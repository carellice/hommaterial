using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Channels;

namespace Hommaterial.Core;

// An LG TV paired on the home network, in the same JSON as Android: Key is what the TV gave when the request was
// accepted, Pin the SHA-256 of its certificate as first seen, Macs wake it from standby, Apps its apps and Inputs its
// sources, such as the HDMI ports, by id.
public sealed record LgTv(string Id, string Name, string Host, string Key, string Pin, List<string>? Macs = null, Dictionary<string, string>? Apps = null,
    Dictionary<string, string>? Inputs = null)
{
    public const string Prefix = "lg:";
    public const string Type = "LG webOS";
    public RemoteDevice AsRemote() => new(Id, Name, Type);
}
public sealed record FoundTv(string Host, string? Name);
public enum LgTvError { Unreachable, Changed, Denied, NoAnswer, Closed, Refused, UnknownCommand, NoMac, PairAgain }
public sealed class LgTvException(LgTvError error, string detail = "") : InvalidOperationException(error switch
{
    LgTvError.Unreachable => $"Il TV a {detail} non risponde: è acceso e sulla stessa rete?",
    LgTvError.Changed => $"Il TV a {detail} non è più quello abbinato: rimuovilo e aggiungilo di nuovo.",
    LgTvError.Denied => "Il TV ha rifiutato la richiesta.",
    LgTvError.NoAnswer => "Il TV non ha risposto in tempo.",
    LgTvError.Closed => "Il TV ha chiuso la connessione.",
    LgTvError.Refused => "Il TV ha rifiutato il comando: " + detail,
    LgTvError.UnknownCommand => "Comando del TV sconosciuto: " + detail,
    LgTvError.NoMac => "Il TV non ha detto come risvegliarlo: accendilo con il suo telecomando.",
    _ => detail + " chiede di essere abbinato di nuovo: rimuovilo e aggiungilo ancora.",
})
{
    public LgTvError Error { get; } = error;
}

// What a command of the app is for webOS: a request with its payload, a key of the remote, or waking it up.
public abstract record WebOsAction;
public sealed record WebOsCall(string Uri, JsonObject? Payload = null) : WebOsAction;
public sealed record WebOsKey(string Name) : WebOsAction;
public sealed record WebOsWake : WebOsAction;

// The remote control of webOS over a WebSocket on the home network: no account, no cloud. The TV makes its own
// certificate, trusted as first seen and then by its pin. A connection is kept a while so keys in a row go at once.
public sealed class LgTvClient(int port = 3001) : IDisposable
{
    const string SsdpTarget = "urn:lge-com:service:webos-second-screen:1";
    const string RegisterId = "register_0";
    static readonly TimeSpan ReplyTimeout = TimeSpan.FromSeconds(10), PairingTimeout = TimeSpan.FromSeconds(60), IdleTime = TimeSpan.FromSeconds(30);
    static readonly string[] Permissions = ["APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
        "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT", "CONTROL_INPUT_TV", "CONTROL_MOUSE_AND_KEYBOARD",
        "CONTROL_POWER", "CONTROL_TV_SCREEN", "LAUNCH", "LAUNCH_WEBAPP", "READ_APP_STATUS", "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL",
        "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS", "READ_NETWORK_STATE", "READ_NOTIFICATIONS",
        "READ_POWER_STATE", "READ_RUNNING_APPS", "READ_SETTINGS", "READ_TV_CHANNEL_LIST", "READ_TV_CURRENT_TIME", "READ_UPDATE_INFO",
        "SEARCH", "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE", "UPDATE_FROM_REMOTE_APP", "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS"];
    static readonly string[] Media = ["play", "pause", "stop", "rewind", "fastForward"];
    readonly SemaphoreSlim gate = new(1, 1);
    readonly Dictionary<string, Kept> kept = [];

    // The names are those of the SwitchBot TV remotes, so buttons, voice and custom devices work the same with either.
    public static WebOsAction? Action(string command, string parameter = "default") => command switch
    {
        "turnOn" => new WebOsWake(),
        "turnOff" => new WebOsCall("ssap://system/turnOff"),
        "volumeAdd" => new WebOsCall("ssap://audio/volumeUp"),
        "volumeSub" => new WebOsCall("ssap://audio/volumeDown"),
        "channelAdd" => new WebOsCall("ssap://tv/channelUp"),
        "channelSub" => new WebOsCall("ssap://tv/channelDown"),
        "setMute" => new WebOsKey("MUTE"),
        "SetChannel" when parameter.Length > 0 && parameter.All(char.IsAsciiDigit) => new WebOsCall("ssap://tv/openChannel", new() { ["channelNumber"] = parameter }),
        "setVolume" => int.TryParse(parameter, out var volume) && volume is >= 0 and <= 100 ? new WebOsCall("ssap://audio/setVolume", new() { ["volume"] = volume }) : null,
        "toast" => new WebOsCall("ssap://system.notifications/createToast", new() { ["message"] = parameter }),
        _ when Media.Contains(command) => new WebOsCall("ssap://media.controls/" + command),
        _ when command.StartsWith("app:") && command.Length > 4 => new WebOsCall("ssap://system.launcher/launch", new() { ["id"] = command[4..] }),
        _ when command.StartsWith("input:") && command.Length > 6 => new WebOsCall("ssap://tv/switchInput", new() { ["inputId"] = command[6..] }),
        _ when command.StartsWith("key:") && command.Length > 4 && command[4..].All(c => char.IsAsciiLetterUpper(c) || char.IsAsciiDigit(c) || c == '_') => new WebOsKey(command[4..]),
        _ => null,
    };
    public static JsonObject RegisterMessage(string? key)
    {
        var payload = new JsonObject { ["forcePairing"] = false, ["pairingType"] = "PROMPT",
            ["manifest"] = new JsonObject { ["appVersion"] = "1.1", ["manifestVersion"] = 1, ["permissions"] = new JsonArray(Permissions.Select(p => (JsonNode?)p).ToArray()) } };
        if (key is not null) payload["client-key"] = key;
        return new() { ["type"] = "register", ["id"] = RegisterId, ["payload"] = payload };
    }
    public static JsonObject Request(string id, string uri, JsonObject? payload = null) => new() { ["type"] = "request", ["id"] = id, ["uri"] = uri, ["payload"] = payload?.DeepClone() ?? new JsonObject() };
    public static string KeyMessage(string name) => $"type:button\nname:{name}\n\n";
    public static List<string> Macs(JsonObject payload) => new[] { "wiredInfo", "wifiInfo" }
        .Select(n => payload[n]?["macAddress"]?.ToString()).Where(m => m is not null && IsMac(m)).Select(m => m!.ToLowerInvariant()).Distinct().ToList();
    public static Dictionary<string, string> Apps(JsonObject payload)
    {
        var apps = new Dictionary<string, string>();
        foreach (var point in payload["launchPoints"]?.AsArray() ?? [])
            if (point?["id"]?.ToString() is { Length: > 0 } id && point["title"]?.ToString() is { } title && !string.IsNullOrWhiteSpace(title)) apps.TryAdd(id, title);
        return apps;
    }
    // The sources of the TV, by id, with the names it shows for them.
    public static Dictionary<string, string> Inputs(JsonObject payload)
    {
        var inputs = new Dictionary<string, string>();
        foreach (var device in payload["devices"]?.AsArray() ?? [])
            if (device?["id"]?.ToString() is { Length: > 0 } id)
                inputs.TryAdd(id, device["label"]?.ToString() is { } label && !string.IsNullOrWhiteSpace(label) ? label : id);
        return inputs;
    }
    static bool IsMac(string text) => System.Text.RegularExpressions.Regex.IsMatch(text, "^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$");
    // The magic packet: six bytes 0xFF, then the address sixteen times.
    public static byte[] WakePacket(string mac)
    {
        if (!IsMac(mac)) throw new ArgumentException("Indirizzo MAC non valido: " + mac);
        var address = mac.Split(':', '-').Select(b => Convert.ToByte(b, 16)).ToArray();
        return Enumerable.Repeat((byte)0xFF, 6).Concat(Enumerable.Repeat(address, 16).SelectMany(a => a)).ToArray();
    }
    public static string CertificatePin(byte[] der) => Convert.ToHexString(SHA256.HashData(der)).ToLowerInvariant();
    public static FoundTv? ParseSsdp(string text, string host)
    {
        var lines = text.Split(["\r\n", "\n"], StringSplitOptions.None);
        if (lines.Length == 0 || !lines[0].StartsWith("HTTP/1.1 200")) return null;
        var headers = new Dictionary<string, string>();
        foreach (var line in lines.Skip(1)) if (line.IndexOf(':') is var i and > 0) headers.TryAdd(line[..i].Trim().ToLowerInvariant(), line[(i + 1)..].Trim());
        if (headers.GetValueOrDefault("st") != SsdpTarget) return null;
        // LG TVs put the name the user gave them in a header of their own, encoded as in a URL.
        var name = headers.TryGetValue("dlnadevicename.lge.com", out var encoded) ? Uri.UnescapeDataString(encoded) : null;
        return new(host, string.IsNullOrWhiteSpace(name) ? null : name);
    }
    public static string SsdpSearch => $"M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: {SsdpTarget}\r\n\r\n";

    public async Task<List<FoundTv>> DiscoverAsync(TimeSpan? duration = null, CancellationToken cancellation = default)
    {
        var found = new Dictionary<string, FoundTv>();
        using var udp = new UdpClient(AddressFamily.InterNetwork);
        var search = Encoding.ASCII.GetBytes(SsdpSearch);
        var group = new IPEndPoint(IPAddress.Parse("239.255.255.250"), 1900);
        // Twice, as datagrams may get lost.
        for (var i = 0; i < 2; i++) await udp.SendAsync(search, group, cancellation);
        using var window = CancellationTokenSource.CreateLinkedTokenSource(cancellation); window.CancelAfter(duration ?? TimeSpan.FromSeconds(3));
        try
        {
            while (true)
            {
                var answer = await udp.ReceiveAsync(window.Token);
                var host = answer.RemoteEndPoint.Address.ToString();
                if (ParseSsdp(Encoding.UTF8.GetString(answer.Buffer), host) is { } tv) found.TryAdd(host, tv);
            }
        }
        catch (OperationCanceledException) when (!cancellation.IsCancellationRequested) { }
        return found.Values.ToList();
    }

    // Asks the TV at host to be controlled, which it does by asking the user on screen; greeting is shown once accepted.
    public async Task<LgTv> PairAsync(string host, string greeting, Action? asking = null, CancellationToken cancellation = default)
    {
        string? seen = null;
        using var session = await Session.OpenAsync(new Uri($"wss://{host}:{port}"), host, null, pin => seen = pin, cancellation);
        await session.SendAsync(new JsonObject { ["id"] = "hello", ["type"] = "hello", ["payload"] = new JsonObject() }, cancellation);
        await session.TryReplyAsync("hello", cancellation);
        // Recent webOS tells about itself only before the registration.
        await session.SendAsync(Request("info", "ssap://system/getSystemInfo"), cancellation);
        var model = (await session.TryReplyAsync("info", cancellation))?["payload"]?["modelName"]?.ToString();
        var key = await RegisterAsync(session, null, asking, cancellation);
        await session.SendAsync(Request("network", "ssap://com.webos.service.connectionmanager/getinfo"), cancellation);
        var macs = (await session.TryReplyAsync("network", cancellation))?["payload"] is JsonObject network ? Macs(network) : [];
        try { await session.CallAsync(new WebOsCall("ssap://system.notifications/createToast", new() { ["message"] = greeting }), cancellation); }
        catch (LgTvException) { /* The greeting is a courtesy. */ }
        return new(LgTv.Prefix + Guid.NewGuid(), string.IsNullOrWhiteSpace(model) ? "LG TV" : "LG " + model, host, key,
            seen ?? throw new LgTvException(LgTvError.Unreachable, host), macs, []);
    }

    public async Task SendAsync(LgTv tv, string command, string parameter = "default", CancellationToken cancellation = default)
    {
        switch (Action(command, parameter) ?? throw new LgTvException(LgTvError.UnknownCommand, command))
        {
            case WebOsWake: await WakeAsync(tv, cancellation); break;
            case WebOsCall call: await WithSessionAsync(tv, k => k.Main.CallAsync(call, cancellation), cancellation); break;
            case WebOsKey key:
                await WithSessionAsync(tv, async connection =>
                {
                    // The keys go on a socket of their own, which the TV opens on request.
                    if (connection.Keys is not { Open: true })
                    {
                        var path = (await connection.Main.CallAsync(new WebOsCall("ssap://com.webos.service.networkinput/getPointerInputSocket"), cancellation))["socketPath"]?.ToString();
                        if (path is null || !(path.StartsWith("wss://") || path.StartsWith("ws://"))) throw new LgTvException(LgTvError.Refused, "socketPath");
                        connection.Keys?.Dispose();
                        connection.Keys = await Session.OpenAsync(new Uri(path), tv.Host, tv.Pin, null, cancellation);
                    }
                    await connection.Keys.SendTextAsync(KeyMessage(key.Name), cancellation);
                    return new JsonObject();
                }, cancellation);
                break;
        }
    }

    public Task<Dictionary<string, string>> AppsAsync(LgTv tv, CancellationToken cancellation = default) =>
        WithSessionAsync(tv, async k => Apps(await k.Main.CallAsync(new WebOsCall("ssap://com.webos.applicationManager/listLaunchPoints"), cancellation)), cancellation);

    public Task<Dictionary<string, string>> InputsAsync(LgTv tv, CancellationToken cancellation = default) =>
        WithSessionAsync(tv, async k => Inputs(await k.Main.CallAsync(new WebOsCall("ssap://tv/getExternalInputList"), cancellation)), cancellation);

    // Wakes the TV from standby, which works only while its network stays on.
    public static async Task WakeAsync(LgTv tv, CancellationToken cancellation = default)
    {
        if (tv.Macs is not { Count: > 0 }) throw new LgTvException(LgTvError.NoMac);
        var targets = new List<IPAddress> { IPAddress.Broadcast };
        // The broadcast of the TV's own network as well, which some routers prefer.
        if (IPAddress.TryParse(tv.Host, out var address) && address.AddressFamily == AddressFamily.InterNetwork)
        { var bytes = address.GetAddressBytes(); bytes[3] = 0xFF; targets.Add(new IPAddress(bytes)); }
        using var udp = new UdpClient { EnableBroadcast = true };
        foreach (var mac in tv.Macs)
        {
            var packet = WakePacket(mac);
            foreach (var target in targets) try { await udp.SendAsync(packet, new IPEndPoint(target, 9), cancellation); } catch (SocketException) { }
        }
    }

    // One request at a time on the connection kept from before when still open; once more on a new one if the TV
    // closed it in the meantime, as it does going to standby.
    async Task<T> WithSessionAsync<T>(LgTv tv, Func<Kept, Task<T>> block, CancellationToken cancellation)
    {
        await gate.WaitAsync(cancellation);
        try
        {
            var before = kept.GetValueOrDefault(tv.Id) is { Main.Open: true } k ? k : null;
            before?.Idle?.Cancel();
            var connection = before ?? await ConnectAsync(tv, cancellation);
            try { var result = await block(connection); Keep(tv, connection); return result; }
            catch (LgTvException e) when (before is not null && e.Error == LgTvError.Closed)
            {
                Forget(tv, connection);
                var fresh = await ConnectAsync(tv, cancellation);
                try { var result = await block(fresh); Keep(tv, fresh); return result; }
                catch { fresh.Dispose(); throw; }
            }
            catch { Forget(tv, connection); throw; }
        }
        finally { gate.Release(); }
    }
    async Task<Kept> ConnectAsync(LgTv tv, CancellationToken cancellation)
    {
        var session = await Session.OpenAsync(new Uri($"wss://{tv.Host}:{port}"), tv.Host, tv.Pin, null, cancellation);
        try { await RegisterAsync(session, tv.Key, () => throw new LgTvException(LgTvError.PairAgain, tv.Name), cancellation); }
        catch { session.Dispose(); throw; }
        return new(session);
    }
    void Keep(LgTv tv, Kept connection)
    {
        kept[tv.Id] = connection;
        var idle = connection.Idle = new CancellationTokenSource();
        _ = CloseLaterAsync(tv, connection, idle.Token);
    }
    async Task CloseLaterAsync(LgTv tv, Kept connection, CancellationToken idle)
    {
        try { await Task.Delay(IdleTime, idle); } catch (OperationCanceledException) { return; }
        await gate.WaitAsync();
        try { if (!idle.IsCancellationRequested) Forget(tv, connection); } finally { gate.Release(); }
    }
    void Forget(LgTv tv, Kept connection)
    {
        if (kept.GetValueOrDefault(tv.Id) == connection) kept.Remove(tv.Id);
        connection.Dispose();
    }
    static async Task<string> RegisterAsync(Session session, string? key, Action? asking, CancellationToken cancellation)
    {
        await session.SendAsync(RegisterMessage(key), cancellation);
        using var window = CancellationTokenSource.CreateLinkedTokenSource(cancellation); window.CancelAfter(PairingTimeout);
        JsonObject answer;
        try
        {
            answer = await session.ReplyAsync(RegisterId, window.Token);
            // A plain response means the TV is asking the user; the outcome comes after.
            while (answer["type"]?.ToString() == "response")
            {
                if (answer["payload"]?["pairingType"]?.ToString() == "PROMPT") asking?.Invoke();
                answer = await session.ReplyAsync(RegisterId, window.Token);
            }
        }
        catch (OperationCanceledException) when (!cancellation.IsCancellationRequested) { throw new LgTvException(LgTvError.NoAnswer); }
        if (answer["type"]?.ToString() != "registered") throw new LgTvException(LgTvError.Denied);
        return answer["payload"]?["client-key"]?.ToString() is { Length: > 0 } given ? given : key ?? throw new LgTvException(LgTvError.Denied);
    }

    public void Dispose()
    {
        foreach (var connection in kept.Values) connection.Dispose();
        kept.Clear();
    }

    sealed class Kept(Session main) : IDisposable
    {
        public Session Main { get; } = main;
        public Session? Keys { get; set; }
        public CancellationTokenSource? Idle { get; set; }
        public void Dispose() { Idle?.Cancel(); Keys?.Dispose(); Main.Dispose(); }
    }

    sealed class Session : IDisposable
    {
        readonly ClientWebSocket socket;
        readonly Channel<JsonObject> incoming = Channel.CreateUnbounded<JsonObject>();
        int count;
        Session(ClientWebSocket socket) { this.socket = socket; _ = ReadAsync(); }
        public bool Open => socket.State == WebSocketState.Open && !incoming.Reader.Completion.IsCompleted;

        public static async Task<Session> OpenAsync(Uri uri, string host, string? pin, Action<string>? seen, CancellationToken cancellation)
        {
            var changed = false;
            var socket = new ClientWebSocket();
            // The certificate is not made out to the address of the TV: the pin stands for the name.
            socket.Options.RemoteCertificateValidationCallback = (_, certificate, _, _) =>
            {
                if (certificate is null) return false;
                var got = CertificatePin(certificate.GetRawCertData());
                if (pin is not null && got != pin) { changed = true; return false; }
                seen?.Invoke(got); return true;
            };
            using var window = CancellationTokenSource.CreateLinkedTokenSource(cancellation); window.CancelAfter(ReplyTimeout);
            try { await socket.ConnectAsync(uri, window.Token); }
            catch (Exception e) when (e is WebSocketException or HttpRequestException or IOException || e is OperationCanceledException && !cancellation.IsCancellationRequested)
            {
                socket.Dispose();
                throw new LgTvException(changed ? LgTvError.Changed : LgTvError.Unreachable, host);
            }
            return new(socket);
        }

        async Task ReadAsync()
        {
            var buffer = new byte[16 * 1024];
            using var message = new MemoryStream();
            try
            {
                while (socket.State == WebSocketState.Open)
                {
                    var part = await socket.ReceiveAsync(buffer, CancellationToken.None);
                    if (part.MessageType == WebSocketMessageType.Close) break;
                    message.Write(buffer, 0, part.Count);
                    if (!part.EndOfMessage) continue;
                    try { if (JsonNode.Parse(message.ToArray()) is JsonObject json) incoming.Writer.TryWrite(json); }
                    catch (System.Text.Json.JsonException) { }
                    message.SetLength(0);
                }
            }
            catch (Exception e) when (e is WebSocketException or ObjectDisposedException or OperationCanceledException) { }
            incoming.Writer.TryComplete();
        }

        public Task SendAsync(JsonObject message, CancellationToken cancellation) => SendTextAsync(message.ToJsonString(), cancellation);
        public async Task SendTextAsync(string text, CancellationToken cancellation)
        {
            try { await socket.SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, true, cancellation); }
            catch (Exception e) when (e is WebSocketException or ObjectDisposedException) { throw new LgTvException(LgTvError.Closed); }
        }
        // The next message about the request with this id; those about others are let go.
        public async Task<JsonObject> ReplyAsync(string id, CancellationToken cancellation)
        {
            while (true)
            {
                JsonObject message;
                try { message = await incoming.Reader.ReadAsync(cancellation); }
                catch (ChannelClosedException) { throw new LgTvException(LgTvError.Closed); }
                if (message["id"]?.ToString() == id) return message;
            }
        }
        public async Task<JsonObject?> TryReplyAsync(string id, CancellationToken cancellation)
        {
            using var window = CancellationTokenSource.CreateLinkedTokenSource(cancellation); window.CancelAfter(ReplyTimeout);
            try { return await ReplyAsync(id, window.Token); }
            catch (OperationCanceledException) when (!cancellation.IsCancellationRequested) { return null; }
        }
        // Sends a request and returns the payload of its answer, if the TV did it.
        public async Task<JsonObject> CallAsync(WebOsCall call, CancellationToken cancellation)
        {
            var id = "call_" + count++;
            await SendAsync(Request(id, call.Uri, call.Payload), cancellation);
            var answer = await TryReplyAsync(id, cancellation) ?? throw new LgTvException(LgTvError.NoAnswer);
            var payload = answer["payload"] as JsonObject ?? new JsonObject();
            if (answer["type"]?.ToString() == "error" || payload["returnValue"]?.GetValue<bool>() == false)
                throw new LgTvException(LgTvError.Refused, answer["error"]?.ToString() ?? payload["errorText"]?.ToString() ?? "");
            return payload;
        }
        public void Dispose()
        {
            if (socket.State == WebSocketState.Open) _ = socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None).ContinueWith(_ => socket.Dispose());
            else socket.Dispose();
        }
    }
}
