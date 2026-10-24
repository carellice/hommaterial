using System.Text.Json.Nodes;
using Hommaterial.Core;

static class FeatureTests
{
    public static async Task Run(Action<bool, string> check)
    {
        var imported = BackupCodec.Parse("""
        {"app":"hommaterial","version":1,"favorites":["a","macro:m"],"hidden":["b"],"rooms":{"a":"Studio","macro:m":"Studio"},"roomList":["Studio"],
        "groups":[{"id":"g","name":"Luci","devices":["a","b"]}],"macros":[{"id":"m","name":"Postazione","condition":"a","steps":[{"type":"power","target":"b","value":"on","custom":false}],"otherwise":[{"type":"remote","target":"r","value":"Power","custom":true}]}],
        "order":{"room:Studio":["macro:m","a"]},"shortcuts":["device:a","group:g"],"widget":{"columns":3,"transparent":true},"tiles":["a",null,"b"],
        "alerts":{"a":{"above":28.5,"below":16}},"autoUpdate":false,"haptics":true,"hapticLevel":63,"theme":2,"layout":1,"language":"en",
        "remoteButtons":{"r":["Power","HDMI 1"]},"hiddenRemotes":["hidden-r"],"switchBot":{"token":"token","secret":"secret"},
        "monitor":{"enabled":true,"pages":["room:Studio","favorites"],"only":{"room:Studio":["a","macro:m"]},"restMinutes":4,"restItems":["clock","sensors"],"night":true,"nightFrom":22,"nightTo":6},
        "schedules":[{"id":"s","action":{"type":"macro","target":"m","value":"","custom":false},"label":"Postazione","at":1792567800000,"hour":20,"minute":10,"days":[2,4,6],"enabled":true}],
        "automations":[{"id":"auto","device":"a","trigger":"temperatureAbove","threshold":25,"action":{"type":"power","target":"b","value":"off"},"title":"Caldo","label":"Spegni b","enabled":true}],
        "lgTvs":[{"id":"lg:1","name":"Sala","host":"192.168.1.20","key":"k","pin":"p","macs":["a8:23:fe:01:02:03"],"apps":{"netflix":"Netflix"}}],
        "futureField":{"keep":[1,2,3]},"monitorPin":"never-export","refreshToken":"never-export"}
        """);
        var original = imported.Choices;
        check(original.Macros[0].Otherwise![0].Custom && original.Schedules[0].Days.SetEquals([2, 4, 6]) && imported.SwitchBot?.Secret == "secret", "backup Android/web: macro condizionali, programmi, credenziali SwitchBot");
        check(imported.LgTvs is [{ Key: "k", Macs: ["a8:23:fe:01:02:03"] }] && !original.BackupExtras.ContainsKey("lgTvs"), "TV LG del backup letti come dati, non come campi sconosciuti");
        var exported = JsonNode.Parse(BackupCodec.Export(original, imported.SwitchBot, imported.LgTvs))!;
        check(exported["widget"]?["columns"]?.GetValue<int>() == 3 && exported["tiles"]?[1] is null && exported["hapticLevel"]?.GetValue<int>() == 63 && exported["futureField"]?["keep"]?.AsArray().Count == 3, "round trip conserva widget, tile Android, vibrazione e campi futuri");
        check(exported["theme"]?.GetValue<int>() == 2 && exported["app"]?.ToString() == "hommaterial" && exported["monitorPin"] is null && exported["refreshToken"] is null, "formato v1 comune e nessun PIN o token Amazon nel backup");
        check(exported["lgTvs"]?[0]?["key"]?.ToString() == "k" && exported["lgTvs"]?[0]?["apps"]?["netflix"]?.ToString() == "Netflix", "i TV LG abbinati su Android tornano intatti dal backup di Windows");
        check(BackupCodec.Parse(exported.ToJsonString()).Choices.Automations[0].Action.Value == "off", "esportazione Windows riletta senza perdere le automazioni");
        var account = DemoHome.Create(); account.MonitorPin = "local-pin"; account.Marketplace = "amazon.de";
        BackupCodec.Apply(account, original, false);
        check(account.Devices.Count == 7 && account.Marketplace == "amazon.de" && account.MonitorPin == "local-pin" && !account.Monitor.Enabled, "ripristino mantiene cache account, marketplace e PIN; monitor solo su scelta");
        var selective = DemoHome.Create(); selective.Favorites = ["local"]; selective.Tripped = ["already-tripped"];
        selective.TileSpans["local"] = 2;
        var schedulesBefore = selective.Schedules;
        BackupCodec.Apply(selective, original, false, new HashSet<string> { "groups", "macros" });
        check(selective.Groups[0].Id == "g" && selective.Macros[0].Id == "m" && selective.Favorites.SetEquals(["local"]), "importa gruppi e dispositivi personalizzati senza cambiare i preferiti");
        check(ReferenceEquals(selective.Schedules, schedulesBefore) && selective.Tripped.Contains("already-tripped") && selective.TileSpans["local"] == 2, "categorie escluse conservano programmi, automazioni e larghezze locali");
        var widths = BackupCodec.Parse(BackupCodec.Export(new HomeData { TileSpans = new() { ["group:g"] = 2, ["a"] = 1 } })).Choices;
        check(widths.TileSpans["group:g"] == 2 && TileLayout.Span(widths.TileSpans, "group:g", 1) == 1 && TileLayout.Span(widths.TileSpans, "missing", 3) == 1, "larghezze compatibili nel backup e limitate alle colonne disponibili");
        check(TileLayout.Arrange([1, 2, 2, 1], 3).SequenceEqual(new[] { (0, 0, 1), (0, 1, 2), (1, 0, 2), (1, 2, 1) }), "griglia mista mantiene ordine e righe senza sovrapposizioni");
        check(TileLayout.Arrange([2, 1], 1).SequenceEqual(new[] { (0, 0, 1), (1, 0, 1) }), "riquadri larghi si adattano a una sola colonna");
        foreach (var bad in new[] { "{}", "{broken", "{\"app\":\"other\",\"version\":1}", "{\"app\":\"hommaterial\",\"version\":2}", "{\"app\":\"hommaterial\",\"version\":1,\"schedules\":[null]}", "{\"app\":\"hommaterial\",\"version\":1,\"macros\":[{\"id\":\"m\",\"name\":\"Bad\",\"steps\":[{\"type\":\"macro\",\"target\":\"m\"}]}]}" })
        {
            var refused = false; try { BackupCodec.Parse(bad); } catch (InvalidOperationException) { refused = true; }
            check(refused, "backup corrotto/incompatibile rifiutato prima di modificare dati");
        }
        var zone = TimeZoneInfo.FindSystemTimeZoneById(OperatingSystem.IsWindows() ? "W. Europe Standard Time" : "Europe/Rome");
        long At(int day, int hour, int minute) => new DateTimeOffset(2026, 10, day, hour, minute, 0, zone.GetUtcOffset(new DateTime(2026, 10, day, hour, minute, 0))).ToUnixTimeMilliseconds();
        check(ScheduleClock.Next(16, 0, [], At(14, 9, 30), zone) == At(14, 16, 0), "programmi: più tardi oggi");
        check(ScheduleClock.Next(8, 0, [4], At(14, 9, 30), zone) == At(21, 8, 0), "programmi: giorni Java Calendar uguali ad Android e web");
        var dst = ScheduleClock.Next(2, 30, [], At(24, 18, 0), zone);
        check(TimeZoneInfo.ConvertTime(DateTimeOffset.FromUnixTimeMilliseconds(dst), zone).Hour == 2, "programmi: cambio dell'ora autunnale valido");
        var home = DemoHome.Create(); home.History.Clear(); home.Automations.Clear(); var actions = new List<HomeAction>(); var notifications = new List<string>();
        var failNetwork = false;
        var runtime = new HomeRuntime(() => home, (action, _) => { if (failNetwork) throw new HttpRequestException("offline"); actions.Add(action); return Task.CompletedTask; }, () => { }, (title, _) => notifications.Add(title));
        var rule = new AutomationRule { Id = "hot", Device = "demo-3", Trigger = "temperatureAbove", Threshold = 25, Action = new("power", "demo-1", "off") };
        home.Automations.Add(rule);
        var now = At(20, 12, 0);
        async Task Receive(double temperature) => await runtime.ReceiveAsync(new Dictionary<string, DeviceState> { ["demo-3"] = new(Temperature: temperature) }, now);
        await Receive(25.1); await Receive(25.2); await Receive(24.9); await Receive(25.3);
        check(actions.Count == 1, "automazione: un solo comando mentre oscilla vicino alla soglia");
        await Receive(24.6); await Receive(25.1);
        check(actions.Count == 2, "automazione: riarmo dopo isteresi di 0,3 °C");
        runtime.Prime(rule); // Current demo state is 22.4, below threshold.
        home.States["demo-3"] = new(Temperature: 27); runtime.Prime(rule); await Receive(27);
        check(actions.Count == 2, "automazione creata/riattivata in condizione già vera attende un nuovo attraversamento");
        var unknown = new AutomationRule { Trigger = "on" };
        check(unknown.Holds(new()) is null && rule.Holds(new(Temperature: 40, Reachable: false)) is null, "automazioni ignorano stati sconosciuti e irraggiungibili");
        home.Schedules.Add(new() { Id = "once", At = now, Action = new("power", "demo-1", "on"), Label = "Una volta" });
        await runtime.TickAsync(now); await runtime.TickAsync(now);
        check(actions.Count == 3 && home.Schedules.Count == 0, "programma una tantum eseguito una volta e rimosso");
        var weekly = new HomeSchedule { Id = "weekly", At = now, Hour = 12, Minute = 0, Days = [3], Action = new("power", "demo-1", "on") };
        home.Schedules.Add(weekly); await runtime.TickAsync(now);
        check(weekly.At > now && home.Schedules.Count == 1, "programma ripetuto spostato alla prossima occorrenza");
        home.Schedules.Clear(); var before = actions.Count;
        home.Schedules.Add(new() { At = now - 11 * 60_000, Action = new("power", "demo-1", "on") }); await runtime.TickAsync(now);
        check(actions.Count == before && home.Schedules.Count == 0 && notifications.Contains("Azione scaduta"), "ripresa del PC: nessun comando scaduto da oltre 10 minuti");
        failNetwork = true; var retry = new HomeSchedule { At = now, Action = new("power", "demo-1", "off") }; home.Schedules.Add(retry);
        await runtime.TickAsync(now); check(home.Schedules.Count == 1 && retry.RetryAt == now + 60_000, "programma offline ritenta dopo un minuto");
        failNetwork = false; await runtime.TickAsync(now + 60_000);
        check(home.Schedules.Count == 0 && actions.Count == before + 1, "programma ripreso dalla rete eseguito e completato");
        home.Alerts["demo-3"] = new(Above: 25); notifications.Clear(); home.Automations.Clear();
        await Receive(26); await Receive(27); await Receive(24.9); await Receive(26);
        check(notifications.Count == 1, "notifiche sensori con isteresi senza duplicati");
        check(home.History["demo-3"].Count == 1, "storico: campioni distanziati di almeno dieci minuti");
        var voice = VoiceParser.Parse("accendi la lampada divano", home);
        check(voice[0].Certain && voice[0].Actions[0].Target == "demo-1", "voce: nome esatto del dispositivo");
        check(!VoiceParser.Parse("spegni tutto in cucina", home)[0].Certain, "voce: stanza intera richiede conferma");
        check(VoiceParser.Parse("turn off the lampada divano please", home)[0].Actions[0].Value == "off", "voce: comandi inglesi");
        check(SwitchBotClient.Sign("token-abc", "secret-key", "1700000000000", "nonce-1") == "BDFQNBEJWKVP6RHVWLYOXD4M48GPWYEFZM1IASZBWIY=", "firma SwitchBot identica al vettore Android/web");
    }
}
