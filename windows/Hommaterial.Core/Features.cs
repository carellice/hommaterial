using System.Text.Json;
using System.Text.Json.Nodes;

namespace Hommaterial.Core;

public sealed record HomeGroup(string Id, string Name, List<string> Devices);
public sealed record HomeAction(string Type, string Target = "", string Value = "", bool Custom = false);
public sealed record HomeMacro(string Id, string Name, string? Condition = null, List<HomeAction>? Steps = null, List<HomeAction>? Otherwise = null);
public sealed record RemoteDevice(string Id, string Name, string Type)
{
    public string Kind => Type.Replace("DIY ", "").ToLowerInvariant() switch
    { "tv" or "iptv" or "streamer" or "set top box" or "lg webos" => "TV", "air conditioner" => "AC", "others" => "CUSTOM", _ => "OTHER" };
}
public sealed record AcSetting(int Temperature = 24, int Mode = 2, int Fan = 1);
public sealed record SwitchBotKeys(string Token, string Secret);
public sealed record SensorAlert(double? Above = null, double? Below = null);
public sealed record SensorReading(long At, double Temperature, int? Humidity);
public sealed class HomeSchedule
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public HomeAction Action { get; set; } = new("power");
    public string Label { get; set; } = "";
    public long At { get; set; }
    public int Hour { get; set; }
    public int Minute { get; set; }
    // Java Calendar convention: Sunday=1, Monday=2, ... Saturday=7.
    public HashSet<int> Days { get; set; } = [];
    public bool Enabled { get; set; } = true;
    public bool IsTimer { get; set; }
    public long? RetryAt { get; set; }
}
public sealed class AutomationRule
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string Device { get; set; } = "";
    public string Trigger { get; set; } = "on";
    public double Threshold { get; set; }
    public HomeAction Action { get; set; } = new("power");
    public string Title { get; set; } = "";
    public string Label { get; set; } = "";
    public bool Enabled { get; set; } = true;
    public double? Distance(DeviceState state)
    {
        if (!state.Reachable) return null;
        return Trigger switch {
            "temperatureAbove" => state.Temperature - Threshold, "temperatureBelow" => Threshold - state.Temperature,
            "humidityAbove" => state.Humidity - Threshold, "humidityBelow" => Threshold - state.Humidity,
            "on" => state.Power is bool on ? on ? 1 : -1 : null,
            "off" => state.Power is bool off ? off ? -1 : 1 : null, _ => null };
    }
    public bool? Holds(DeviceState state) => Distance(state) is double d && double.IsFinite(d) ? d > 0 : null;
    public bool? Cleared(DeviceState state) => Distance(state) is double d && double.IsFinite(d)
        ? d <= -(Trigger.StartsWith("temperature") ? .3 : Trigger.StartsWith("humidity") ? 2 : 0) : null;
}
public sealed class MonitorSettings
{
    public bool Enabled { get; set; }
    public List<string>? Pages { get; set; }
    public Dictionary<string, List<string>> Only { get; set; } = [];
    public int TileSize { get; set; } = 1;
    public bool Clock { get; set; } = true;
    public bool KeepOn { get; set; } = true;
    public bool Fullscreen { get; set; } = true;
    public int RefreshSeconds { get; set; } = 60;
    public bool Rest { get; set; } = true;
    public int RestMinutes { get; set; } = 2;
    public List<string> RestItems { get; set; } = ["clock", "date", "sensors", "timers", "power"];
    public List<string>? RestSensors { get; set; }
    public int RestBrightness { get; set; } = 15;
    public bool Night { get; set; }
    public int NightFrom { get; set; } = 23;
    public int NightTo { get; set; } = 7;
}

public static class ScheduleClock
{
    public static long Next(int hour, int minute, IEnumerable<int> days, long after, TimeZoneInfo? zone = null)
    {
        if (hour is < 0 or > 23 || minute is < 0 or > 59) throw new ArgumentOutOfRangeException(nameof(hour));
        var chosen = days.ToHashSet();
        if (chosen.Any(d => d is < 1 or > 7)) throw new ArgumentException("Giorni non validi.");
        zone ??= TimeZoneInfo.Local;
        var now = DateTimeOffset.FromUnixTimeMilliseconds(after);
        var local = TimeZoneInfo.ConvertTime(now, zone);
        for (var i = 0; i <= 8; i++)
        {
            var candidate = DateTime.SpecifyKind(local.Date.AddDays(i).AddHours(hour).AddMinutes(minute), DateTimeKind.Unspecified);
            if (chosen.Count > 0 && !chosen.Contains((int)candidate.DayOfWeek + 1)) continue;
            // On a DST gap, run at the next existing local minute, never with an invalid offset.
            while (zone.IsInvalidTime(candidate)) candidate = candidate.AddMinutes(1);
            var offsets = zone.IsAmbiguousTime(candidate) ? zone.GetAmbiguousTimeOffsets(candidate) : [zone.GetUtcOffset(candidate)];
            foreach (var offset in offsets.OrderDescending())
            {
                var result = new DateTimeOffset(candidate, offset).ToUnixTimeMilliseconds();
                if (result > after) return result;
            }
        }
        throw new InvalidOperationException("Impossibile calcolare la prossima esecuzione.");
    }
}

public sealed record ImportedBackup(HomeData Choices, SwitchBotKeys? SwitchBot, List<LgTv>? LgTvs = null);
public static class BackupCodec
{
    public static readonly JsonSerializerOptions PortableJson = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true, WriteIndented = true };
    static JsonNode? Node<T>(T data) => JsonSerializer.SerializeToNode(data, PortableJson);
    public static string Export(HomeData home, SwitchBotKeys? keys = null, List<LgTv>? lgTvs = null)
    {
        var root = (JsonObject)home.BackupExtras.DeepClone();
        root["app"] = "hommaterial"; root["version"] = 1;
        root["favorites"] = Node(home.Favorites); root["hidden"] = Node(home.Hidden);
        root["rooms"] = Node(home.Rooms); root["roomList"] = Node(home.RoomList);
        root["groups"] = Node(home.Groups); root["macros"] = Node(home.Macros); root["order"] = Node(home.Order);
        root["alerts"] = Node(home.Alerts); root["remoteButtons"] = Node(home.RemoteButtons); root["hiddenRemotes"] = Node(home.HiddenRemotes);
        root["theme"] = home.Theme == "Light" ? 1 : home.Theme == "Dark" ? 2 : 0;
        root["layout"] = home.Layout; root["language"] = home.Language;
        root["monitor"] = Node(home.Monitor);
        root["schedules"] = Node(home.Schedules.Where(s => !s.IsTimer).Select(s => new { s.Id, s.Action, s.Label, s.At, s.Hour, s.Minute, s.Days, s.Enabled }));
        root["automations"] = Node(home.Automations);
        root["shortcuts"] ??= null; root["widget"] ??= new JsonObject(); root["tiles"] ??= new JsonArray(Enumerable.Repeat<JsonNode?>(null, 10).ToArray());
        root["autoUpdate"] ??= true;
        // Account credentials and PINs never belong in a portable backup. SwitchBot keys are optional, as on Android/web.
        foreach (var name in new[] { "refreshToken", "cookie", "csrf", "monitorPin", "pin", "password", "session", "switchBot" }) root.Remove(name);
        if (root["monitor"] is JsonObject monitor) { monitor.Remove("pin"); monitor.Remove("monitorPin"); }
        if (keys is not null) root["switchBot"] = Node(keys);
        // The LG TVs with their keys, which serve only on the home network, as Android does.
        if (lgTvs is { Count: > 0 }) root["lgTvs"] = Node(lgTvs);
        return root.ToJsonString(PortableJson);
    }
    public static ImportedBackup Parse(string json)
    {
        if (json.Length > 5_000_000) throw new InvalidOperationException("Backup troppo grande (massimo 5 MB).");
        try
        {
            var root = JsonNode.Parse(json)?.AsObject() ?? throw new JsonException();
            if (root["app"]?.ToString() != "hommaterial" || root["version"]?.GetValue<int>() != 1)
                throw new InvalidOperationException("Il file non è un backup Hommaterial versione 1.");
            T Read<T>(string name, T fallback) => root[name] is JsonNode node ? node.Deserialize<T>(PortableJson) ?? fallback : fallback;
            var home = new HomeData {
                Favorites = Read<HashSet<string>>("favorites", []), Hidden = Read<HashSet<string>>("hidden", []),
                Rooms = Read<Dictionary<string, string>>("rooms", []), RoomList = Read<List<string>>("roomList", []),
                Groups = Read<List<HomeGroup>>("groups", []), Macros = Read<List<HomeMacro>>("macros", []), Order = Read<Dictionary<string, List<string>>>("order", []),
                Alerts = Read<Dictionary<string, SensorAlert>>("alerts", []), RemoteButtons = Read<Dictionary<string, List<string>>>("remoteButtons", []),
                HiddenRemotes = Read<HashSet<string>>("hiddenRemotes", []), Monitor = Read("monitor", new MonitorSettings()),
                Schedules = Read<List<HomeSchedule>>("schedules", []), Automations = Read<List<AutomationRule>>("automations", []),
                Layout = Math.Clamp(Read("layout", 0), 0, 1), Language = Read("language", "auto"),
                Theme = Read("theme", 0) switch { 1 => "Light", 2 => "Dark", _ => "Default" }, BackupExtras = (JsonObject)root.DeepClone()
            };
            if (home.Groups.Any(g => g is null || string.IsNullOrWhiteSpace(g.Id) || g.Devices is null) || home.Macros.Any(m => m is null || string.IsNullOrWhiteSpace(m.Id))) throw new JsonException();
            foreach (var s in home.Schedules)
            {
                if (s is null || string.IsNullOrWhiteSpace(s.Id) || s.Hour is < 0 or > 23 || s.Minute is < 0 or > 59 || s.Days is null || s.Days.Any(d => d is < 1 or > 7)) throw new JsonException();
                _ = DateTimeOffset.FromUnixTimeMilliseconds(s.At); ValidateAction(s.Action); s.RetryAt = null;
            }
            foreach (var a in home.Automations)
            {
                if (a is null || !double.IsFinite(a.Threshold) || string.IsNullOrWhiteSpace(a.Device) || !new[] { "on", "off", "temperatureAbove", "temperatureBelow", "humidityAbove", "humidityBelow" }.Contains(a.Trigger)) throw new JsonException();
                ValidateAction(a.Action);
            }
            foreach (var m in home.Macros) foreach (var action in (m.Steps ?? []).Concat(m.Otherwise ?? [])) ValidateAction(action, false);
            foreach (var list in new[] { home.Schedules.Select(s => s.Id), home.Automations.Select(a => a.Id), home.Macros.Select(m => m.Id), home.Groups.Select(g => g.Id) })
                if (list.Count() != list.Distinct().Count()) throw new JsonException();
            var keys = root["switchBot"]?.Deserialize<SwitchBotKeys>(PortableJson);
            var tvs = root["lgTvs"]?.Deserialize<List<LgTv>>(PortableJson);
            if (tvs is not null && tvs.Any(t => t is null || t.Id?.StartsWith(LgTv.Prefix) != true || string.IsNullOrWhiteSpace(t.Host) || t.Key is null || t.Pin is null)) throw new JsonException();
            foreach (var name in new[] { "refreshToken", "cookie", "csrf", "monitorPin", "pin", "password", "session", "switchBot", "lgTvs" }) home.BackupExtras.Remove(name);
            return new(home, keys is { Token.Length: > 0 } ? keys : null, tvs);
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException or ArgumentException or NullReferenceException)
        { throw new InvalidOperationException("Backup non valido o danneggiato. Nessun dato è stato modificato.", e); }
    }
    public static void ValidateAction(HomeAction action, bool allowMacro = true)
    {
        if (action is null || action.Target is null || action.Value is null || !(action.Type switch {
            "power" => action.Target.Length > 0 && action.Value is "on" or "off",
            "remote" => action.Target.Length > 0 && action.Value.Length > 0,
            "macro" => allowMacro && action.Target.Length > 0,
            "wait" => int.TryParse(action.Value, out var seconds) && seconds is >= 0 and <= 300, _ => false })) throw new JsonException("Azione non valida.");
    }
    public static void Apply(HomeData target, HomeData imported, bool restoreMonitor)
    {
        target.Favorites = imported.Favorites; target.Hidden = imported.Hidden; target.Rooms = imported.Rooms;
        target.RoomList = imported.RoomList; target.Groups = imported.Groups; target.Macros = imported.Macros; target.Order = imported.Order;
        target.Alerts = imported.Alerts; target.RemoteButtons = imported.RemoteButtons; target.HiddenRemotes = imported.HiddenRemotes;
        target.Theme = imported.Theme; target.Layout = imported.Layout; target.Language = imported.Language;
        target.Schedules = imported.Schedules; target.Automations = imported.Automations; target.BackupExtras = imported.BackupExtras;
        target.Tripped.Clear();
        if (restoreMonitor) target.Monitor = imported.Monitor;
    }
}
