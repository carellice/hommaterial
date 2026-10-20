using System.Text.Json;
using Hommaterial.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage;
using Windows.Storage.Pickers;

namespace Hommaterial.Windows;

public sealed partial class MainWindow
{
    void RenderNativeSettings()
    {
        page.Children.Add(Text("Impostazioni", 32, true));
        var account = new StackPanel { Spacing = 12 }; account.Children.Add(Text("Account", 20, true));
        account.Children.Add(Text(demo ? "Casa dimostrativa. Le modifiche restano in memoria." : client.IsLoggedIn ? "Amazon · " + home.Marketplace : "Collega il tuo account per ritrovare la casa."));
        account.Children.Add(AsyncButton(demo ? "Esci dalla demo e collega Amazon" : "Collega Amazon", demo ? LeaveDemoAndLoginAsync : LoginAsync));
        if (!demo && client.IsLoggedIn) account.Children.Add(AsyncButton("Esci e cancella i dati Amazon locali", LogoutAsync));
        if (!demo) account.Children.Add(Button("Esplora la demo", StartDemo)); page.Children.Add(Card(account));

        var backup = new StackPanel { Spacing = 12 }; backup.Children.Add(Text("Backup e trasferimento", 20, true));
        backup.Children.Add(Text("Lo stesso file JSON si importa su Android, web e Windows. Include stanze, gruppi, preferiti, personalizzati, automazioni, programmi e impostazioni. L'account Amazon e il PIN restano su questo dispositivo."));
        var keys = new CheckBox { Content = "Includi token e chiave SwitchBot nel file", IsChecked = false };
        backup.Children.Add(keys);
        backup.Children.Add(Text("Se incluse, le chiavi SwitchBot sono leggibili nel backup, come nelle altre versioni. Conserva il file in un posto di cui ti fidi.", 12));
        var backupActions = Row(); backupActions.Children.Add(AsyncButton("Esporta backup…", () => ExportBackupAsync(keys.IsChecked == true)));
        backupActions.Children.Add(AsyncButton("Importa backup…", ImportBackupAsync)); backup.Children.Add(backupActions); page.Children.Add(Card(backup));

        var appearance = new StackPanel { Spacing = 12 }; appearance.Children.Add(Text("Aspetto e interazione", 20, true));
        var theme = new ComboBox { Header = "Tema", Items = { "Come Windows", "Chiaro", "Scuro" }, SelectedIndex = home.Theme == "Light" ? 1 : home.Theme == "Dark" ? 2 : 0 };
        theme.SelectionChanged += (_, _) => { home.Theme = theme.SelectedIndex == 1 ? "Light" : theme.SelectedIndex == 2 ? "Dark" : "Default"; ApplyTheme(); Save(); };
        var layout = new ComboBox { Header = "Dispositivi", Items = { "Griglia", "Elenco" }, SelectedIndex = home.Layout };
        layout.SelectionChanged += (_, _) => { home.Layout = layout.SelectedIndex; Save(); };
        appearance.Children.Add(theme); appearance.Children.Add(layout);
        appearance.Children.Add(Text("Ctrl+K: ricerca in tutta la casa · Invio: azione sul primo risultato · F5: aggiorna · Esc: svuota la ricerca · Maiusc+F10: dettagli della card selezionata."));
        appearance.Children.Add(Text("Le animazioni seguono la preferenza di accessibilità di Windows.", 12)); page.Children.Add(Card(appearance));

        var notificationsPanel = new StackPanel { Spacing = 12 }; notificationsPanel.Children.Add(Text("Notifiche e background", 20, true));
        var enabled = new ToggleSwitch { Header = "Notifiche per timer, programmi, automazioni e sensori", IsOn = home.Notifications };
        enabled.Toggled += (_, _) => { home.Notifications = enabled.IsOn; Save(); };
        notificationsPanel.Children.Add(enabled);
        var background = new ToggleSwitch { Header = "Resta nell'area di notifica quando chiudo la finestra", IsOn = home.RunInBackground };
        background.Toggled += (_, _) => { home.RunInBackground = background.IsOn; Save(); };
        notificationsPanel.Children.Add(background); notificationsPanel.Children.Add(Text(notifications.Status, 12));
        notificationsPanel.Children.Add(Button("Prova una notifica", () => { if (home.Notifications) notifications.Show("Hommaterial", "Le notifiche della tua casa sono pronte."); else Message("Attiva prima le notifiche."); }));
        notificationsPanel.Children.Add(Text("Usa l'icona vicino all'orologio per riaprire l'app o uscire completamente. Il PC deve essere acceso e connesso. Senza rete, timer e programmi riprovano per un massimo di 10 minuti. Le automazioni seguono la lettura dei sensori, non sono allarmi in tempo reale.", 12));
        page.Children.Add(Card(notificationsPanel));

        var bot = new StackPanel { Spacing = 12 }; bot.Children.Add(Text("Telecomandi SwitchBot", 20, true));
        bot.Children.Add(Text((demo ? demoKeys : store.ReadSwitchBot()) is null ? "Collega l'hub con token e chiave segreta dell'app SwitchBot." : "Account collegato · " + home.Remotes.Count + " telecomandi"));
        bot.Children.Add(AsyncButton("Collega o modifica credenziali", ConnectSwitchBotAsync));
        bot.Children.Add(AsyncButton("Aggiorna telecomandi", RefreshRemotesAsync));
        bot.Children.Add(Button("Scollega SwitchBot", () => { if (demo) demoKeys = null; else store.SaveSwitchBot(null); home.Remotes.Clear(); Save(); Render(); }));
        page.Children.Add(Card(bot));

        var monitor = new StackPanel { Spacing = 12 }; monitor.Children.Add(Text("Modalità monitor", 20, true));
        monitor.Children.Add(Text("Un pannello dedicato alla casa, con pagine, schermo acceso, riposo e PIN facoltativo."));
        monitor.Children.Add(AsyncButton("Configura il monitor…", MonitorSettingsAsync)); page.Children.Add(Card(monitor));

        var hidden = new StackPanel { Spacing = 10 }; hidden.Children.Add(Text("Dispositivi e cronologia", 20, true));
        var show = new ToggleSwitch { Header = "Mostra anche i dispositivi nascosti", IsOn = showHidden };
        show.Toggled += (_, _) => { showHidden = show.IsOn; };
        hidden.Children.Add(show);
        foreach (var d in home.Devices.Where(d => home.Hidden.Contains(d.ApplianceId))) hidden.Children.Add(Button("Mostra " + d.Name, () => { home.Hidden.Remove(d.ApplianceId); Save(); Render(); }));
        foreach (var r in home.Remotes.Where(r => home.HiddenRemotes.Contains(r.Id))) hidden.Children.Add(Button("Mostra " + r.Name, () => { home.HiddenRemotes.Remove(r.Id); Save(); Render(); }));
        hidden.Children.Add(Button("Cancella cronologia dei sensori", () => { home.History.Clear(); Save(); Message("Cronologia cancellata."); }));
        hidden.Children.Add(AsyncButton("Riordina stanze e card", () => { selected = "rooms"; RebuildNavigation(); Render(); return Task.CompletedTask; }));
        page.Children.Add(Card(hidden));
        page.Children.Add(Card(new StackPanel { Spacing = 8, Children = { Text("Hommaterial per Windows · " + AppVersion, 20, true),
            Text("C# · WinUI 3 · Open source · MIT"), Text("Progetto indipendente, non affiliato ad Amazon. Le API Alexa sono non ufficiali."),
            Text("Controllo aggiornamenti su GitHub all'avvio e ogni 6 ore. Download e installazione partono solo quando lo scegli. Usa una cartella personale scrivibile, conservando tutti i file dell'app."),
            AsyncButton("Controlla aggiornamenti", () => CheckUpdatesAsync(true), true),
            AsyncButton("Progetto e versioni disponibili", () => { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("https://github.com/carellice/hommaterial") { UseShellExecute = true }); return Task.CompletedTask; }) } }));
    }
    async Task ExportBackupAsync(bool includeKeys)
    {
        if (dialogOpen || busy || pending.Count > 0) return;
        try
        {
            var picker = new FileSavePicker { SuggestedFileName = "hommaterial-backup", SuggestedStartLocation = PickerLocationId.DocumentsLibrary };
            picker.FileTypeChoices.Add("Backup Hommaterial", new List<string> { ".json" });
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
            var file = await picker.PickSaveFileAsync(); if (file is null) return;
            await FileIO.WriteTextAsync(file, BackupCodec.Export(home, includeKeys ? demo ? demoKeys : store.ReadSwitchBot() : null));
            Message("Backup esportato. Compatibile con Android e web.", InfoBarSeverity.Success);
        }
        catch (Exception e) { ShowError(e); }
    }
    async Task ImportBackupAsync()
    {
        if (dialogOpen || busy || pending.Count > 0) return;
        try
        {
            var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary };
            picker.FileTypeFilter.Add(".json");
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
            var file = await picker.PickSingleFileAsync(); if (file is null) return;
            if ((await file.GetBasicPropertiesAsync()).Size > 5_000_000) throw new InvalidOperationException("Backup troppo grande (massimo 5 MB).");
            var imported = BackupCodec.Parse(await FileIO.ReadTextAsync(file));
            var choices = imported.Choices;
            var body = new StackPanel { Spacing = 14 };
            body.Children.Add(Text($"{choices.Favorites.Count} preferiti · {choices.RoomList.Count} stanze personalizzate · {choices.Groups.Count} gruppi · {choices.Macros.Count} personalizzati · {choices.Automations.Count} automazioni · {choices.Schedules.Count} programmi"));
            body.Children.Add(Text("L'importazione sostituisce le tue scelte locali. Il collegamento Amazon e la cronologia rimangono su questo PC."));
            var monitor = new CheckBox { Content = "Ripristina anche la modalità monitor", IsChecked = false };
            var active = new CheckBox { Content = "Attiva automazioni e programmi importati", IsChecked = false };
            var keys = new CheckBox { Content = "Importa anche le credenziali SwitchBot", IsChecked = imported.SwitchBot is not null, Visibility = imported.SwitchBot is null ? Visibility.Collapsed : Visibility.Visible };
            body.Children.Add(monitor); body.Children.Add(active); body.Children.Add(keys);
            await ShowFormAsync("Ripristina backup", body, () =>
            {
                if (active.IsChecked != true) { foreach (var a in choices.Automations) a.Enabled = false; foreach (var s in choices.Schedules) s.Enabled = false; }
                BackupCodec.Apply(home, choices, monitor.IsChecked == true);
                foreach (var rule in home.Automations) runtime.Prime(rule);
                if (keys.IsChecked == true && imported.SwitchBot is not null) { if (demo) demoKeys = imported.SwitchBot; else store.SaveSwitchBot(imported.SwitchBot); }
                Save(); ApplyTheme(); ApplyMonitor(); Message("Backup importato.", InfoBarSeverity.Success); return Task.CompletedTask;
            }, "Ripristina");
            if (imported.SwitchBot is not null && (demo ? demoKeys : store.ReadSwitchBot()) is not null) await RefreshRemotesAsync();
        }
        catch (Exception e) { ShowError(e); }
    }
    async Task ConnectSwitchBotAsync()
    {
        var body = new StackPanel { Spacing = 14 };
        body.Children.Add(Text("Nell'app SwitchBot: Profilo → Preferenze → Opzioni sviluppatore. Le credenziali restano cifrate su questo PC."));
        var token = new PasswordBox { Header = "Token" }; var secret = new PasswordBox { Header = "Chiave segreta" };
        body.Children.Add(token); body.Children.Add(secret);
        await ShowFormAsync("Collega SwitchBot", body, async () =>
        {
            if (string.IsNullOrWhiteSpace(token.Password) || string.IsNullOrWhiteSpace(secret.Password)) throw new InvalidOperationException("Inserisci token e chiave segreta.");
            var keys = new SwitchBotKeys(token.Password.Trim(), secret.Password.Trim());
            if (demo) { demoKeys = keys; return; }
            using var verify = new SwitchBotClient(() => keys);
            var remotes = await verify.RemotesAsync();
            store.SaveSwitchBot(keys); home.Remotes = remotes; Save();
        }, "Collega");
    }
    async Task RefreshRemotesAsync()
    {
        if (demo) { Message("In demo i telecomandi sono simulati."); return; }
        if (store.ReadSwitchBot() is null) return;
        home.Remotes = await switchBotClient.RemotesAsync(); Save(); RebuildNavigation(); Render();
    }
    async Task RemoteDetailsAsync(RemoteDevice remote)
    {
        var body = new StackPanel { Spacing = 12 }; var feedback = Text("I comandi a infrarossi non restituiscono lo stato dell'apparecchio.", 12); body.Children.Add(feedback);
        async Task Press(string command, string parameter = "default", bool custom = false)
        {
            try { if (!demo) await switchBotClient.SendAsync(remote.Id, command, parameter, custom, lifetime.Token); feedback.Text = "Comando inviato: " + command; }
            catch (Exception e) { feedback.Text = e.Message; ShowError(e); }
        }
        foreach (var (code, label) in StandardCommands(remote)) body.Children.Add(AsyncButton(label, () => Press(code)));
        if (remote.Kind == "AC")
        {
            var current = home.AcSettings.GetValueOrDefault(remote.Id) ?? new();
            var temperature = new NumberBox { Header = "Temperatura", Minimum = 16, Maximum = 30, Value = current.Temperature, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact };
            var mode = new ComboBox { Header = "Modalità", Items = { "Automatico", "Freddo", "Deumidifica", "Ventola", "Caldo" }, SelectedIndex = Math.Clamp(current.Mode - 1, 0, 4) };
            var fan = new ComboBox { Header = "Ventola", Items = { "Automatica", "Bassa", "Media", "Alta" }, SelectedIndex = Math.Clamp(current.Fan - 1, 0, 3) };
            body.Children.Add(temperature); body.Children.Add(mode); body.Children.Add(fan);
            body.Children.Add(AsyncButton("Applica e accendi", async () =>
            {
                if (!double.IsFinite(temperature.Value)) return;
                var setting = new AcSetting((int)temperature.Value, mode.SelectedIndex + 1, fan.SelectedIndex + 1);
                await Press("setAll", $"{setting.Temperature},{setting.Mode},{setting.Fan},on");
                home.AcSettings[remote.Id] = setting; Save();
            }, true));
        }
        var customPanel = new StackPanel { Spacing = 8 }; body.Children.Add(customPanel);
        void FillCustom()
        {
            customPanel.Children.Clear();
            foreach (var name in home.RemoteButtons.GetValueOrDefault(remote.Id) ?? []) customPanel.Children.Add(AsyncButton(name, () => Press(name, custom: true)));
        }
        FillCustom();
        var names = new TextBox { Header = "Tasti personalizzati (uno per riga)", AcceptsReturn = true, Text = string.Join("\n", home.RemoteButtons.GetValueOrDefault(remote.Id) ?? []) };
        body.Children.Add(names); body.Children.Add(Button("Salva i tasti", () =>
        { home.RemoteButtons[remote.Id] = names.Text.Split('\n').Select(n => n.Trim()).Where(n => n.Length > 0).Distinct().ToList(); Save(); FillCustom(); }));
        AddFavoriteControl(body, "remote:" + remote.Id); AddPlaceControl(body, "remote:" + remote.Id);
        AddActionTimer(body, "Timer del telecomando", selection => { AddNativeTimer(new("remote", remote.Id, selection.On ? "turnOn" : "turnOff"), remote.Name, selection.Minutes); return Task.CompletedTask; });
        var customTimer = new ComboBox { Header = "Oppure esegui un tasto dopo il timer" };
        foreach (var name in home.RemoteButtons.GetValueOrDefault(remote.Id) ?? []) customTimer.Items.Add(name);
        if (customTimer.Items.Count > 0)
        {
            customTimer.SelectedIndex = 0; body.Children.Add(customTimer);
            AddActionTimer(body, "Timer tasto personalizzato", selection => { AddNativeTimer(new("remote", remote.Id, customTimer.SelectedItem.ToString()!, true), remote.Name, selection.Minutes); return Task.CompletedTask; }, false);
        }
        body.Children.Add(Button("Nascondi questo telecomando", () => { home.HiddenRemotes.Add(remote.Id); Save(); Message("Telecomando nascosto."); }));
        await ShowPanelAsync(remote.Name, body);
    }
}
