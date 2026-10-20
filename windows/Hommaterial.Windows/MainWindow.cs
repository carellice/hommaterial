using System.Diagnostics;
using Hommaterial.Core;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;

namespace Hommaterial.Windows;

public sealed partial class MainWindow : Window
{
    readonly LocalStore store = new();
    HomeData home;
    AlexaClient client;
    readonly NavigationView navigation = new() { PaneDisplayMode = NavigationViewPaneDisplayMode.Auto,
        OpenPaneLength = 244, IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed, IsSettingsVisible = true };
    readonly Grid root = new();
    readonly StackPanel page = new() { Spacing = 22, Padding = new Thickness(32, 24, 32, 32) };
    readonly InfoBar notice = new() { IsOpen = false, IsClosable = true, Margin = new Thickness(32, 8, 32, 0) };
    readonly ProgressBar progress = new() { IsIndeterminate = true, Visibility = Visibility.Collapsed, Height = 3 };
    readonly AutoSuggestBox search = new() { PlaceholderText = "Cerca un dispositivo…", Width = 300, QueryIcon = new SymbolIcon(Symbol.Find) };
    readonly DispatcherTimer polling = new() { Interval = TimeSpan.FromSeconds(45) };
    readonly DispatcherTimer timerClock = new() { Interval = TimeSpan.FromSeconds(1) };
    readonly HashSet<string> pending = [];
    bool demo, busy, rebuildingNavigation, dialogOpen, closingAllowed;
    string selected = "all";
    string query = "";
    static readonly SolidColorBrush Accent = new(AppPalette.LogoBlue);
    static readonly SolidColorBrush Muted = new(ColorHelper.FromArgb(255, 116, 124, 130));

    public MainWindow()
    {
        home = store.Load();
        client = NewClient();
        Title = "Hommaterial";
        SystemBackdrop = new MicaBackdrop();
        AppWindow.Resize(new SizeInt32(1240, 860));
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "App.ico"));
        if (AppWindow.Presenter is Microsoft.UI.Windowing.OverlappedPresenter presenter)
            presenter.PreferredMinimumWidth = 520;
        root.RowDefinitions.Add(new() { Height = new GridLength(44) });
        root.RowDefinitions.Add(new() { Height = new GridLength(1, GridUnitType.Star) });
        var title = new Grid { Padding = new Thickness(20, 0, 0, 0) };
        var brand = Row(10);
        brand.VerticalAlignment = VerticalAlignment.Center;
        brand.Children.Add(new Image { Source = new Microsoft.UI.Xaml.Media.Imaging.BitmapImage(new Uri(Path.Combine(AppContext.BaseDirectory, "Assets", "Logo.png"))), Width = 22, Height = 22 });
        brand.Children.Add(Text("Hommaterial", 13, true));
        title.Children.Add(brand);
        root.Children.Add(title);
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(title);
        Grid.SetRow(navigation, 1);
        root.Children.Add(navigation);
        navigation.PaneHeader = new StackPanel { Margin = new Thickness(16, 12, 0, 12), Spacing = 4,
            Children = { Text("LA TUA CASA", 11, true), Text("Uno spazio, tutto sotto controllo.", 12) } };
        var body = new Grid();
        body.RowDefinitions.Add(new() { Height = GridLength.Auto });
        body.RowDefinitions.Add(new() { Height = GridLength.Auto });
        body.RowDefinitions.Add(new() { Height = new GridLength(1, GridUnitType.Star) });
        body.Children.Add(progress);
        Grid.SetRow(notice, 1); body.Children.Add(notice);
        var scroll = new ScrollViewer { Content = page, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        Grid.SetRow(scroll, 2); body.Children.Add(scroll);
        navigation.Content = body;
        navigation.SelectionChanged += async (_, args) =>
        {
            if (rebuildingNavigation) return;
            var next = args.IsSettingsSelected ? "settings" : (args.SelectedItem as NavigationViewItem)?.Tag?.ToString() ?? "all";
            if (next is "settings" or "rooms" or "automations" or "schedules" && !await UnlockMonitorAsync()) { RebuildNavigation(); return; }
            selected = next;
            Render();
        };
        search.TextChanged += (_, _) => { query = search.Text.Trim(); if (selected != "settings") Render(); };
        search.QuerySubmitted += async (_, _) =>
        {
            if (!string.IsNullOrWhiteSpace(query)) await ActivateFirstResultAsync();
        };
        Content = root;
        InitializeNativeFeatures(body, scroll);
        ApplyTheme();
        RebuildNavigation();
        Render();
        polling.Tick += async (_, _) => { if (!dialogOpen) { if (demo) await runtime.ReceiveAsync(home.States, DateTimeOffset.Now.ToUnixTimeMilliseconds()); else if (client.IsLoggedIn) await RefreshAsync(false); } };
        timerClock.Tick += async (_, _) => await TickTimersAsync();
        polling.Start(); timerClock.Start();
        root.Loaded += async (_, _) => { if (!demo && client.IsLoggedIn) await RefreshAsync(); if (!demo && store.ReadSwitchBot() is not null) await RefreshRemotesAsync(); };
        AppWindow.Closing += (sender, args) =>
        {
            if (closingAllowed) return;
            if (home.RunInBackground && !demo && tray is not null) { args.Cancel = true; AppWindow.Hide(); return; }
            if (!home.Schedules.Any(s => s.Enabled) && !home.Automations.Any(a => a.Enabled)) return;
            args.Cancel = true;
            _ = ConfirmCloseAsync();
        };
        Closed += (_, _) => { polling.Stop(); timerClock.Stop(); lifetime.Cancel(); client.Dispose(); switchBotClient.Dispose(); notifications.Dispose(); tray?.Dispose(); };
        if (Environment.GetCommandLineArgs().Contains("--demo")) StartDemo();
    }

    AlexaClient NewClient() => new(Marketplace.All.FirstOrDefault(m => m.Domain == home.Marketplace) ?? Marketplace.All[0], store.ReadToken, store.SaveToken);
    static StackPanel Row(double gap = 12) => new() { Orientation = Orientation.Horizontal, Spacing = gap };
    static TextBlock Text(string text, double size = 14, bool strong = false) => new() { Text = text, FontSize = size,
        FontWeight = strong ? Microsoft.UI.Text.FontWeights.SemiBold : Microsoft.UI.Text.FontWeights.Normal,
        TextWrapping = TextWrapping.Wrap };
    static Button Button(string label, Action action, bool accent = false)
    {
        var button = new Button { Content = label };
        if (accent) button.Style = (Style)Application.Current.Resources["AccentButtonStyle"];
        button.Click += (_, _) => action();
        return button;
    }
    Button AsyncButton(string label, Func<Task> action, bool accent = false)
    {
        var button = Button(label, () => { }, accent);
        button.Click += async (_, _) =>
        {
            button.IsEnabled = false;
            try { await action(); }
            catch (Exception error) { ShowError(error); }
            finally { button.IsEnabled = true; }
        };
        return button;
    }
    static Border Card(UIElement content) => new() { Child = content, CornerRadius = new CornerRadius(12),
        Padding = new Thickness(20), Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
        BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"], BorderThickness = new Thickness(1) };
    void ShowError(Exception error)
    {
        LocalStore.Log(error);
        Message(error is HttpRequestException ? "Connessione non riuscita. " + error.Message : error.Message, InfoBarSeverity.Error);
    }
    void Message(string message, InfoBarSeverity severity = InfoBarSeverity.Informational)
    { notice.Title = ""; notice.Message = message; notice.Severity = severity; notice.IsOpen = true; }
    void Save() { if (!demo) store.Save(home); }
    void ApplyTheme() => root.RequestedTheme = home.Theme switch { "Light" => ElementTheme.Light, "Dark" => ElementTheme.Dark, _ => ElementTheme.Default };

    void RebuildNavigation()
    {
        rebuildingNavigation = true;
        navigation.MenuItems.Clear();
        void Add(string name, string tag, Symbol icon) => navigation.MenuItems.Add(new NavigationViewItem { Content = name, Tag = tag, Icon = new SymbolIcon(icon) });
        Add("Panoramica", "all", Symbol.Home);
        Add("Preferiti", "favorites", Symbol.Favorite);
        Add("Dispositivi accesi", "on", Symbol.Important);
        Add("Gruppi", "groups", Symbol.People);
        Add("Personalizzati", "macros", Symbol.Play);
        Add("Telecomandi", "remotes", Symbol.Remote);
        Add("Automazioni", "automations", Symbol.Important);
        Add("Azioni programmate", "schedules", Symbol.Calendar);
        Add("Stanze e ordine", "rooms", Symbol.Edit);
        navigation.MenuItems.Add(new NavigationViewItemHeader { Content = "STANZE" });
        foreach (var room in home.AllRooms()) Add(room, "room:" + room, Symbol.MapPin);
        if (home.Monitor.Enabled && home.Monitor.Pages is { Count: > 0 } pages)
        {
            var allowed = navigation.MenuItems.OfType<NavigationViewItem>().Where(i => pages.Contains(i.Tag?.ToString() ?? "")).OrderBy(i => pages.IndexOf(i.Tag.ToString()!)).ToList();
            navigation.MenuItems.Clear(); foreach (var item in allowed) navigation.MenuItems.Add(item);
            if (navigation.MenuItems.Count == 0) Add("Panoramica", "all", Symbol.Home);
            if (selected != "settings" && !pages.Contains(selected)) selected = navigation.MenuItems.OfType<NavigationViewItem>().First().Tag.ToString()!;
        }
        navigation.SelectedItem = selected == "settings" ? navigation.SettingsItem
            : navigation.MenuItems.OfType<NavigationViewItem>().FirstOrDefault(i => i.Tag?.ToString() == selected)
              ?? navigation.MenuItems[0];
        navigation.PaneFooter = new StackPanel { Margin = new Thickness(16, 12, 16, 20), Spacing = 6,
            Children = { Text(demo ? "MODALITÀ DEMO" : client.IsLoggedIn ? "ACCOUNT COLLEGATO" : "BENVENUTO", 11, true), Text("Hommaterial per Windows · 0.1", 11) } };
        rebuildingNavigation = false;
    }

    IEnumerable<Device> VisibleDevices() => NativeEntries().Where(e => e.Device is not null).Select(e => e.Device!);

    void Render() => RenderNative();

    async Task RefreshAsync(bool discover = true)
    {
        if (busy || pending.Count > 0) return;
        if (demo) { home.UpdatedAt = DateTimeOffset.Now; RefreshDashboardStates(); return; }
        if (!client.IsLoggedIn) { await LoginAsync(); return; }
        busy = true; progress.Visibility = Visibility.Visible;
        try
        {
            var devices = discover ? await client.DevicesAsync() : home.Devices;
            var states = await client.StatesAsync(devices);
            home.Devices = devices; home.States = states; home.UpdatedAt = DateTimeOffset.Now;
            Save();
            if (discover) { RebuildNavigation(); Render(); }
            else RefreshDashboardStates();
            busy = false;
            await runtime.ReceiveAsync(states, DateTimeOffset.Now.ToUnixTimeMilliseconds(), lifetime.Token);
        }
        catch (Exception error) { ShowError(error); }
        finally { busy = false; progress.Visibility = Visibility.Collapsed; if (refreshDashboardButton is not null) refreshDashboardButton.IsEnabled = true; }
    }

    async Task<bool> CommandAsync(Device device, object parameters, Func<DeviceState, DeviceState> update)
    {
        if (busy) { Message("Attendi la fine dell'aggiornamento prima di inviare un comando."); return false; }
        if (!pending.Add(device.ApplianceId)) return false;
        try
        {
            if (!demo) await client.ControlAsync(device, parameters);
            home.States[device.ApplianceId] = update(home.States.GetValueOrDefault(device.ApplianceId) ?? new()) with { Reachable = true };
            Save(); return true;
        }
        catch (Exception error) { ShowError(error); return false; }
        finally { pending.Remove(device.ApplianceId); if (!dialogOpen) RefreshDashboardStates(); }
    }

    async Task RoomPowerAsync(string name, List<Device> devices, bool on)
    {
        if (dialogOpen) return;
        var confirm = new ContentDialog { Title = (on ? "Accendere " : "Spegnere ") + name + "?",
            Content = $"Il comando verrà inviato a {devices.Count} dispositivi di questa stanza.", PrimaryButtonText = "Conferma", CloseButtonText = "Annulla", XamlRoot = root.XamlRoot };
        dialogOpen = true;
        try { if (await confirm.ShowAsync() != ContentDialogResult.Primary) return; }
        finally { dialogOpen = false; }
        var results = new List<bool>();
        foreach (var device in devices) results.Add(await CommandAsync(device, new { action = on ? "turnOn" : "turnOff" }, s => s with { Power = on }));
        if (results.All(r => r)) Message("Comando inviato alla stanza " + name + ".", InfoBarSeverity.Success);
    }

    async Task DetailsAsync(Device device)
    {
        if (dialogOpen) return;
        dialogOpen = true;
        var state = home.States.GetValueOrDefault(device.ApplianceId) ?? new();
        var content = new StackPanel { Spacing = 16, MinWidth = 320 };
        content.Children.Add(Text((home.RoomOf(device) ?? "Altri dispositivi") + " · " + state.Description));
        var feedback = new InfoBar { IsOpen = false, IsClosable = false };
        content.Children.Add(feedback);
        async Task Execute(object parameters, Func<DeviceState, DeviceState> update)
        {
            var ok = await CommandAsync(device, parameters, update);
            feedback.IsOpen = true; feedback.Severity = ok ? InfoBarSeverity.Success : InfoBarSeverity.Error;
            feedback.Message = ok ? "Comando inviato." : notice.Message;
        }
        if (device.HasPower)
        {
            var power = Row();
            power.Children.Add(AsyncButton("Accendi", () => Execute(new { action = "turnOn" }, s => s with { Power = true }), true));
            power.Children.Add(AsyncButton("Spegni", () => Execute(new { action = "turnOff" }, s => s with { Power = false })));
            content.Children.Add(power);
        }
        if (device.HasBrightness)
        {
            var brightness = new Slider { Header = "Luminosità", Minimum = 1, Maximum = 100, Value = state.Brightness ?? 50, StepFrequency = 1 };
            content.Children.Add(brightness);
            content.Children.Add(AsyncButton("Applica luminosità", () => Execute(new { action = "setBrightness", brightness = (int)brightness.Value }, s => s with { Brightness = (int)brightness.Value })));
        }
        if (device.HasColor || device.HasColorTemperature)
        {
            var colors = new ComboBox { Header = "Colore della luce", HorizontalAlignment = HorizontalAlignment.Stretch };
            var choices = new List<(string Label, string Name, bool White)>();
            if (device.HasColorTemperature) choices.AddRange([("Bianco caldo", "warm_white", true), ("Bianco naturale", "white", true), ("Bianco freddo", "cool_white", true)]);
            if (device.HasColor) choices.AddRange([("Rosso", "red", false), ("Arancione", "orange", false), ("Giallo", "yellow", false), ("Verde", "green", false), ("Azzurro", "sky_blue", false), ("Blu", "blue", false), ("Viola", "purple", false), ("Rosa", "pink", false)]);
            foreach (var c in choices) colors.Items.Add(c.Label);
            colors.SelectedIndex = 0; content.Children.Add(colors);
            content.Children.Add(AsyncButton("Applica colore", async () =>
            {
                var color = choices[colors.SelectedIndex];
                object command = color.White ? new { action = "setColorTemperature", colorTemperatureName = color.Name }
                    : new { action = "setColor", colorName = color.Name };
                await Execute(command, s => s with { ColorName = color.Name });
            }));
        }
        AddDeviceExtras(content, device);
        if (device.HasPower)
        {
            content.Children.Add(Text("Timer", 18, true));
            content.Children.Add(Text("Funziona anche con la finestra chiusa se Hommaterial resta nell’area di notifica. Richiede PC acceso e connesso.", 12));
            var minutes = new NumberBox { Header = "Tra quanti minuti", Value = 15, Minimum = 1, Maximum = 1440, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
            var action = new ComboBox { Header = "Azione", Items = { "Spegni", "Accendi" }, SelectedIndex = 0 };
            content.Children.Add(minutes); content.Children.Add(action);
            content.Children.Add(Button("Avvia timer", () =>
            {
                if (!double.IsFinite(minutes.Value) || minutes.Value < 1 || minutes.Value > 1440)
                { feedback.Message = "Inserisci da 1 a 1440 minuti."; feedback.Severity = InfoBarSeverity.Warning; feedback.IsOpen = true; return; }
                AddNativeTimer(new HomeAction("power", device.ApplianceId, action.SelectedIndex == 1 ? "on" : "off"), device.Name, minutes.Value);
                feedback.Message = "Timer avviato."; feedback.Severity = InfoBarSeverity.Success; feedback.IsOpen = true; Render();
            }));
        }
        if (CanConfigure) content.Children.Add(Button(home.Hidden.Contains(device.ApplianceId) ? "Mostra nella casa" : "Nascondi dalla casa", () =>
        { if (!home.Hidden.Add(device.ApplianceId)) home.Hidden.Remove(device.ApplianceId); Save(); Render(); }));
        var dialog = new ContentDialog { Title = device.Name, Content = new ScrollViewer { Content = content, MaxHeight = 520 }, CloseButtonText = "Chiudi", XamlRoot = root.XamlRoot };
        try { await dialog.ShowAsync(); }
        finally { dialogOpen = false; Render(); }
    }

    void RenderWelcome()
    {
        var intro = new StackPanel { Spacing = 20, MaxWidth = 660, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 36, 0, 0) };
        intro.Children.Add(new Image { Width = 86, Height = 86, HorizontalAlignment = HorizontalAlignment.Left,
            Source = new Microsoft.UI.Xaml.Media.Imaging.BitmapImage(new Uri(Path.Combine(AppContext.BaseDirectory, "Assets", "Logo.png"))) });
        intro.Children.Add(Text("La tua casa.\nFinalmente sul tuo PC.", 40, true));
        intro.Children.Add(Text("Luci, prese e sensori Alexa in un'app nativa per Windows 11. Scegli una stanza e ritrova subito ciò che ti serve.", 18));
        var actions = Row();
        actions.Children.Add(AsyncButton("Collega il tuo account Amazon", LoginAsync, true));
        actions.Children.Add(Button("Prova la demo", StartDemo)); intro.Children.Add(actions);
        intro.Children.Add(Card(new StackPanel { Spacing = 10, Children = { Text("Prima di iniziare", 16, true),
            Text("Hommaterial è un progetto indipendente, non affiliato ad Amazon. Usa le stesse API non ufficiali della versione Android: Amazon può modificarle o limitare l'account. Nessuna garanzia di funzionamento."),
            Text("L'accesso si svolge sul sito Amazon nel tuo browser. Il token resta su questo PC, protetto dal tuo account Windows. Nessuna pubblicità o telemetria.", 13) } }));
        page.Children.Add(intro);
    }

    void StartDemo()
    {
        if (busy || dialogOpen || pending.Count > 0) return;
        demo = true; home = DemoHome.Create(); selected = "all"; query = ""; search.Text = "";
        notice.IsOpen = false; RebuildNavigation(); Render();
    }
    async Task LeaveDemoAndLoginAsync()
    {
        if (busy || dialogOpen || pending.Count > 0) return;
        demo = false; home = store.Load(); selected = "all";
        client.Dispose(); client = NewClient(); ApplyTheme(); RebuildNavigation(); Render();
        if (client.IsLoggedIn) await RefreshAsync(); else await LoginAsync();
    }

    async Task LoginAsync()
    {
        if (dialogOpen || busy || pending.Count > 0) return;
        dialogOpen = true;
        var content = new StackPanel { Spacing = 14, MinWidth = 360 };
        content.Children.Add(Text("Accedi nel browser Amazon. Al termine copia dalla barra l'indirizzo completo della pagina maplanding e incollalo qui. La password e la verifica in due passaggi restano nel browser."));
        var consent = new CheckBox { Content = "Accetto le avvertenze sulle API non ufficiali." };
        content.Children.Add(consent);
        var market = new ComboBox { Header = "Marketplace del tuo account", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var m in Marketplace.All) market.Items.Add(m.Domain);
        market.SelectedIndex = Array.FindIndex(Marketplace.All, m => m.Domain == home.Marketplace);
        content.Children.Add(market);
        LoginAttempt? attempt = null;
        AlexaClient? loginClient = null;
        string? pendingToken = null;
        var feedback = new InfoBar { IsOpen = false, IsClosable = false };
        var redirect = new TextBox { Header = "Indirizzo dopo l'accesso", PlaceholderText = "https://www.amazon.com/ap/maplanding?…", TextWrapping = TextWrapping.Wrap, MaxHeight = 110 };
        var open = Button("1. Apri Amazon nel browser", () =>
        {
            try
            {
                loginClient?.Dispose();
                pendingToken = null;
                loginClient = new(Marketplace.All[market.SelectedIndex], () => pendingToken, token => pendingToken = token);
                attempt = loginClient.NewLoginAttempt();
                Process.Start(new ProcessStartInfo(attempt.Url) { UseShellExecute = true });
                market.IsEnabled = false; redirect.Text = "";
            }
            catch (Exception error) { feedback.Message = error.Message; feedback.IsOpen = true; feedback.Severity = InfoBarSeverity.Error; }
        });
        open.IsEnabled = false;
        consent.Checked += (_, _) => open.IsEnabled = true;
        consent.Unchecked += (_, _) => open.IsEnabled = false;
        content.Children.Add(open); content.Children.Add(redirect); content.Children.Add(feedback);
        var dialog = new ContentDialog { Title = "Collega Amazon", Content = content, PrimaryButtonText = "2. Completa l'accesso", CloseButtonText = "Annulla", XamlRoot = root.XamlRoot };
        var connected = false;
        dialog.PrimaryButtonClick += async (_, args) =>
        {
            args.Cancel = true;
            if (attempt is null || loginClient is null || consent.IsChecked != true)
            { feedback.Message = "Accetta le avvertenze e apri prima la pagina di accesso."; feedback.IsOpen = true; return; }
            var deferral = args.GetDeferral();
            dialog.IsPrimaryButtonEnabled = false; dialog.IsSecondaryButtonEnabled = false; dialog.CloseButtonText = ""; open.IsEnabled = false;
            try
            {
                await loginClient.CompleteLoginAsync(attempt, redirect.Text.Trim());
                store.SaveToken(pendingToken ?? throw new InvalidOperationException("Token Amazon mancante."));
                home = new HomeData { Theme = home.Theme, Marketplace = loginClient.Market.Domain };
                 Save(); connected = true;
                client.Dispose(); client = NewClient(); dialog.Hide();
            }
            catch (Exception error) { feedback.Message = error.Message; feedback.IsOpen = true; feedback.Severity = InfoBarSeverity.Error; }
            finally { dialog.IsPrimaryButtonEnabled = true; dialog.CloseButtonText = "Annulla"; open.IsEnabled = consent.IsChecked == true; deferral.Complete(); }
        };
        try { await dialog.ShowAsync(); }
        finally { dialogOpen = false; loginClient?.Dispose(); redirect.Text = ""; }
        if (connected) { RebuildNavigation(); await RefreshAsync(); }
    }

    void RenderSettings() => RenderNativeSettings();

    async Task LogoutAsync()
    {
        if (dialogOpen || busy || pending.Count > 0) return;
        dialogOpen = true;
        var dialog = new ContentDialog { Title = "Uscire dall'account?", Content = "Token, cache, preferiti, impostazioni e timer di Windows saranno cancellati da questo PC.", PrimaryButtonText = "Esci", CloseButtonText = "Annulla", XamlRoot = root.XamlRoot };
        try
        {
            if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
             store.Clear(); home = new(); client.Dispose(); client = NewClient(); selected = "all";
            notice.IsOpen = false; ApplyTheme(); RebuildNavigation(); Render();
        }
        finally { dialogOpen = false; }
    }

    async Task TickTimersAsync() => await TickNativeFeaturesAsync();
    async Task ConfirmCloseAsync()
    {
        if (dialogOpen) return;
        dialogOpen = true;
        var dialog = new ContentDialog { Title = "Chiudere Hommaterial?", Content = "Puoi lasciare l'app nell'area di notifica per eseguire timer e azioni programmate. Uscendo completamente, riprenderanno al prossimo avvio; dopo 10 minuti quelli scaduti non saranno eseguiti.", PrimaryButtonText = "Resta in background", SecondaryButtonText = "Esci completamente", CloseButtonText = "Annulla", XamlRoot = root.XamlRoot };
        try
        {
            var result = await dialog.ShowAsync();
            if (result == ContentDialogResult.Primary) { EnsureTray(); AppWindow.Hide(); }
            else if (result == ContentDialogResult.Secondary) { closingAllowed = true; Close(); }
        }
        finally { dialogOpen = false; }
    }
}
