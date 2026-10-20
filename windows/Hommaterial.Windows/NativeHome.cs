using System.Numerics;
using Hommaterial.Core;
using Microsoft.UI;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.System;
using Windows.UI.ViewManagement;

namespace Hommaterial.Windows;

public sealed partial class MainWindow
{
    HomeRuntime runtime = null!;
    SwitchBotClient switchBotClient = null!;
    readonly CancellationTokenSource lifetime = new();
    NotificationService notifications = null!;
    System.Windows.Forms.NotifyIcon? tray;
    SwitchBotKeys? demoKeys;
    bool showHidden, rendering, monitorUnlocked;
    bool CanConfigure => !home.Monitor.Enabled || home.MonitorPin is null || monitorUnlocked;
    string lastPage = "";
    bool dashboardVisible;
    readonly List<Action> refreshDeviceCards = [];
    TextBlock? poweredSummary, updatedSubtitle, searchActionHint;
    Button? refreshDashboardButton;
    readonly List<(TextBlock Label, HomeSchedule Schedule)> countdowns = [];
    readonly Grid toolbar = new() { Padding = new Thickness(32, 14, 32, 8), ColumnSpacing = 10 };
    sealed record Entry(string Key, string Name, string? Room, Device? Device = null, HomeGroup? Group = null, HomeMacro? Macro = null, RemoteDevice? Remote = null);

    void InitializeNativeFeatures(Grid body, ScrollViewer scroll)
    {
        runtime = new(() => home, ExecuteActionAsync, Save, Notify);
        switchBotClient = new(() => demo ? demoKeys : store.ReadSwitchBot());
        notifications = new((title, text) => { EnsureTray(); tray?.ShowBalloonTip(6000, title, text, System.Windows.Forms.ToolTipIcon.Info); });
        body.RowDefinitions[2].Height = GridLength.Auto;
        body.RowDefinitions.Add(new() { Height = new GridLength(1, GridUnitType.Star) });
        Grid.SetRow(scroll, 3);
        toolbar.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) });
        toolbar.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
        search.Width = double.NaN; search.MaxWidth = 540; search.HorizontalAlignment = HorizontalAlignment.Left;
        search.MinWidth = 260; search.PlaceholderText = "Cerca nella casa  ·  Ctrl+K";
        toolbar.Children.Add(search);
        var voice = AsyncButton("🎙  Voce", VoiceAsync); Grid.SetColumn(voice, 1); toolbar.Children.Add(voice);
        Grid.SetRow(toolbar, 2); body.Children.Add(toolbar);
        AddShortcut(VirtualKey.K, VirtualKeyModifiers.Control, () =>
        {
            if (dialogOpen) return;
            selected = "all"; query = search.Text.Trim(); RebuildNavigation(); Render();
            search.Focus(FocusState.Keyboard);
            FindVisual<TextBox>(search)?.SelectAll();
        });
        AddShortcut(VirtualKey.Escape, VirtualKeyModifiers.None, () => { if (!dialogOpen) { search.Text = ""; query = ""; Render(); } });
        AddShortcut(VirtualKey.F5, VirtualKeyModifiers.None, () => _ = RefreshAsync());
        root.PointerPressed += (_, _) => WakeMonitor();
        root.KeyDown += (_, _) => WakeMonitor();
        EnsureTray();
        ApplyMonitor();
    }
    void AddShortcut(VirtualKey key, VirtualKeyModifiers modifiers, Action action)
    {
        var accelerator = new KeyboardAccelerator { Key = key, Modifiers = modifiers };
        accelerator.Invoked += (_, args) => { if (!dialogOpen) { action(); args.Handled = true; } };
        root.KeyboardAccelerators.Add(accelerator);
    }
    static T? FindVisual<T>(DependencyObject element) where T : DependencyObject
    {
        if (element is T match) return match;
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(element); i++)
            if (FindVisual<T>(VisualTreeHelper.GetChild(element, i)) is T child) return child;
        return null;
    }
    void EnsureTray()
    {
        if (tray is not null) return;
        tray = new System.Windows.Forms.NotifyIcon { Text = "Hommaterial", Visible = true,
            Icon = new System.Drawing.Icon(Path.Combine(AppContext.BaseDirectory, "Assets", "App.ico")) };
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Apri Hommaterial", null, (_, _) => { AppWindow.Show(); Activate(); });
        menu.Items.Add("Esci completamente", null, async (_, _) => { AppWindow.Show(); Activate(); if (await UnlockMonitorAsync()) { closingAllowed = true; Close(); } });
        tray.ContextMenuStrip = menu;
        tray.DoubleClick += (_, _) => { AppWindow.Show(); Activate(); };
        tray.BalloonTipClicked += (_, _) => { AppWindow.Show(); Activate(); };
    }
    void Notify(string title, string text)
    {
        Message(title + ": " + text);
        if (home.Notifications && !demo) notifications.Show(title, text);
    }

    IEnumerable<Entry> AllEntries()
    {
        foreach (var d in home.Devices) if (showHidden || !home.Hidden.Contains(d.ApplianceId)) yield return new(d.ApplianceId, d.Name, home.RoomOf(d), Device: d);
        foreach (var g in home.Groups) yield return new("group:" + g.Id, g.Name, null, Group: g);
        foreach (var m in home.Macros) yield return new("macro:" + m.Id, m.Name, home.PlaceOf("macro:" + m.Id), Macro: m);
        foreach (var r in home.Remotes) if (showHidden || !home.HiddenRemotes.Contains(r.Id)) yield return new("remote:" + r.Id, r.Name, home.PlaceOf("remote:" + r.Id), Remote: r);
    }
    IEnumerable<Entry> NativeEntries()
    {
        var searching = !string.IsNullOrWhiteSpace(query);
        var entries = AllEntries().Where(e => searching
            ? e.Name.Contains(query, StringComparison.CurrentCultureIgnoreCase) || (e.Room?.Contains(query, StringComparison.CurrentCultureIgnoreCase) ?? false)
            : selected switch {
                "favorites" => home.Favorites.Contains(e.Key), "on" => e.Device is not null && home.States.GetValueOrDefault(e.Key)?.Power == true,
                "groups" => e.Group is not null, "macros" => e.Macro is not null, "remotes" => e.Remote is not null,
                _ => !selected.StartsWith("room:") || e.Room == selected[5..] });
        var order = home.Order.GetValueOrDefault(selected) ?? [];
        if (home.Monitor.Enabled && home.Monitor.Only.TryGetValue(selected, out var only)) entries = entries.Where(e => only.Contains(e.Key) || e.Macro is not null && only.Contains(e.Macro.Id) || e.Remote is not null && only.Contains(e.Remote.Id));
        return entries.OrderBy(e => searching && e.Name.Equals(query, StringComparison.CurrentCultureIgnoreCase) ? -2 : order.IndexOf(e.Key) is var index && index >= 0 ? index : int.MaxValue)
            .ThenBy(e => e.Name, StringComparer.CurrentCultureIgnoreCase);
    }
    async Task ActivateFirstResultAsync()
    {
        if (dialogOpen || string.IsNullOrWhiteSpace(query)) return;
        if (NativeEntries().FirstOrDefault() is Entry first) await ActivateEntryAsync(first);
    }
    async Task ActivateEntryAsync(Entry entry)
    {
        if (entry.Device is Device device)
        {
            var state = home.States.GetValueOrDefault(device.ApplianceId);
            if (device.HasPower && state?.Power is bool on)
                await CommandAsync(device, new { action = on ? "turnOff" : "turnOn" }, s => s with { Power = !on });
            else await DetailsAsync(device);
        }
        else if (entry.Group is HomeGroup group) await ToggleGroupAsync(group);
        else if (entry.Macro is HomeMacro macro) await RunMacroAsync(macro);
        else if (entry.Remote is RemoteDevice remote) await RemoteDetailsAsync(remote);
    }
    async Task OpenEntryAsync(Entry entry)
    {
        if (entry.Device is Device d) await DetailsAsync(d);
        else if (entry.Group is HomeGroup g) await GroupDetailsAsync(g);
        else if (entry.Macro is HomeMacro m) await MacroDetailsAsync(m);
        else if (entry.Remote is RemoteDevice r) await RemoteDetailsAsync(r);
    }

    void RenderNative()
    {
        if (rendering) return;
        rendering = true;
        try
        {
            dashboardVisible = false;
            refreshDeviceCards.Clear(); poweredSummary = updatedSubtitle = searchActionHint = null;
            refreshDashboardButton = null;
            page.Children.Clear(); countdowns.Clear();
            var welcome = !demo && !client.IsLoggedIn && home.Devices.Count == 0 && home.Remotes.Count == 0;
            toolbar.Visibility = welcome || new[] { "settings", "automations", "schedules", "rooms" }.Contains(selected) ? Visibility.Collapsed : Visibility.Visible;
            if (selected == "settings") { RenderNativeSettings(); return; }
            if (selected == "automations") { RenderAutomations(); return; }
            if (selected == "schedules") { RenderSchedules(); return; }
            if (selected == "rooms") { RenderRooms(); return; }
            if (welcome) { RenderWelcome(); return; }
            var title = query.Length > 0 ? "Risultati nella casa" : selected switch {
                "favorites" => "I tuoi preferiti", "on" => "Dispositivi accesi", "groups" => "I tuoi gruppi", "macros" => "Dispositivi personalizzati", "remotes" => "Telecomandi", _ => selected.StartsWith("room:") ? selected[5..] : "La tua casa" };
            var header = new Grid { ColumnSpacing = 12 };
            header.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) });
            header.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
            updatedSubtitle = Text(DashboardSubtitle());
            header.Children.Add(new StackPanel { Spacing = 6, Children = { Text(title, 32, true), updatedSubtitle } });
            var refresh = AsyncButton("↻  Aggiorna", () => RefreshAsync()); refresh.IsEnabled = !busy;
            refreshDashboardButton = refresh;
            Grid.SetColumn(refresh, 1); header.Children.Add(refresh); page.Children.Add(header);
            if (home.Monitor.Enabled && home.Monitor.Clock) { monitorClock = Text(DateTime.Now.ToString("HH:mm:ss"), 28, true); page.Children.Add(monitorClock); }
            if (demo) page.Children.Add(new InfoBar { IsOpen = true, IsClosable = false, Title = "Prova liberamente", Message = "Tutte le azioni sono simulate.", ActionButton = AsyncButton("Collega Amazon", LeaveDemoAndLoginAsync) });
            if (selected == "all" && query.Length == 0)
            {
                var summary = Row(24); summary.Children.Add(Text($"{home.Devices.Count} dispositivi", 15, true));
                poweredSummary = Text($"{home.States.Count(s => s.Value.Power == true)} accesi", 15, true);
                summary.Children.Add(poweredSummary);
                summary.Children.Add(Text($"{home.AllRooms().Count()} stanze", 15, true)); page.Children.Add(summary);
                RenderActivity();
            }
            var entries = NativeEntries().ToList();
            if (selected == "all" && query.Length == 0)
            {
                var favorites = entries.Where(e => home.Favorites.Contains(e.Key)).ToList();
                if (favorites.Count > 0)
                {
                    page.Children.Add(Text("Preferiti", 20, true));
                    var favoriteGrid = DeviceGrid();
                    ScrollViewer.SetVerticalScrollMode(favoriteGrid, ScrollMode.Disabled);
                    var favoriteOrder = home.Order.GetValueOrDefault("favorites") ?? [];
                    foreach (var entry in favorites.OrderBy(e => favoriteOrder.IndexOf(e.Key) is int i && i >= 0 ? i : int.MaxValue)) favoriteGrid.Items.Add(NativeDeviceCard(entry, false));
                    page.Children.Add(favoriteGrid);
                }
            }
            if (query.Length > 0 && entries.Count > 0)
            {
                searchActionHint = Text(SearchActionHint(entries[0]), 14, true);
                page.Children.Add(searchActionHint);
            }
            if (entries.Count == 0) page.Children.Add(Card(new StackPanel { Spacing = 8, Children = { Text("Qui c'è spazio per qualcosa di nuovo", 20, true), Text(query.Length > 0 ? "Prova un altro nome o una stanza." : "Aggiungi dispositivi o personalizza questa sezione.") } }));
            var grouped = query.Length > 0 ? entries.GroupBy(_ => "") : entries.GroupBy(e => e.Group is not null ? "Gruppi" : e.Room ?? (e.Macro is not null ? "Personalizzati" : e.Remote is not null ? "Telecomandi" : "Altri dispositivi"));
            foreach (var group in grouped)
            {
                var section = new StackPanel { Spacing = 12 };
                if (group.Key.Length > 0)
                {
                    var room = Row(12); room.Children.Add(Text(group.Key, 20, true));
                    var powered = group.Where(e => e.Device?.HasPower == true).Select(e => e.Device!).ToList();
                    if (powered.Count > 0)
                    {
                        room.Children.Add(AsyncButton("⏻  Stanza", async () =>
                        { var on = !powered.All(d => home.States.GetValueOrDefault(d.ApplianceId)?.Power == true); await RoomPowerAsync(group.Key, powered, on); }));
                    }
                    section.Children.Add(room);
                }
                if (home.Layout == 1)
                {
                    var list = new StackPanel { Spacing = 8 };
                    foreach (var entry in OrderSection(group.Key, group)) list.Children.Add(NativeDeviceCard(entry, query.Length > 0 && entry == entries[0], true));
                    section.Children.Add(list);
                }
                else
                {
                    var grid = DeviceGrid();
                    ScrollViewer.SetVerticalScrollMode(grid, ScrollMode.Disabled);
                    foreach (var entry in OrderSection(group.Key, group)) grid.Items.Add(NativeDeviceCard(entry, query.Length > 0 && entry == entries[0]));
                    section.Children.Add(grid);
                }
                page.Children.Add(section);
            }
            if (selected is "groups" or "macros") page.Children.Add(AsyncButton("＋  " + (selected == "groups" ? "Nuovo gruppo" : "Nuovo dispositivo personalizzato"), selected == "groups" ? () => EditGroupAsync() : () => EditMacroAsync(), true));
            page.Children.Add(Text("Clic: accendi, spegni o esegui · Tasto destro / pressione prolungata: dettagli · Ctrl+K: cerca · Invio: primo risultato", 12));
            dashboardVisible = true;
        }
        finally
        {
            rendering = false;
            if (lastPage != selected) { AnimateEntrance(page); lastPage = selected; }
        }
    }

    FrameworkElement NativeDeviceCard(Entry entry, bool highlighted, bool list = false)
    {
        var state = entry.Device is not null ? home.States.GetValueOrDefault(entry.Key) : null;
        var on = state?.Power == true;
        var content = new StackPanel { Spacing = 10, Width = list ? double.NaN : home.Monitor.Enabled ? 190 + home.Monitor.TileSize * 35 : 236 };
        var icon = new FontIcon { Glyph = entry.Device?.IsSensor == true ? "\uE9CA" : entry.Group is not null ? "\uE902" : entry.Macro is not null ? "\uE768" : entry.Remote is not null ? "\uE7F4" : entry.Device?.Category == "LIGHT" ? "\uE793" : "\uE7E8",
            FontSize = 28, HorizontalAlignment = HorizontalAlignment.Left, Foreground = on ? Accent : Muted };
        content.Children.Add(icon); content.Children.Add(Text(entry.Name, 17, true));
        var status = entry.Device is not null ? state?.Description ?? "Stato da aggiornare"
            : entry.Group is not null ? $"{entry.Group.Devices.Count} dispositivi · clic per commutare"
            : entry.Macro is not null ? $"{entry.Macro.Steps?.Count ?? 0} azioni · clic per eseguire" : "Clic per il telecomando";
        var statusLabel = Text((home.Favorites.Contains(entry.Key) ? "★  " : "") + status, 13);
        content.Children.Add(statusLabel);
        var button = new Button { Content = content, Padding = new Thickness(22), CornerRadius = new CornerRadius(14),
            HorizontalContentAlignment = HorizontalAlignment.Stretch, HorizontalAlignment = HorizontalAlignment.Stretch,
            BorderThickness = new Thickness(highlighted ? 2 : 1),
            Margin = new Thickness(0, 0, 12, 12), MinHeight = list ? 80 : 190, IsEnabled = !pending.Contains(entry.Key) };
        AutomationProperties.SetName(button, entry.Name + ", " + status);
        AutomationProperties.SetHelpText(button, "Invio: azione principale. Maiusc+F10 o tasto destro: dettagli.");
        ToolTipService.SetToolTip(button, "Clic per interagire · tasto destro per i dettagli");
        void RefreshCard()
        {
            var current = entry.Device is null ? null : home.States.GetValueOrDefault(entry.Key);
            var isOn = current?.Power == true;
            var description = entry.Device is null ? status : current?.Description ?? "Stato da aggiornare";
            statusLabel.Text = (home.Favorites.Contains(entry.Key) ? "★  " : "") + description;
            icon.Foreground = isOn ? AppPalette.Accent(button.ActualTheme) : Muted;
            button.IsEnabled = !pending.Contains(entry.Key);
            AutomationProperties.SetName(button, entry.Name + ", " + description);
            AppPalette.ApplyCard(button, isOn, highlighted);
        }
        refreshDeviceCards.Add(RefreshCard);
        button.ActualThemeChanged += (_, _) => RefreshCard();
        button.Loaded += (_, _) => RefreshCard();
        RefreshCard();
        button.Click += async (_, _) => { try { await ActivateEntryAsync(entry); } catch (Exception e) { ShowError(e); } };
        button.RightTapped += async (_, args) => { args.Handled = true; await OpenEntryAsync(entry); };
        button.Holding += async (_, args) => { if (args.HoldingState == Microsoft.UI.Input.HoldingState.Started) { args.Handled = true; await OpenEntryAsync(entry); } };
        button.KeyDown += async (_, args) =>
        { if (args.Key == VirtualKey.F10 && Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift).HasFlag(global::Windows.UI.Core.CoreVirtualKeyStates.Down)) { args.Handled = true; await OpenEntryAsync(entry); } };
        // Keep the card and its border inside the grid container's clipping bounds.
        // Only the icon moves, with ample room inside the card's existing padding.
        var hovered = false;
        button.PointerEntered += (_, _) => { hovered = true; AnimateIconScale(icon, 1.08f); };
        button.PointerExited += (_, _) => { hovered = false; AnimateIconScale(icon, 1f); };
        button.PointerPressed += (_, _) => AnimateIconScale(icon, .94f);
        button.PointerReleased += (_, _) => AnimateIconScale(icon, hovered ? 1.08f : 1f);
        button.PointerCanceled += (_, _) => { hovered = false; AnimateIconScale(icon, 1f); };
        return button;
    }
    // Re-created result containers must not replay WinUI's default entrance/reorder animations.
    // Page navigation still has its own transition; state updates retain the existing controls.
    static GridView DeviceGrid() => new() { SelectionMode = ListViewSelectionMode.None, IsTabStop = false,
        HorizontalAlignment = HorizontalAlignment.Stretch, ItemContainerTransitions = new TransitionCollection() };
    string DashboardSubtitle() => demo ? "Casa dimostrativa · nessun comando reale" : home.UpdatedAt is DateTimeOffset at ? $"Tutto a portata di clic · aggiornato alle {at:HH:mm}" : "Tutto a portata di clic";
    string SearchActionHint(Entry first)
    {
        var verb = first.Device is { HasPower: true } ? home.States.GetValueOrDefault(first.Key)?.Power == true ? "spegnere" : "accendere" : first.Macro is not null ? "eseguire" : first.Group is not null ? "commutare" : "aprire";
        return $"Invio per {verb} {first.Name}";
    }
    void RefreshDashboardStates()
    {
        // The 'on' page changes membership when power changes, so only that case needs reflow.
        if (!dashboardVisible || selected == "on") { Render(); return; }
        foreach (var refresh in refreshDeviceCards) refresh();
        if (poweredSummary is not null) poweredSummary.Text = $"{home.States.Count(s => s.Value.Power == true)} accesi";
        if (updatedSubtitle is not null) updatedSubtitle.Text = DashboardSubtitle();
        if (searchActionHint is not null && NativeEntries().FirstOrDefault() is Entry first) searchActionHint.Text = SearchActionHint(first);
    }
    IEnumerable<Entry> OrderSection(string name, IEnumerable<Entry> entries)
    {
        var key = name switch { "Gruppi" => "groups", "Personalizzati" => "macros", "Telecomandi" => "remotes", _ => "room:" + name };
        var saved = home.Order.GetValueOrDefault(key) ?? [];
        return entries.OrderBy(e => saved.IndexOf(e.Key) is int i && i >= 0 ? i : int.MaxValue);
    }
    static bool MotionEnabled => new UISettings().AnimationsEnabled;
    static void AnimateIconScale(FontIcon element, float scale)
    {
        if (!MotionEnabled) return;
        var visual = ElementCompositionPreview.GetElementVisual(element);
        visual.CenterPoint = new Vector3((float)element.ActualWidth / 2, (float)element.ActualHeight / 2, 0);
        var animation = visual.Compositor.CreateVector3KeyFrameAnimation();
        animation.InsertKeyFrame(1, new Vector3(scale, scale, 1)); animation.Duration = TimeSpan.FromMilliseconds(170);
        visual.StartAnimation("Scale", animation);
    }
    static void AnimateEntrance(FrameworkElement element)
    {
        if (!MotionEnabled) return;
        var visual = ElementCompositionPreview.GetElementVisual(element);
        var animation = visual.Compositor.CreateScalarKeyFrameAnimation();
        animation.InsertKeyFrame(0, .35f); animation.InsertKeyFrame(1, 1); animation.Duration = TimeSpan.FromMilliseconds(230);
        visual.StartAnimation("Opacity", animation);
        ElementCompositionPreview.SetIsTranslationEnabled(element, true);
        var slide = visual.Compositor.CreateVector3KeyFrameAnimation(); slide.InsertKeyFrame(0, new(0, 12, 0)); slide.InsertKeyFrame(1, Vector3.Zero);
        slide.Duration = TimeSpan.FromMilliseconds(260); visual.StartAnimation("Translation", slide);
    }
    async Task ExecuteActionAsync(HomeAction action, CancellationToken cancellation)
    {
        cancellation.ThrowIfCancellationRequested();
        if (action.Type == "power")
        {
            var device = home.Devices.FirstOrDefault(d => d.ApplianceId == action.Target) ?? throw new InvalidOperationException("Dispositivo non trovato.");
            if (!await CommandAsync(device, new { action = action.Value == "on" ? "turnOn" : "turnOff" }, s => s with { Power = action.Value == "on" })) throw new InvalidOperationException(notice.Message ?? "Comando non riuscito.");
        }
        else if (action.Type == "remote")
        {
            if (!home.Remotes.Any(r => r.Id == action.Target)) throw new InvalidOperationException("Telecomando non trovato.");
            if (!demo) await switchBotClient.SendAsync(action.Target, action.Value, custom: action.Custom, cancellation: cancellation);
        }
        else throw new InvalidOperationException("Azione non supportata.");
    }
    async Task RunMacroAsync(HomeMacro macro)
    {
        if (!pending.Add("macro:" + macro.Id)) return;
        try { await runtime.RunActionAsync(new("macro", macro.Id), lifetime.Token); Message("Eseguito: " + macro.Name, InfoBarSeverity.Success); }
        catch (Exception error) { ShowError(error); }
        finally { pending.Remove("macro:" + macro.Id); Render(); }
    }
    async Task ToggleGroupAsync(HomeGroup group)
    {
        var members = home.Devices.Where(d => group.Devices.Contains(d.ApplianceId) && d.HasPower).ToList();
        if (members.Count == 0) { Message("Il gruppo non contiene dispositivi controllabili."); return; }
        var on = !members.All(d => home.States.GetValueOrDefault(d.ApplianceId)?.Power == true);
        foreach (var d in members) await ExecuteActionAsync(new("power", d.ApplianceId, on ? "on" : "off"), lifetime.Token);
    }
    void AddNativeTimer(HomeAction action, string label, double minutes)
    {
        if (!double.IsFinite(minutes) || minutes is < 1 or > 1440) throw new InvalidOperationException("Scegli un tempo tra 1 e 1440 minuti.");
        var due = DateTimeOffset.Now.AddMinutes(minutes);
        home.Schedules.Add(new() { Action = action, Label = label, At = due.ToUnixTimeMilliseconds(), Hour = due.Hour, Minute = due.Minute, IsTimer = true });
        Save(); Render();
    }
    void RenderActivity()
    {
        var schedules = home.Schedules.Where(s => s.Enabled).OrderBy(s => s.At).ToList();
        var rules = home.Automations.Where(a => a.Enabled).ToList();
        if (schedules.Count + rules.Count == 0) return;
        var panel = new StackPanel { Spacing = 10 }; panel.Children.Add(Text("La casa si organizza", 18, true));
        foreach (var s in schedules)
        {
            var label = Text(Countdown(s), 13); countdowns.Add((label, s)); panel.Children.Add(label);
        }
        foreach (var a in rules) panel.Children.Add(Text("ϟ  " + a.Title + " → " + a.Label, 13));
        panel.Children.Add(Button("Gestisci programmi e timer", () => { selected = "schedules"; RebuildNavigation(); Render(); }));
        page.Children.Add(Card(panel));
    }
    static string Countdown(HomeSchedule s)
    {
        var remaining = TimeSpan.FromMilliseconds(Math.Max(0, s.At - DateTimeOffset.Now.ToUnixTimeMilliseconds()));
        return $"{(s.IsTimer ? "◷" : "▦")}  {s.Label} · {DateTimeOffset.FromUnixTimeMilliseconds(s.At).ToLocalTime():ddd HH:mm} · tra {(int)remaining.TotalHours:D2}:{remaining.Minutes:D2}:{remaining.Seconds:D2}";
    }
    async Task TickNativeFeaturesAsync()
    {
        foreach (var (label, schedule) in countdowns) label.Text = Countdown(schedule);
        TickMonitor();
        if (busy || pending.Count > 0) return;
        var count = home.Schedules.Count;
        try { await runtime.TickAsync(DateTimeOffset.Now.ToUnixTimeMilliseconds(), lifetime.Token); }
        catch (OperationCanceledException) { }
        catch (Exception e) { ShowError(e); }
        if (count != home.Schedules.Count && !dialogOpen) Render();
    }
}
