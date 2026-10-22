using Hommaterial.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Hommaterial.Windows;

public sealed partial class MainWindow
{
    async Task ShowFormAsync(string title, StackPanel body, Func<Task> submit, string primary = "Salva")
    {
        if (dialogOpen) return;
        dialogOpen = true;
        var feedback = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Error };
        body.Children.Add(feedback);
        var dialog = new ContentDialog { Title = title, Content = new ScrollViewer { Content = body, MaxHeight = 540 },
            PrimaryButtonText = primary, CloseButtonText = "Annulla", XamlRoot = root.XamlRoot, DefaultButton = ContentDialogButton.Primary };
        dialog.PrimaryButtonClick += async (_, args) =>
        {
            var deferral = args.GetDeferral(); args.Cancel = true; dialog.IsPrimaryButtonEnabled = false;
            try { await submit(); args.Cancel = false; }
            catch (Exception e) { feedback.Message = e.Message; feedback.IsOpen = true; }
            finally { dialog.IsPrimaryButtonEnabled = true; deferral.Complete(); }
        };
        try { await dialog.ShowAsync(); }
        finally { dialogOpen = false; RebuildNavigation(); Render(); }
    }
    async Task ShowPanelAsync(string title, StackPanel body)
    {
        if (dialogOpen) return;
        dialogOpen = true;
        var dialog = new ContentDialog { Title = title, Content = new ScrollViewer { Content = body, MaxHeight = 550 }, CloseButtonText = "Chiudi", XamlRoot = root.XamlRoot };
        try { await dialog.ShowAsync(); }
        finally { dialogOpen = false; Render(); }
    }
    sealed class ActionComposer
    {
        readonly MainWindow owner;
        readonly ComboBox type = new() { Header = "Che cosa deve fare", HorizontalAlignment = HorizontalAlignment.Stretch };
        readonly ComboBox target = new() { Header = "Su quale dispositivo", HorizontalAlignment = HorizontalAlignment.Stretch };
        readonly ComboBox power = new() { Header = "Azione", Items = { "Accendi", "Spegni" }, SelectedIndex = 0 };
        readonly ComboBox command = new() { Header = "Tasto del telecomando", HorizontalAlignment = HorizontalAlignment.Stretch };
        readonly NumberBox seconds = new() { Header = "Attendi (secondi)", Minimum = 0, Maximum = 300, Value = 1, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
        readonly List<string> kinds;
        public StackPanel Panel { get; } = new() { Spacing = 12, MinWidth = 320 };
        public ActionComposer(MainWindow owner, bool allowMacro, bool allowWait, HomeAction? initial = null)
        {
            this.owner = owner;
            kinds = ["power", "remote"]; if (allowMacro) kinds.Add("macro"); if (allowWait) kinds.Add("wait");
            foreach (var k in kinds) type.Items.Add(k switch { "power" => "Accendi o spegni", "remote" => "Premi un telecomando", "macro" => "Esegui un dispositivo personalizzato", _ => "Aspetta" });
            Panel.Children.Add(type); Panel.Children.Add(target); Panel.Children.Add(power); Panel.Children.Add(command); Panel.Children.Add(seconds);
            type.SelectionChanged += (_, _) => RebuildTargets();
            target.SelectionChanged += (_, _) => RebuildCommands();
            type.SelectedIndex = Math.Max(0, initial is null ? 0 : kinds.IndexOf(initial.Type));
            if (initial is not null)
            {
                foreach (ComboBoxItem item in target.Items) if (item.Tag?.ToString() == initial.Target) target.SelectedItem = item;
                power.SelectedIndex = initial.Value == "off" ? 1 : 0;
                if (int.TryParse(initial.Value, out var wait)) seconds.Value = wait;
                foreach (ComboBoxItem item in command.Items) if (item.Tag is HomeAction a && a.Value == initial.Value && a.Custom == initial.Custom) command.SelectedItem = item;
            }
        }
        void RebuildTargets()
        {
            target.Items.Clear();
            var kind = kinds[type.SelectedIndex];
            power.Visibility = kind == "power" ? Visibility.Visible : Visibility.Collapsed;
            command.Visibility = kind == "remote" ? Visibility.Visible : Visibility.Collapsed;
            seconds.Visibility = kind == "wait" ? Visibility.Visible : Visibility.Collapsed;
            target.Visibility = kind == "wait" ? Visibility.Collapsed : Visibility.Visible;
            IEnumerable<(string Id, string Name)> choices = kind switch {
                "power" => owner.home.Devices.Where(d => d.HasPower).Select(d => (d.ApplianceId, d.Name)),
                "remote" => owner.home.Remotes.Select(r => (r.Id, r.Name)), "macro" => owner.home.Macros.Select(m => (m.Id, m.Name)), _ => [] };
            foreach (var (id, name) in choices) target.Items.Add(new ComboBoxItem { Content = name, Tag = id });
            if (target.Items.Count > 0) target.SelectedIndex = 0;
        }
        void RebuildCommands()
        {
            command.Items.Clear();
            var id = (target.SelectedItem as ComboBoxItem)?.Tag?.ToString();
            if (id is null) return;
            var remote = owner.home.Remotes.FirstOrDefault(r => r.Id == id);
            if (remote is null) return;
            foreach (var (code, label) in StandardCommands(remote)) command.Items.Add(new ComboBoxItem { Content = label, Tag = new HomeAction("remote", id, code) });
            foreach (var name in owner.home.RemoteButtons.GetValueOrDefault(id) ?? []) command.Items.Add(new ComboBoxItem { Content = owner.ButtonLabel(id, name) + " (personalizzato)", Tag = new HomeAction("remote", id, name, true) });
            if (command.Items.Count > 0) command.SelectedIndex = 0;
        }
        public HomeAction Build()
        {
            var kind = kinds[type.SelectedIndex];
            var id = (target.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "";
            HomeAction action = kind switch {
                "power" => new("power", id, power.SelectedIndex == 0 ? "on" : "off"),
                "remote" => (command.SelectedItem as ComboBoxItem)?.Tag as HomeAction ?? throw new InvalidOperationException("Scegli un tasto del telecomando."),
                "macro" => new("macro", id),
                _ => new("wait", Value: double.IsFinite(seconds.Value) ? ((int)seconds.Value).ToString() : "") };
            BackupCodec.ValidateAction(action); return action;
        }
    }
    static IEnumerable<(string Code, string Label)> StandardCommands(RemoteDevice remote)
    {
        if (remote.Kind == "CUSTOM") return [];
        if (remote.Kind == "TV" && remote.Id.StartsWith(LgTv.Prefix)) return [("turnOn", "Accendi"), ("turnOff", "Spegni"), ("volumeAdd", "Volume ＋"), ("volumeSub", "Volume −"), ("channelAdd", "Canale ＋"), ("channelSub", "Canale −"), ("setMute", "Muto"), .. LgMedia];
        if (remote.Kind == "TV") return [("turnOn", "Accendi"), ("turnOff", "Spegni"), ("volumeAdd", "Volume ＋"), ("volumeSub", "Volume −"), ("channelAdd", "Canale ＋"), ("channelSub", "Canale −")];
        return [("turnOn", "Accendi"), ("turnOff", "Spegni")];
    }
    string Describe(HomeAction action) => action.Type switch {
        "power" => (action.Value == "on" ? "Accendi " : "Spegni ") + (home.Devices.FirstOrDefault(d => d.ApplianceId == action.Target)?.Name ?? "dispositivo mancante"),
        "macro" => "Esegui " + (home.Macros.FirstOrDefault(m => m.Id == action.Target)?.Name ?? "personalizzato mancante"),
        "wait" => "Aspetta " + action.Value + " secondi", _ => (home.Remotes.FirstOrDefault(r => r.Id == action.Target)?.Name ?? "telecomando mancante") + " · " + ButtonLabel(action.Target, action.Value) };
    void RenderAutomations()
    {
        page.Children.Add(Text("Automazioni", 32, true));
        page.Children.Add(Text("Quando cambia qualcosa nella casa, Hommaterial esegue l'azione che scegli. Le condizioni vengono controllate a ogni aggiornamento, anche dall'area di notifica."));
        foreach (var rule in home.Automations)
        {
            var panel = new StackPanel { Spacing = 10 };
            var toggle = new ToggleSwitch { Header = rule.Title, OnContent = "Attiva", OffContent = "In pausa", IsOn = rule.Enabled };
            toggle.Toggled += (_, _) => { rule.Enabled = toggle.IsOn; runtime.Prime(rule); Save(); };
            panel.Children.Add(toggle); panel.Children.Add(Text(rule.Label));
            var actions = Row(); actions.Children.Add(AsyncButton("Modifica", () => EditAutomationAsync(rule)));
            actions.Children.Add(Button("Elimina", () => { home.Automations.Remove(rule); home.Tripped.Remove("automation:" + rule.Id); Save(); Render(); }));
            panel.Children.Add(actions); page.Children.Add(Card(panel));
        }
        page.Children.Add(AsyncButton("＋  Nuova automazione", () => EditAutomationAsync(), true));
    }
    async Task EditAutomationAsync(AutomationRule? existing = null)
    {
        var body = new StackPanel { Spacing = 14 };
        var device = new ComboBox { Header = "Quando questo dispositivo…", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var d in home.Devices.Where(d => d.HasPower || d.IsSensor)) device.Items.Add(new ComboBoxItem { Content = d.Name, Tag = d });
        var trigger = new ComboBox { Header = "Condizione", HorizontalAlignment = HorizontalAlignment.Stretch };
        var threshold = new NumberBox { Header = "Soglia (°C oppure %)", Value = existing?.Threshold ?? 25, Minimum = -100, Maximum = 100, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
        void FillTriggers()
        {
            trigger.Items.Clear();
            var d = (device.SelectedItem as ComboBoxItem)?.Tag as Device;
            var conditions = new List<(string Id, string Label)>();
            if (d?.HasPower == true) conditions.AddRange([("on", "Si accende"), ("off", "Si spegne")]);
            if (d?.IsSensor == true) conditions.AddRange([("temperatureAbove", "Temperatura sopra"), ("temperatureBelow", "Temperatura sotto"), ("humidityAbove", "Umidità sopra"), ("humidityBelow", "Umidità sotto")]);
            foreach (var c in conditions) trigger.Items.Add(new ComboBoxItem { Content = c.Label, Tag = c.Id });
            if (trigger.Items.Count > 0) trigger.SelectedIndex = 0;
        }
        device.SelectionChanged += (_, _) => FillTriggers();
        if (device.Items.Count > 0) device.SelectedIndex = 0;
        if (existing is not null)
        {
            foreach (ComboBoxItem item in device.Items) if ((item.Tag as Device)?.ApplianceId == existing.Device) device.SelectedItem = item;
            foreach (ComboBoxItem item in trigger.Items) if (item.Tag?.ToString() == existing.Trigger) trigger.SelectedItem = item;
        }
        trigger.SelectionChanged += (_, _) => threshold.Visibility = ((trigger.SelectedItem as ComboBoxItem)?.Tag?.ToString() is "on" or "off") ? Visibility.Collapsed : Visibility.Visible;
        var action = new ActionComposer(this, true, false, existing?.Action);
        body.Children.Add(device); body.Children.Add(trigger); body.Children.Add(threshold); body.Children.Add(Text("Allora…", 20, true)); body.Children.Add(action.Panel);
        await ShowFormAsync(existing is null ? "Nuova automazione" : "Modifica automazione", body, () =>
        {
            var d = (device.SelectedItem as ComboBoxItem)?.Tag as Device ?? throw new InvalidOperationException("Scegli il dispositivo da osservare.");
            var condition = trigger.SelectedItem as ComboBoxItem ?? throw new InvalidOperationException("Scegli una condizione.");
            if (!double.IsFinite(threshold.Value)) throw new InvalidOperationException("Inserisci una soglia valida.");
            var chosen = action.Build();
            var rule = new AutomationRule { Id = existing?.Id ?? Guid.NewGuid().ToString(), Device = d.ApplianceId, Trigger = condition.Tag.ToString()!, Threshold = threshold.Value,
                Action = chosen, Title = d.Name + " · " + condition.Content + (condition.Tag.ToString() is "on" or "off" ? "" : " " + threshold.Value), Label = Describe(chosen), Enabled = existing?.Enabled ?? true };
            home.Automations.RemoveAll(a => a.Id == rule.Id); home.Automations.Add(rule); runtime.Prime(rule); Save(); return Task.CompletedTask;
        });
    }
    void RenderSchedules()
    {
        page.Children.Add(Text("Azioni programmate e timer", 32, true));
        page.Children.Add(Text("Una volta o ogni settimana, nei giorni che scegli. Lascia Hommaterial nell'area di notifica per eseguirle con la finestra chiusa. Se il PC è spento o in sospensione, non può inviare comandi."));
        foreach (var s in home.Schedules.OrderBy(s => s.At))
        {
            var panel = new StackPanel { Spacing = 10 };
            var enabled = new ToggleSwitch { Header = s.Label, IsOn = s.Enabled, OnContent = "Attiva", OffContent = "In pausa" };
            enabled.Toggled += (_, _) =>
            { s.Enabled = enabled.IsOn; if (s.Enabled && s.Days.Count > 0) s.At = ScheduleClock.Next(s.Hour, s.Minute, s.Days, DateTimeOffset.Now.ToUnixTimeMilliseconds()); s.RetryAt = null; Save(); };
            panel.Children.Add(enabled);
            var label = Text(Countdown(s)); panel.Children.Add(label); countdowns.Add((label, s));
            panel.Children.Add(Text(s.Days.Count == 0 ? "Una volta" : "Ogni " + string.Join(", ", s.Days.OrderBy(d => d == 1 ? 8 : d).Select(DayName))));
            var actions = Row(); actions.Children.Add(AsyncButton("Modifica", () => EditScheduleAsync(s)));
            actions.Children.Add(Button(s.IsTimer ? "Annulla timer" : "Elimina", () => { home.Schedules.Remove(s); Save(); Render(); }));
            panel.Children.Add(actions); page.Children.Add(Card(panel));
        }
        page.Children.Add(AsyncButton("＋  Nuova azione programmata", () => EditScheduleAsync(), true));
    }
    static string DayName(int day) => new[] { "", "Domenica", "Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato" }[day];
    async Task EditScheduleAsync(HomeSchedule? existing = null)
    {
        var body = new StackPanel { Spacing = 14 };
        var action = new ActionComposer(this, true, false, existing?.Action); body.Children.Add(action.Panel);
        var time = new TimePicker { Header = "A che ora", ClockIdentifier = "24HourClock", Time = new TimeSpan(existing?.Hour ?? DateTime.Now.Hour, existing?.Minute ?? DateTime.Now.Minute, 0) };
        var date = new CalendarDatePicker { Header = "Data (per una sola esecuzione)", Date = existing is null ? DateTimeOffset.Now : DateTimeOffset.FromUnixTimeMilliseconds(existing.At).ToLocalTime() };
        body.Children.Add(time); body.Children.Add(date); body.Children.Add(Text("Ripeti nei giorni (nessuno = una volta)", 14, true));
        var week = new StackPanel { Spacing = 4 };
        var days = new Dictionary<int, CheckBox>();
        foreach (var d in new[] { 2, 3, 4, 5, 6, 7, 1 })
        { var check = new CheckBox { Content = DayName(d), IsChecked = existing?.Days.Contains(d) == true }; days[d] = check; week.Children.Add(check); }
        body.Children.Add(week);
        await ShowFormAsync(existing is null ? "Nuova azione programmata" : "Modifica programma", body, () =>
        {
            var chosen = action.Build(); var selectedDays = days.Where(d => d.Value.IsChecked == true).Select(d => d.Key).ToHashSet();
            long at;
            if (selectedDays.Count > 0) at = ScheduleClock.Next(time.Time.Hours, time.Time.Minutes, selectedDays, DateTimeOffset.Now.ToUnixTimeMilliseconds());
            else
            {
                var local = (date.Date ?? DateTimeOffset.Now).Date.Add(time.Time);
                if (TimeZoneInfo.Local.IsInvalidTime(local)) throw new InvalidOperationException("Quest'orario non esiste per il cambio dell'ora. Scegli un altro orario.");
                at = new DateTimeOffset(local, TimeZoneInfo.Local.GetUtcOffset(local)).ToUnixTimeMilliseconds();
                if (at <= DateTimeOffset.Now.ToUnixTimeMilliseconds()) throw new InvalidOperationException("Scegli un orario futuro o un giorno da ripetere.");
            }
            var schedule = new HomeSchedule { Id = existing?.Id ?? Guid.NewGuid().ToString(), Action = chosen, Label = Describe(chosen), At = at, Hour = time.Time.Hours, Minute = time.Time.Minutes, Days = selectedDays, Enabled = existing?.Enabled ?? true };
            home.Schedules.RemoveAll(s => s.Id == schedule.Id); home.Schedules.Add(schedule); Save(); return Task.CompletedTask;
        });
    }
    async Task EditGroupAsync(HomeGroup? group = null)
    {
        var body = new StackPanel { Spacing = 12 };
        var name = new TextBox { Header = "Nome del gruppo", Text = group?.Name ?? "" }; body.Children.Add(name);
        var checks = new Dictionary<string, CheckBox>();
        foreach (var d in home.Devices.Where(d => d.HasPower))
        { var check = new CheckBox { Content = d.Name, IsChecked = group?.Devices.Contains(d.ApplianceId) == true }; checks[d.ApplianceId] = check; body.Children.Add(check); }
        await ShowFormAsync(group is null ? "Nuovo gruppo" : "Modifica gruppo", body, () =>
        {
            if (string.IsNullOrWhiteSpace(name.Text)) throw new InvalidOperationException("Dai un nome al gruppo.");
            var ids = checks.Where(c => c.Value.IsChecked == true).Select(c => c.Key).ToList();
            if (ids.Count == 0) throw new InvalidOperationException("Scegli almeno un dispositivo.");
            var result = new HomeGroup(group?.Id ?? Guid.NewGuid().ToString(), name.Text.Trim(), ids);
            home.Groups.RemoveAll(g => g.Id == result.Id); home.Groups.Add(result); Save(); return Task.CompletedTask;
        });
    }
    async Task GroupDetailsAsync(HomeGroup group)
    {
        var body = new StackPanel { Spacing = 12 };
        foreach (var d in home.Devices.Where(d => group.Devices.Contains(d.ApplianceId)))
        {
            var on = home.States.GetValueOrDefault(d.ApplianceId)?.Power == true;
            body.Children.Add(AsyncButton(d.Name + " · " + (on ? "Spegni" : "Accendi"), () => ExecuteActionAsync(new("power", d.ApplianceId, on ? "off" : "on"), lifetime.Token)));
        }
        AddFavoriteControl(body, "group:" + group.Id); AddActionTimer(body, "Timer del gruppo", async on =>
        { foreach (var id in group.Devices) AddNativeTimer(new("power", id, on.On ? "on" : "off"), group.Name, on.Minutes); await Task.CompletedTask; });
        await ShowPanelAsync(group.Name, body);
        // Editing is available from the Groups management row on right-click / details.
    }
    async Task EditMacroAsync(HomeMacro? macro = null)
    {
        var body = new StackPanel { Spacing = 14 };
        var name = new TextBox { Header = "Nome", Text = macro?.Name ?? "" }; body.Children.Add(name);
        var condition = new ComboBox { Header = "Dipende da un dispositivo?", HorizontalAlignment = HorizontalAlignment.Stretch };
        condition.Items.Add(new ComboBoxItem { Content = "No, esegui sempre le stesse azioni", Tag = "" });
        foreach (var d in home.Devices.Where(d => d.HasPower)) condition.Items.Add(new ComboBoxItem { Content = d.Name, Tag = d.ApplianceId });
        condition.SelectedIndex = 0;
        foreach (ComboBoxItem item in condition.Items) if (item.Tag?.ToString() == macro?.Condition) condition.SelectedItem = item;
        body.Children.Add(condition);
        var steps = new List<ActionComposer>(); var otherwise = new List<ActionComposer>();
        void Section(string title, List<ActionComposer> rows, IEnumerable<HomeAction> initial)
        {
            body.Children.Add(Text(title, 18, true)); var list = new StackPanel { Spacing = 10 }; body.Children.Add(list);
            void Add(HomeAction? action = null)
            {
                var editor = new ActionComposer(this, false, true, action); rows.Add(editor);
                var row = new StackPanel { Spacing = 8 }; row.Children.Add(editor.Panel);
                row.Children.Add(Button("Rimuovi questa azione", () => { rows.Remove(editor); list.Children.Remove(row); }));
                list.Children.Add(Card(row));
            }
            foreach (var a in initial) Add(a);
            body.Children.Add(Button("＋  Aggiungi azione", () => Add()));
        }
        Section("Azioni (sempre, oppure quando è acceso)", steps, macro?.Steps ?? []);
        Section("Altrimenti (quando è spento)", otherwise, macro?.Otherwise ?? []);
        await ShowFormAsync(macro is null ? "Nuovo dispositivo personalizzato" : "Modifica personalizzato", body, () =>
        {
            if (string.IsNullOrWhiteSpace(name.Text)) throw new InvalidOperationException("Dai un nome al dispositivo.");
            var actions = steps.Select(s => s.Build()).ToList(); var alternatives = otherwise.Select(s => s.Build()).ToList();
            if (actions.Count == 0) throw new InvalidOperationException("Aggiungi almeno un'azione.");
            var depends = (condition.SelectedItem as ComboBoxItem)?.Tag?.ToString();
            var result = new HomeMacro(macro?.Id ?? Guid.NewGuid().ToString(), name.Text.Trim(), string.IsNullOrEmpty(depends) ? null : depends, actions, alternatives);
            home.Macros.RemoveAll(m => m.Id == result.Id); home.Macros.Add(result); Save(); return Task.CompletedTask;
        });
    }
    async Task MacroDetailsAsync(HomeMacro macro)
    {
        var body = new StackPanel { Spacing = 12 };
        body.Children.Add(AsyncButton("▶  Esegui", () => RunMacroAsync(macro), true));
        foreach (var step in macro.Steps ?? []) body.Children.Add(Text(Describe(step)));
        if (macro.Condition is not null)
        { body.Children.Add(Text("Altrimenti", 16, true)); foreach (var step in macro.Otherwise ?? []) body.Children.Add(Text(Describe(step))); }
        AddFavoriteControl(body, "macro:" + macro.Id); AddPlaceControl(body, "macro:" + macro.Id);
        AddActionTimer(body, "Esegui tra…", selection => { AddNativeTimer(new("macro", macro.Id), macro.Name, selection.Minutes); return Task.CompletedTask; }, false);
        await ShowPanelAsync(macro.Name, body);
    }
    void AddFavoriteControl(StackPanel body, string key)
    {
        if (!CanConfigure) return;
        var favorite = new ToggleSwitch { Header = "Nei preferiti", IsOn = home.Favorites.Contains(key) };
        favorite.Toggled += (_, _) => { if (favorite.IsOn) home.Favorites.Add(key); else home.Favorites.Remove(key); Save(); };
        body.Children.Add(favorite);
    }
    void AddPlaceControl(StackPanel body, string key)
    {
        if (!CanConfigure) return;
        var room = new ComboBox { Header = "Stanza", HorizontalAlignment = HorizontalAlignment.Stretch };
        room.Items.Add(new ComboBoxItem { Content = "Nessuna stanza", Tag = "" });
        foreach (var r in home.AllRooms()) room.Items.Add(new ComboBoxItem { Content = r, Tag = r });
        room.SelectedIndex = 0;
        var current = home.Rooms.GetValueOrDefault(key) ?? home.Devices.FirstOrDefault(d => d.ApplianceId == key)?.Room;
        foreach (ComboBoxItem item in room.Items) if (item.Tag?.ToString() == current) room.SelectedItem = item;
        room.SelectionChanged += (_, _) => { home.Rooms[key] = (room.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? ""; Save(); };
        body.Children.Add(room);
    }
    void AddActionTimer(StackPanel body, string title, Func<(double Minutes, bool On), Task> add, bool power = true)
    {
        body.Children.Add(Text(title, 18, true));
        var minutes = new NumberBox { Header = "Minuti", Value = 15, Minimum = 1, Maximum = 1440, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
        var on = new ToggleSwitch { Header = "Azione", OnContent = "Accendi", OffContent = "Spegni", Visibility = power ? Visibility.Visible : Visibility.Collapsed };
        body.Children.Add(minutes); body.Children.Add(on);
        body.Children.Add(AsyncButton("Avvia timer", async () => { await add((minutes.Value, on.IsOn)); Message("Timer avviato.", InfoBarSeverity.Success); }));
    }
    void RenderRooms()
    {
        page.Children.Add(Text("Stanze e ordine", 32, true));
        page.Children.Add(Text("Le modifiche restano in Hommaterial e non cambiano la configurazione Alexa."));
        foreach (var room in home.AllRooms())
        {
            var content = new StackPanel { Spacing = 12 }; content.Children.Add(Text(room, 20, true));
            var actions = Row(); actions.Children.Add(AsyncButton("Modifica", () => EditRoomAsync(room)));
            actions.Children.Add(Button("Rimuovi stanza", () =>
            { foreach (var e in AllEntries().Where(e => e.Room == room).ToList()) home.Rooms[e.Key] = ""; home.RoomList.Remove(room); Save(); RebuildNavigation(); Render(); }));
            content.Children.Add(actions); page.Children.Add(Card(content));
        }
        page.Children.Add(AsyncButton("＋  Nuova stanza", () => EditRoomAsync(), true));
        page.Children.Add(Text("Riordina le card", 22, true));
        var entries = AllEntries().ToList();
        var ordering = home.Order.GetValueOrDefault("all") ?? [];
        entries = entries.OrderBy(e => ordering.IndexOf(e.Key) is int i && i >= 0 ? i : int.MaxValue).ToList();
        for (var index = 0; index < entries.Count; index++)
        {
            var i = index; var row = Row(); row.Children.Add(Text(entries[i].Name));
            void Move(int by)
            { var keys = entries.Select(e => e.Key).ToList(); (keys[i], keys[i + by]) = (keys[i + by], keys[i]); home.Order["all"] = keys; Save(); Render(); }
            var up = Button("↑", () => Move(-1)); up.IsEnabled = i > 0; row.Children.Add(up);
            var down = Button("↓", () => Move(1)); down.IsEnabled = i < entries.Count - 1; row.Children.Add(down); page.Children.Add(row);
        }
        foreach (var group in home.Groups) page.Children.Add(AsyncButton("Modifica gruppo: " + group.Name, () => EditGroupAsync(group)));
        foreach (var macro in home.Macros) page.Children.Add(AsyncButton("Modifica personalizzato: " + macro.Name, () => EditMacroAsync(macro)));
    }
    async Task EditRoomAsync(string? existing = null)
    {
        var body = new StackPanel { Spacing = 12 }; var name = new TextBox { Header = "Nome della stanza", Text = existing ?? "" }; body.Children.Add(name);
        var checks = new Dictionary<string, CheckBox>();
        foreach (var e in AllEntries().Where(e => e.Group is null))
        { var check = new CheckBox { Content = e.Name, IsChecked = existing is not null && e.Room == existing }; checks[e.Key] = check; body.Children.Add(check); }
        await ShowFormAsync(existing is null ? "Nuova stanza" : "Modifica stanza", body, () =>
        {
            var room = name.Text.Trim(); if (room.Length == 0) throw new InvalidOperationException("Scrivi il nome della stanza.");
            if (existing != room && home.AllRooms().Contains(room)) throw new InvalidOperationException("Esiste già una stanza con questo nome.");
            foreach (var e in AllEntries().Where(e => e.Group is null).ToList())
            {
                if (checks[e.Key].IsChecked == true) home.Rooms[e.Key] = room;
                else if (e.Room == existing && existing is not null) home.Rooms[e.Key] = "";
            }
            if (existing is not null) home.RoomList.Remove(existing); if (!home.RoomList.Contains(room)) home.RoomList.Add(room);
            Save(); return Task.CompletedTask;
        });
    }
}
