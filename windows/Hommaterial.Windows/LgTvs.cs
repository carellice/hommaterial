using Hommaterial.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Hommaterial.Windows;

// LG TVs with webOS, driven on the home network. They stand among the remotes, after those of SwitchBot, so that the
// home, custom devices, schedules, automations and voice find them as any other remote.
public sealed partial class MainWindow
{
    readonly LgTvClient lgTv = new();
    List<LgTv> lgTvs = [];
    static readonly (string Key, string Label)[] LgKeys = [("HOME", "Home"), ("BACK", "Indietro"), ("ENTER", "OK"), ("EXIT", "Esci"),
        ("MENU", "Impostazioni"), ("INFO", "Info"), ("GUIDE", "Guida TV"), ("MUTE", "Muto"), ("RED", "Rosso"), ("GREEN", "Verde"),
        ("YELLOW", "Giallo"), ("BLUE", "Blu"), ("UP", "Su"), ("DOWN", "Giù"), ("LEFT", "Sinistra"), ("RIGHT", "Destra"),
        ("1", "1"), ("2", "2"), ("3", "3"), ("4", "4"), ("5", "5"), ("6", "6"), ("7", "7"), ("8", "8"), ("9", "9"), ("0", "0")];
    static readonly (string Code, string Label)[] LgMedia = [("play", "Riproduci"), ("pause", "Pausa"), ("stop", "Stop"), ("rewind", "Indietro veloce"), ("fastForward", "Avanti veloce")];

    LgTv? LgTvOf(string id) => lgTvs.FirstOrDefault(t => t.Id == id);
    void SyncLgRemotes() => home.Remotes = home.Remotes.Where(r => !r.Id.StartsWith(LgTv.Prefix)).Concat(demo ? [] : lgTvs.Select(t => t.AsRemote())).ToList();
    void SaveLgTvs(List<LgTv> tvs) { lgTvs = tvs; store.SaveLgTvs(tvs); SyncLgRemotes(); Save(); }

    Task SendRemoteAsync(string id, string command, string parameter = "default", bool custom = false, CancellationToken cancellation = default) =>
        LgTvOf(id) is { } tv ? lgTv.SendAsync(tv, command, parameter, cancellation) : switchBotClient.SendAsync(id, command, parameter, custom, cancellation);

    // What a key of a remote is called: for SwitchBot the name the user typed, for an LG TV the app or the key it stands for.
    string ButtonLabel(string remoteId, string command)
    {
        if (LgTvOf(remoteId) is not { } tv) return command;
        if (command.StartsWith("app:")) return tv.Apps?.GetValueOrDefault(command[4..]) ?? command[4..];
        if (command.StartsWith("key:")) return LgKeys.FirstOrDefault(k => k.Key == command[4..]).Label ?? command[4..];
        return LgMedia.FirstOrDefault(m => m.Code == command).Label ?? command switch { "setMute" => "Muto", _ => command };
    }

    void RenderLgTvSettings()
    {
        var panel = new StackPanel { Spacing = 12 }; panel.Children.Add(Text("TV LG", 20, true));
        panel.Children.Add(Text("I TV LG con webOS, comandati direttamente sulla rete di casa: niente account e niente cloud. Rispondono solo mentre il PC è sulla stessa rete."));
        if (demo) panel.Children.Add(Text("In demo i TV LG non sono disponibili.", 12));
        foreach (var tv in demo ? new List<LgTv>() : lgTvs)
        {
            var row = Row(); row.Children.Add(new TextBlock { Text = tv.Name + " · " + tv.Host, VerticalAlignment = VerticalAlignment.Center });
            row.Children.Add(AsyncButton("Prova", async () => { await lgTv.SendAsync(tv, "toast", "Hommaterial è collegato", lifetime.Token); Message("Messaggio mostrato su " + tv.Name + ".", InfoBarSeverity.Success); }));
            row.Children.Add(AsyncButton("Rinomina", () => RenameLgTvAsync(tv)));
            row.Children.Add(Button("Rimuovi", () => { SaveLgTvs(lgTvs.Where(t => t.Id != tv.Id).ToList()); RebuildNavigation(); Render(); }));
            panel.Children.Add(row);
        }
        if (!demo) panel.Children.Add(AsyncButton("Aggiungi un TV LG", AddLgTvAsync));
        page.Children.Add(Card(panel));
    }

    // The name the TV goes by in the app, for the home, custom devices and voice; the TV itself keeps its own.
    void RenameLgTv(string id, string name)
    {
        if (string.IsNullOrWhiteSpace(name)) throw new InvalidOperationException("Scrivi un nome per il TV.");
        SaveLgTvs(lgTvs.Select(t => t.Id == id ? t with { Name = name.Trim() } : t).ToList());
    }
    async Task RenameLgTvAsync(LgTv tv)
    {
        var body = new StackPanel { Spacing = 12, MinWidth = 320 };
        var name = new TextBox { Header = "Nome", Text = tv.Name };
        body.Children.Add(name);
        await ShowFormAsync("Rinomina " + tv.Name, body, () => { RenameLgTv(tv.Id, name.Text); RebuildNavigation(); return Task.CompletedTask; }, "Rinomina");
    }

    // Looks for TVs on the network, or takes one by its address, then waits while the TV asks the user on screen.
    async Task AddLgTvAsync()
    {
        var body = new StackPanel { Spacing = 12, MinWidth = 360 };
        body.Children.Add(Text("Accendi il TV e tieni il PC sulla stessa rete. Se il TV non risponde, cerca nelle sue impostazioni l'opzione che lascia comandarlo dal telefono (come «LG Connect Apps»).", 12));
        var found = new StackPanel { Spacing = 8 }; found.Children.Add(Text("Cerco i TV…"));
        var address = new TextBox { Header = "Indirizzo del TV, es. 192.168.1.20" };
        var status = Text("", 12);
        body.Children.Add(found); body.Children.Add(address); body.Children.Add(status);
        List<FoundTv> results = [];
        _ = SearchAsync();
        async Task SearchAsync()
        {
            try { results = await lgTv.DiscoverAsync(cancellation: lifetime.Token); }
            catch (Exception e) when (e is System.Net.Sockets.SocketException or OperationCanceledException) { results = []; }
            found.Children.Clear();
            var fresh = results.Where(r => lgTvs.All(t => t.Host != r.Host)).ToList();
            if (fresh.Count == 0) found.Children.Add(Text("Nessun TV ha risposto. Scrivi il suo indirizzo, che trovi nelle impostazioni di rete del TV."));
            foreach (var tv in fresh) found.Children.Add(Button((tv.Name ?? "TV LG") + " · " + tv.Host, () => address.Text = tv.Host));
        }
        await ShowFormAsync("Aggiungi un TV LG", body, async () =>
        {
            var host = address.Text.Trim();
            if (host.Length == 0) throw new InvalidOperationException("Scegli un TV trovato o scrivi il suo indirizzo.");
            status.Text = "Collegamento a " + host + "…";
            var tv = await lgTv.PairAsync(host, "Hommaterial è collegato", () => status.Text = "Accetta la richiesta sullo schermo del TV, con il suo telecomando.", lifetime.Token);
            // Paired again, it stays the same remote, with its buttons, rooms and custom devices.
            var before = lgTvs.FirstOrDefault(t => t.Host == host);
            var name = before?.Name ?? results.FirstOrDefault(r => r.Host == host)?.Name ?? tv.Name;
            tv = tv with { Id = before?.Id ?? tv.Id, Name = name, Apps = before?.Apps ?? [] };
            SaveLgTvs(lgTvs.Where(t => t.Id != tv.Id && t.Host != host).Append(tv).ToList());
            Message(tv.Name + " abbinato.", InfoBarSeverity.Success);
        }, "Abbina");
    }

    // The keys of the LG remote that matter most, on top of those every TV has, and its apps and inputs.
    void AddLgControls(StackPanel body, LgTv tv, Func<string, Task> press, Action refreshButtons)
    {
        var pad = new Grid { ColumnSpacing = 8, RowSpacing = 8, HorizontalAlignment = HorizontalAlignment.Center };
        for (var i = 0; i < 3; i++) { pad.ColumnDefinitions.Add(new() { Width = new GridLength(72) }); pad.RowDefinitions.Add(new() { Height = new GridLength(48) }); }
        void PadKey(string label, string key, int row, int column, bool accent = false)
        {
            var button = AsyncButton(label, () => press("key:" + key), accent);
            button.HorizontalAlignment = HorizontalAlignment.Stretch; button.VerticalAlignment = VerticalAlignment.Stretch;
            Grid.SetRow(button, row); Grid.SetColumn(button, column); pad.Children.Add(button);
        }
        PadKey("▲", "UP", 0, 1); PadKey("◀", "LEFT", 1, 0); PadKey("OK", "ENTER", 1, 1, true); PadKey("▶", "RIGHT", 1, 2); PadKey("▼", "DOWN", 2, 1);
        body.Children.Add(pad);
        var keys = Row(8);
        foreach (var (key, label) in new[] { ("BACK", "Indietro"), ("HOME", "Home"), ("MUTE", "Muto") }) keys.Children.Add(AsyncButton(label, () => press("key:" + key)));
        body.Children.Add(keys);
        var media = Row(8);
        foreach (var (code, label) in new[] { ("rewind", "⏪"), ("play", "▶"), ("pause", "⏸"), ("fastForward", "⏩") })
        {
            var button = AsyncButton(label, () => press(code)); ToolTipService.SetToolTip(button, LgMedia.First(m => m.Code == code).Label); media.Children.Add(button);
        }
        body.Children.Add(media);

        // The apps are asked to the TV as the panel opens; the last list known shows meanwhile.
        var apps = new ComboBox { Header = "App e ingressi", HorizontalAlignment = HorizontalAlignment.Stretch, PlaceholderText = "Chiedo al TV le sue app…" };
        void FillApps(Dictionary<string, string>? known)
        {
            apps.Items.Clear();
            foreach (var (id, title) in known ?? []) apps.Items.Add(new ComboBoxItem { Content = title, Tag = "app:" + id });
            foreach (var (key, label) in LgKeys) apps.Items.Add(new ComboBoxItem { Content = "Tasto " + label, Tag = "key:" + key });
            foreach (var (code, label) in LgMedia) apps.Items.Add(new ComboBoxItem { Content = label, Tag = code });
            if (apps.Items.Count > 0) apps.SelectedIndex = 0;
        }
        FillApps(tv.Apps);
        _ = RefreshAppsAsync();
        async Task RefreshAppsAsync()
        {
            try
            {
                var fresh = await lgTv.AppsAsync(tv, lifetime.Token);
                SaveLgTvs(lgTvs.Select(t => t.Id == tv.Id ? t with { Apps = fresh } : t).ToList());
                FillApps(fresh);
            }
            catch (LgTvException) { if (tv.Apps is not { Count: > 0 }) apps.PlaceholderText = "Il TV non risponde: accendilo per vedere le sue app."; }
        }
        var appActions = Row(8);
        appActions.Children.Add(AsyncButton("Apri", () => apps.SelectedItem is ComboBoxItem { Tag: string command } ? press(command) : Task.CompletedTask, true));
        appActions.Children.Add(Button("Aggiungi ai tuoi tasti", () =>
        {
            if (apps.SelectedItem is not ComboBoxItem { Tag: string command }) return;
            var buttons = home.RemoteButtons.GetValueOrDefault(tv.Id) ?? [];
            if (!buttons.Contains(command)) home.RemoteButtons[tv.Id] = [.. buttons, command];
            Save(); refreshButtons();
        }));
        body.Children.Add(apps); body.Children.Add(appActions);

        // Renamed in place: the panel is a dialog already, and another cannot open over it.
        var rename = Row(8);
        var name = new TextBox { Header = "Nome del TV", Text = tv.Name, MinWidth = 220 };
        var save = Button("Rinomina", () => { RenameLgTv(tv.Id, name.Text); Message("Nome del TV aggiornato.", InfoBarSeverity.Success); });
        save.VerticalAlignment = VerticalAlignment.Bottom;
        rename.Children.Add(name); rename.Children.Add(save);
        body.Children.Add(rename);
    }
}
