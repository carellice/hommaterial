using System.Text.Json;
using System.Text.Json.Nodes;

namespace Hommaterial.Core;

public sealed record Device(string ApplianceId, string EntityId, string Name, string Category,
    string? Room, bool HasPower, bool HasBrightness = false, bool IsSensor = false,
    bool HasColor = false, bool HasColorTemperature = false);

public sealed record DeviceState(bool? Power = null, int? Brightness = null,
    double? Temperature = null, int? Humidity = null, string? ColorName = null, bool Reachable = true)
{
    public string Description => !Reachable ? "Non raggiungibile" : Temperature is double t
        ? $"{t:F1} °C" + (Humidity is int h ? $" · {h}%" : "")
        : Power == true ? "Acceso" + (Brightness is int b ? $" · {b}%" : "")
        : Power == false ? "Spento" : "Stato da aggiornare";
}

public sealed class HomeData
{
    public HashSet<string> Favorites { get; set; } = [];
    public HashSet<string> Hidden { get; set; } = [];
    public List<Device> Devices { get; set; } = [];
    public Dictionary<string, DeviceState> States { get; set; } = [];
    public string Theme { get; set; } = "Default";
    public string Marketplace { get; set; } = "amazon.it";
    public DateTimeOffset? UpdatedAt { get; set; }
    public Dictionary<string, string> Rooms { get; set; } = [];
    public List<string> RoomList { get; set; } = [];
    public List<HomeGroup> Groups { get; set; } = [];
    public List<HomeMacro> Macros { get; set; } = [];
    public Dictionary<string, List<string>> Order { get; set; } = [];
    public Dictionary<string, int> TileSpans { get; set; } = [];
    public List<HomeSchedule> Schedules { get; set; } = [];
    public List<AutomationRule> Automations { get; set; } = [];
    public Dictionary<string, SensorAlert> Alerts { get; set; } = [];
    public Dictionary<string, List<SensorReading>> History { get; set; } = [];
    public HashSet<string> Tripped { get; set; } = [];
    public List<RemoteDevice> Remotes { get; set; } = [];
    public Dictionary<string, List<string>> RemoteButtons { get; set; } = [];
    public HashSet<string> HiddenRemotes { get; set; } = [];
    public Dictionary<string, AcSetting> AcSettings { get; set; } = [];
    public MonitorSettings Monitor { get; set; } = new();
    public string? MonitorPin { get; set; }
    public int Layout { get; set; }
    public string Language { get; set; } = "auto";
    public bool Notifications { get; set; } = true;
    public bool RunInBackground { get; set; } = true;
    // Unknown portable fields (including Android widget/tiles) survive a Windows round trip.
    public JsonObject BackupExtras { get; set; } = new();
    public string? RoomOf(Device device) => Rooms.TryGetValue(device.ApplianceId, out var room) ? string.IsNullOrEmpty(room) ? null : room : device.Room;
    public string? PlaceOf(string key) => Rooms.GetValueOrDefault(key);
    public IEnumerable<string> AllRooms() => Devices.Select(RoomOf).Concat(RoomList).Concat(Rooms.Values).Where(r => !string.IsNullOrEmpty(r)).Select(r => r!).Distinct().Order();
    public static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true, PropertyNameCaseInsensitive = true };
}

public sealed record Marketplace(string Domain, string Locale)
{
    public string Host => "alexa." + Domain;
    public static readonly Marketplace[] All = [new("amazon.it", "it-IT"), new("amazon.de", "de-DE"),
        new("amazon.fr", "fr-FR"), new("amazon.es", "es-ES"), new("amazon.co.uk", "en-GB"), new("amazon.com", "en-US")];
}

public static class AlexaParser
{
    public static List<Device> Devices(JsonNode endpoints, JsonNode groups)
    {
        var rooms = new Dictionary<string, string>();
        foreach (var group in groups["applianceGroups"]?.AsArray() ?? [])
            foreach (var id in group?["applianceIds"]?.AsArray() ?? [])
                if (id is not null) rooms.TryAdd(id.ToString(), group?["name"]?.ToString() ?? "");
        var items = endpoints["data"]?["endpoints"]?["items"]?.AsArray()
            ?? throw new InvalidOperationException("Alexa ha restituito un elenco inatteso.");
        var result = new List<Device>();
        foreach (var item in items)
        {
            var a = item?["legacyAppliance"];
            var id = a?["applianceId"]?.ToString();
            if (string.IsNullOrEmpty(id) || a?["isEnabled"]?.GetValue<bool>() == false) continue;
            var interfaces = (a?["capabilities"]?.AsArray() ?? []).Select(c => c?["interfaceName"]?.ToString()).ToHashSet();
            var category = item?["displayCategories"]?["primary"]?["value"]?.ToString() ?? "OTHER";
            var power = interfaces.Contains("Alexa.PowerController");
            var sensor = interfaces.Contains("Alexa.TemperatureSensor");
            if (category == "ALEXA_VOICE_ENABLED" || (!power && !sensor)) continue;
            result.Add(new(id, a?["entityId"]?.ToString() ?? "", item?["friendlyName"]?.ToString() ?? id,
                category, rooms.GetValueOrDefault(id), power, interfaces.Contains("Alexa.BrightnessController"), sensor,
                interfaces.Contains("Alexa.ColorController"), interfaces.Contains("Alexa.ColorTemperatureController")));
        }
        return result.OrderBy(d => d.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
    }

    public static Dictionary<string, DeviceState> States(JsonNode response)
    {
        var result = new Dictionary<string, DeviceState>();
        foreach (var entry in response["deviceStates"]?.AsArray() ?? [])
        {
            var id = entry?["entity"]?["entityId"]?.ToString();
            if (string.IsNullOrEmpty(id)) continue;
            var state = new DeviceState();
            foreach (var raw in entry?["capabilityStates"]?.AsArray() ?? [])
            {
                JsonNode? cap;
                try { cap = raw is JsonValue ? JsonNode.Parse(raw.ToString()) : raw; }
                catch (JsonException) { continue; }
                var value = cap?["value"];
                state = cap?["name"]?.ToString() switch
                {
                    "powerState" => state with { Power = value?.ToString() == "ON" },
                    "brightness" => state with { Brightness = value?.GetValue<int>() },
                    "temperature" => state with { Temperature = value?["value"]?.GetValue<double>() },
                    "relativeHumidity" => state with { Humidity = value?.GetValue<int>() },
                    "colorProperties" => state with { ColorName = value?["name"]?.ToString() },
                    _ => state
                };
            }
            result[id] = state;
        }
        foreach (var error in response["errors"]?.AsArray() ?? [])
            if (error?["entity"]?["entityId"]?.ToString() is string id) result[id] = new(Reachable: false);
        return result;
    }
}

public static class DemoHome
{
    public static HomeData Create() => new()
    {
        Devices = [new("demo-1", "demo-1", "Lampada divano", "LIGHT", "Soggiorno", true, true, HasColor: true, HasColorTemperature: true),
            new("demo-2", "demo-2", "Luce libreria", "LIGHT", "Soggiorno", true, true),
            new("demo-3", "demo-3", "Clima soggiorno", "TEMPERATURE_SENSOR", "Soggiorno", false, IsSensor: true),
            new("demo-4", "demo-4", "Luce cucina", "LIGHT", "Cucina", true, true),
            new("demo-5", "demo-5", "Macchina del caffè", "SMARTPLUG", "Cucina", true),
            new("demo-6", "demo-6", "Comodino", "LIGHT", "Camera", true, true),
            new("demo-7", "demo-7", "Clima camera", "TEMPERATURE_SENSOR", "Camera", false, IsSensor: true)],
        States = new() { ["demo-1"] = new(true, 65), ["demo-2"] = new(false, 40),
            ["demo-3"] = new(Temperature: 22.4, Humidity: 48), ["demo-4"] = new(true, 90),
            ["demo-5"] = new(false), ["demo-6"] = new(false, 25), ["demo-7"] = new(Temperature: 20.8, Humidity: 52) },
        Favorites = ["demo-1", "demo-4", "demo-7"], UpdatedAt = DateTimeOffset.Now,
        Groups = [new("demo-group", "Luci della casa", ["demo-1", "demo-2", "demo-4", "demo-6"])],
        Macros = [new("demo-macro", "Buonanotte", Steps: [new("power", "demo-1", "off"), new("wait", Value: "1"), new("power", "demo-4", "off")])],
        Remotes = [new("demo-remote", "TV soggiorno", "TV")],
        RemoteButtons = new() { ["demo-remote"] = ["Netflix", "HDMI 1"] },
        Automations = [new() { Id = "demo-automation", Device = "demo-3", Trigger = "temperatureAbove", Threshold = 25,
            Action = new("power", "demo-1", "off"), Title = "Soggiorno sopra 25 °C", Label = "Spegni lampada divano", Enabled = false }],
        History = new() { ["demo-3"] = Enumerable.Range(0, 36).Select(i => new SensorReading(DateTimeOffset.Now.AddMinutes((i - 35) * 20).ToUnixTimeMilliseconds(), Math.Round(21.5 + Math.Sin(i / 5.0), 1), 48 + (int)(4 * Math.Sin(i / 4.0)))).ToList() }
    };
}
