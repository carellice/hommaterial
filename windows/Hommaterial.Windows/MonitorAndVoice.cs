using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using Hommaterial.Core;
using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Media.SpeechRecognition;

namespace Hommaterial.Windows;

public sealed partial class MainWindow
{
    DateTimeOffset lastInteraction = DateTimeOffset.Now;
    Border? restOverlay;
    TextBlock? restClock, restDetails, monitorClock;
    bool keepDisplayAwake;
    [DllImport("kernel32.dll")] static extern uint SetThreadExecutionState(uint flags);
    void ApplyMonitor()
    {
        polling.Interval = TimeSpan.FromSeconds(home.Monitor.Enabled ? Math.Max(30, home.Monitor.RefreshSeconds) : 45);
        var awake = home.Monitor.Enabled && home.Monitor.KeepOn;
        if (awake != keepDisplayAwake) { SetThreadExecutionState(awake ? 0x80000002 : 0x80000000); keepDisplayAwake = awake; }
        AppWindow.SetPresenter(home.Monitor.Enabled && home.Monitor.Fullscreen ? AppWindowPresenterKind.FullScreen : AppWindowPresenterKind.Overlapped);
        if (!home.Monitor.Enabled) { restOverlay?.SetValue(UIElement.VisibilityProperty, Visibility.Collapsed); monitorUnlocked = false; }
        lastInteraction = DateTimeOffset.Now;
    }
    void WakeMonitor()
    {
        lastInteraction = DateTimeOffset.Now;
        if (restOverlay is not null && restOverlay.Visibility == Visibility.Visible)
        { restOverlay.Visibility = Visibility.Collapsed; AnimateEntrance(page); }
    }
    void TickMonitor()
    {
        if (monitorClock is not null) monitorClock.Text = DateTime.Now.ToString("HH:mm:ss");
        if (!home.Monitor.Enabled || !home.Monitor.Rest || dialogOpen || DateTimeOffset.Now - lastInteraction < TimeSpan.FromMinutes(Math.Max(1, home.Monitor.RestMinutes))) return;
        if (restOverlay is null)
        {
            var body = new StackPanel { Spacing = 20, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, MaxWidth = 800 };
            restClock = Text("", 90, true); restDetails = Text("", 20);
            body.Children.Add(restClock); body.Children.Add(restDetails); body.Children.Add(Text("Tocca o premi un tasto per tornare alla casa", 13));
            restOverlay = new Border { Child = body, Background = new SolidColorBrush(Colors.Black), Padding = new Thickness(32), CornerRadius = new CornerRadius(0) };
            restClock.Foreground = new SolidColorBrush(Colors.White); restDetails.Foreground = new SolidColorBrush(Colors.White);
            Grid.SetRowSpan(restOverlay, 2); Canvas.SetZIndex(restOverlay, 100); root.Children.Add(restOverlay);
            restOverlay.PointerPressed += (_, _) => WakeMonitor();
        }
        restOverlay.Visibility = Visibility.Visible;
        var monitor = home.Monitor; var hour = DateTime.Now.Hour;
        var night = monitor.Night && (monitor.NightFrom <= monitor.NightTo ? hour >= monitor.NightFrom && hour < monitor.NightTo : hour >= monitor.NightFrom || hour < monitor.NightTo);
        restClock!.Text = monitor.RestItems.Contains("clock") || night ? DateTime.Now.ToString("HH:mm") : "";
        var lines = new List<string>();
        if (!night)
        {
            if (monitor.RestItems.Contains("date")) lines.Add(DateTime.Now.ToString("dddd d MMMM"));
            if (monitor.RestItems.Contains("sensors")) foreach (var d in home.Devices.Where(d => d.IsSensor && (monitor.RestSensors is null || monitor.RestSensors.Contains(d.ApplianceId)))) lines.Add(d.Name + " · " + home.States.GetValueOrDefault(d.ApplianceId)?.Description);
            if (monitor.RestItems.Contains("power")) lines.Add("Accesi: " + home.States.Count(s => s.Value.Power == true));
            if (monitor.RestItems.Contains("timers")) foreach (var s in home.Schedules.Where(s => s.Enabled).OrderBy(s => s.At).Take(4)) lines.Add(Countdown(s));
        }
        restDetails!.Text = string.Join("\n", lines);
        restClock.Opacity = night ? .12 : Math.Clamp(monitor.RestBrightness / 100.0, .05, 1);
        restDetails.Opacity = Math.Clamp(monitor.RestBrightness / 100.0, .05, 1);
        monitorUnlocked = false;
    }
    static string HashPin(string pin)
    {
        var salt = RandomNumberGenerator.GetBytes(16);
        return Convert.ToHexString(salt) + ":" + Convert.ToHexString(Rfc2898DeriveBytes.Pbkdf2(pin, salt, 100_000, HashAlgorithmName.SHA256, 32));
    }
    static bool VerifyPin(string pin, string stored)
    {
        try
        {
            var parts = stored.Split(':'); var salt = Convert.FromHexString(parts[0]); var expected = Convert.FromHexString(parts[1]);
            return CryptographicOperations.FixedTimeEquals(expected, Rfc2898DeriveBytes.Pbkdf2(pin, salt, 100_000, HashAlgorithmName.SHA256, 32));
        }
        catch { return false; }
    }
    async Task<bool> UnlockMonitorAsync()
    {
        if (!home.Monitor.Enabled || home.MonitorPin is null || monitorUnlocked) return true;
        if (dialogOpen) return false;
        dialogOpen = true;
        var pin = new PasswordBox { Header = "PIN del monitor", MaxLength = 12 };
        var error = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Error, Message = "PIN non corretto." };
        var dialog = new ContentDialog { Title = "Sblocca le impostazioni", Content = new StackPanel { Spacing = 12, Children = { pin, error } }, PrimaryButtonText = "Sblocca", CloseButtonText = "Annulla", XamlRoot = root.XamlRoot };
        dialog.PrimaryButtonClick += (_, args) => { if (!VerifyPin(pin.Password, home.MonitorPin)) { args.Cancel = true; error.IsOpen = true; } else monitorUnlocked = true; };
        try { await dialog.ShowAsync(); return monitorUnlocked; }
        finally { dialogOpen = false; pin.Password = ""; }
    }
    async Task MonitorSettingsAsync()
    {
        if (!await UnlockMonitorAsync()) return;
        var current = home.Monitor;
        var body = new StackPanel { Spacing = 14 };
        ToggleSwitch Toggle(string title, bool value) { var control = new ToggleSwitch { Header = title, IsOn = value }; body.Children.Add(control); return control; }
        var enabled = Toggle("Modalità monitor", current.Enabled); var fullscreen = Toggle("Schermo intero", current.Fullscreen);
        var awake = Toggle("Mantieni lo schermo acceso", current.KeepOn); var clock = Toggle("Mostra l'orologio", current.Clock);
        var size = new ComboBox { Header = "Dimensione delle card", Items = { "Compatta", "Media", "Grande" }, SelectedIndex = Math.Clamp(current.TileSize, 0, 2) }; body.Children.Add(size);
        var refresh = new NumberBox { Header = "Aggiorna ogni (secondi)", Minimum = 30, Maximum = 3600, Value = Math.Max(30, current.RefreshSeconds) }; body.Children.Add(refresh);
        var rest = Toggle("Schermata di riposo", current.Rest);
        var minutes = new NumberBox { Header = "Riposo dopo (minuti)", Minimum = 1, Maximum = 120, Value = Math.Max(1, current.RestMinutes) }; body.Children.Add(minutes);
        var brightness = new Slider { Header = "Luminosità del contenuto a riposo", Minimum = 5, Maximum = 100, Value = current.RestBrightness }; body.Children.Add(brightness);
        var night = Toggle("Di notte mostra solo l'orologio", current.Night);
        var nightFrom = new NumberBox { Header = "Notte dalle (ora)", Minimum = 0, Maximum = 23, Value = current.NightFrom };
        var nightTo = new NumberBox { Header = "Fino alle (ora)", Minimum = 0, Maximum = 23, Value = current.NightTo }; body.Children.Add(nightFrom); body.Children.Add(nightTo);
        var restItems = new Dictionary<string, CheckBox>();
        foreach (var (key, label) in new[] { ("clock", "Orologio"), ("date", "Data"), ("sensors", "Sensori"), ("power", "Dispositivi accesi"), ("timers", "Timer e programmi") })
        { var check = new CheckBox { Content = "A riposo: " + label, IsChecked = current.RestItems.Contains(key) }; restItems[key] = check; body.Children.Add(check); }
        var availablePages = new List<(string Key, string Label)> { ("all", "Panoramica"), ("favorites", "Preferiti"), ("groups", "Gruppi"), ("macros", "Personalizzati"), ("remotes", "Telecomandi") };
        availablePages.AddRange(home.AllRooms().Select(r => ("room:" + r, r)));
        var pages = new Dictionary<string, CheckBox>();
        body.Children.Add(Text("Pagine del monitor, nell'ordine scelto", 18, true));
        var pageRows = new StackPanel { Spacing = 8 }; body.Children.Add(pageRows);
        var pageOrder = (current.Pages ?? availablePages.Select(p => p.Key).ToList()).Concat(availablePages.Select(p => p.Key)).Distinct().Where(k => availablePages.Any(p => p.Key == k)).ToList();
        foreach (var (key, label) in availablePages) pages[key] = new CheckBox { Content = label, IsChecked = current.Pages is null || current.Pages.Contains(key) };
        void FillPages()
        {
            pageRows.Children.Clear();
            foreach (var key in pageOrder)
            {
                if (pages[key].Parent is Panel old) old.Children.Remove(pages[key]);
                var row = Row(); row.Children.Add(pages[key]);
                var index = pageOrder.IndexOf(key);
                var up = Button("↑", () => { (pageOrder[index], pageOrder[index - 1]) = (pageOrder[index - 1], pageOrder[index]); FillPages(); }); up.IsEnabled = index > 0; row.Children.Add(up);
                var down = Button("↓", () => { (pageOrder[index], pageOrder[index + 1]) = (pageOrder[index + 1], pageOrder[index]); FillPages(); }); down.IsEnabled = index < pageOrder.Count - 1; row.Children.Add(down);
                pageRows.Children.Add(row);
            }
        }
        FillPages();
        body.Children.Add(Text("Dispositivi visibili in ogni pagina", 18, true));
        var pageChoice = new ComboBox { Header = "Pagina da personalizzare" }; foreach (var p in availablePages) pageChoice.Items.Add(new ComboBoxItem { Content = p.Label, Tag = p.Key });
        var entriesPanel = new StackPanel { Spacing = 4 }; var selectedOnly = current.Only.ToDictionary(p => p.Key, p => new List<string>(p.Value));
        var all = new CheckBox { Content = "Tutti i dispositivi di questa pagina" };
        string pageKey = "all";
        void FillEntries()
        {
            pageKey = (pageChoice.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "all"; entriesPanel.Children.Clear();
            all.IsChecked = !selectedOnly.ContainsKey(pageKey);
            foreach (var e in AllEntries())
            {
                var check = new CheckBox { Content = e.Name, IsChecked = !selectedOnly.ContainsKey(pageKey) || selectedOnly[pageKey].Contains(e.Key), IsEnabled = all.IsChecked != true };
                check.Checked += (_, _) => { if (!selectedOnly.ContainsKey(pageKey)) selectedOnly[pageKey] = []; if (!selectedOnly[pageKey].Contains(e.Key)) selectedOnly[pageKey].Add(e.Key); };
                check.Unchecked += (_, _) => { if (selectedOnly.TryGetValue(pageKey, out var ids)) ids.Remove(e.Key); };
                entriesPanel.Children.Add(check);
            }
        }
        pageChoice.SelectionChanged += (_, _) => FillEntries();
        all.Click += (_, _) => { if (all.IsChecked == true) selectedOnly.Remove(pageKey); else selectedOnly[pageKey] = AllEntries().Select(e => e.Key).ToList(); FillEntries(); };
        body.Children.Add(pageChoice); body.Children.Add(all); body.Children.Add(entriesPanel); pageChoice.SelectedIndex = 0;
        var pin = new PasswordBox { Header = "Nuovo PIN (vuoto = mantieni quello attuale)", MaxLength = 12 }; body.Children.Add(pin);
        var removePin = new CheckBox { Content = "Rimuovi il PIN attuale" }; body.Children.Add(removePin);
        await ShowFormAsync("Configura monitor", body, () =>
        {
            if (enabled.IsOn && !pages.Values.Any(c => c.IsChecked == true)) throw new InvalidOperationException("Scegli almeno una pagina.");
            if (!double.IsFinite(minutes.Value) || !double.IsFinite(refresh.Value) || !double.IsFinite(nightFrom.Value) || !double.IsFinite(nightTo.Value)) throw new InvalidOperationException("Completa tutti gli intervalli con numeri validi.");
            if (pin.Password.Length > 0 && (pin.Password.Length < 4 || pin.Password.Any(c => !char.IsDigit(c)))) throw new InvalidOperationException("Il PIN deve contenere da 4 a 12 cifre.");
            home.Monitor = new() { Enabled = enabled.IsOn, Fullscreen = fullscreen.IsOn, KeepOn = awake.IsOn, Clock = clock.IsOn, TileSize = size.SelectedIndex,
                RefreshSeconds = Math.Clamp((int)refresh.Value, 30, 3600), Rest = rest.IsOn, RestMinutes = Math.Clamp((int)minutes.Value, 1, 120), RestBrightness = (int)brightness.Value,
                Night = night.IsOn, NightFrom = Math.Clamp((int)nightFrom.Value, 0, 23), NightTo = Math.Clamp((int)nightTo.Value, 0, 23), RestItems = restItems.Where(p => p.Value.IsChecked == true).Select(p => p.Key).ToList(),
                Pages = pageOrder.Where(k => pages[k].IsChecked == true).ToList(), Only = selectedOnly, RestSensors = current.RestSensors };
            if (removePin.IsChecked == true) home.MonitorPin = null;
            if (pin.Password.Length > 0) home.MonitorPin = HashPin(pin.Password);
            Save(); ApplyMonitor(); if (home.Monitor.Enabled) selected = home.Monitor.Pages![0]; return Task.CompletedTask;
        });
    }
    async Task VoiceAsync()
    {
        var body = new StackPanel { Spacing = 14 };
        body.Children.Add(Text("Di' ad esempio “accendi la lampada”, “spegni tutto in cucina” oppure “esegui buonanotte”. Il riconoscimento è fornito da Windows."));
        var heard = new TextBox { Header = "Comando", PlaceholderText = "Accendi la lampada…", TextWrapping = TextWrapping.Wrap }; body.Children.Add(heard);
        var feedback = Text("", 12); body.Children.Add(feedback);
        var alternatives = new ComboBox { Header = "Scegli cosa intendevi", HorizontalAlignment = HorizontalAlignment.Stretch, Visibility = Visibility.Collapsed };
        body.Children.Add(AsyncButton("🎙  Ascolta", async () =>
        {
            try
            {
                using var recognizer = new SpeechRecognizer();
                var phrases = new List<string>();
                foreach (var name in AllEntries().Select(e => e.Name).Concat(home.AllRooms()))
                    foreach (var verb in new[] { "accendi ", "spegni ", "esegui ", "avvia ", "turn on ", "turn off ", "run " }) phrases.Add(verb + name);
                foreach (var room in home.AllRooms()) { phrases.Add("accendi tutto in " + room); phrases.Add("spegni tutto in " + room); }
                if (phrases.Count == 0) { feedback.Text = "Aggiungi prima un dispositivo."; return; }
                recognizer.Constraints.Add(new SpeechRecognitionListConstraint(phrases));
                if ((await recognizer.CompileConstraintsAsync()).Status != SpeechRecognitionResultStatus.Success) { feedback.Text = "Riconoscimento non disponibile per la lingua di Windows. Puoi scrivere il comando."; return; }
                feedback.Text = "Ti ascolto…";
                var result = await recognizer.RecognizeAsync();
                if (result.Status == SpeechRecognitionResultStatus.Success) { heard.Text = result.Text; feedback.Text = "Ho sentito: " + result.Text; }
                else feedback.Text = "Non ho riconosciuto il comando. Riprova o scrivilo qui.";
            }
            catch (Exception) { feedback.Text = "Verifica microfono e riconoscimento vocale nelle impostazioni di Windows. Puoi comunque scrivere il comando."; }
        }));
        body.Children.Add(alternatives);
        string? parsedText = null;
        await ShowFormAsync("Comanda con la voce", body, async () =>
        {
            var choices = VoiceParser.Parse(heard.Text, home);
            if (choices.Count == 0) throw new InvalidOperationException("Non trovo quel comando nella casa. Prova il nome completo.");
            VoiceChoice choice;
            if (choices[0].Certain) choice = choices[0];
            else if (parsedText == heard.Text && (alternatives.SelectedItem as ComboBoxItem)?.Tag is VoiceChoice selectedChoice) choice = selectedChoice;
            else
            {
                alternatives.Items.Clear(); foreach (var c in choices) alternatives.Items.Add(new ComboBoxItem { Content = c.Label, Tag = c });
                alternatives.Visibility = Visibility.Visible; alternatives.SelectedIndex = 0; parsedText = heard.Text;
                throw new InvalidOperationException("Conferma il dispositivo o la stanza scegliendo qui sopra, poi premi Esegui.");
            }
            foreach (var action in choice.Actions) await runtime.RunActionAsync(action, lifetime.Token);
        }, "Esegui");
    }
}
