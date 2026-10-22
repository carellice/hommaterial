using System.Collections.Concurrent;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json.Nodes;
using Hommaterial.Core;

static class LgTvTests
{
    public static async Task Run(Action<bool, string> check)
    {
        check(LgTvClient.Action("turnOn") is WebOsWake && LgTvClient.Action("volumeAdd") is WebOsCall { Uri: "ssap://audio/volumeUp" }
            && LgTvClient.Action("setMute") is WebOsKey { Name: "MUTE" }, "TV LG: comandi con i nomi dei telecomandi SwitchBot");
        check(LgTvClient.Action("SetChannel", "105") is WebOsCall { Uri: "ssap://tv/openChannel" } channel && channel.Payload?["channelNumber"]?.ToString() == "105"
            && LgTvClient.Action("SetChannel", "uno") is null && LgTvClient.Action("setVolume", "120") is null, "TV LG: canale per numero e volume nei limiti");
        check(LgTvClient.Action("app:netflix") is WebOsCall { Uri: "ssap://system.launcher/launch" } app && app.Payload?["id"]?.ToString() == "netflix"
            && LgTvClient.Action("key:UP") is WebOsKey { Name: "UP" } && LgTvClient.Action("key:up\nname:POWER") is null && LgTvClient.Action("selfDestruct") is null, "TV LG: app, tasti e comandi sconosciuti");
        var register = LgTvClient.RegisterMessage(null);
        check(register["payload"]?["pairingType"]?.ToString() == "PROMPT" && register["payload"]?["client-key"] is null
            && LgTvClient.RegisterMessage("abc")["payload"]?["client-key"]?.ToString() == "abc", "TV LG: registrazione con la chiave solo quando c'è");
        var packet = LgTvClient.WakePacket("A8:23:FE:01:02:0c");
        check(packet.Length == 102 && packet.Take(6).All(b => b == 0xFF) && packet.Skip(96).SequenceEqual(new byte[] { 0xA8, 0x23, 0xFE, 0x01, 0x02, 0x0C })
            && packet.SequenceEqual(LgTvClient.WakePacket("a8-23-fe-01-02-0C")), "TV LG: pacchetto Wake-on-LAN");
        var ssdp = "HTTP/1.1 200 OK\r\nST: urn:lge-com:service:webos-second-screen:1\r\nDLNADeviceName.lge.com: %5BLG%5D%20Sala%20OLED\r\n\r\n";
        check(LgTvClient.ParseSsdp(ssdp, "192.168.1.20") == new FoundTv("192.168.1.20", "[LG] Sala OLED") && LgTvClient.ParseSsdp(ssdp.Replace("webos-second-screen", "dial"), "x") is null,
            "TV LG: la ricerca trova solo i TV LG, con il loro nome");
        check(LgTvClient.Macs(JsonNode.Parse("""{"wiredInfo":{"macAddress":"A8:23:FE:01:02:03"},"wifiInfo":{"macAddress":"none"}}""")!.AsObject()).SequenceEqual(["a8:23:fe:01:02:03"])
            && LgTvClient.Apps(JsonNode.Parse("""{"launchPoints":[{"id":"netflix","title":"Netflix"},{"id":"","title":"x"}]}""")!.AsObject()).Count == 1, "TV LG: MAC e app dalle risposte del TV");

        await using (var fake = new FakeTv())
        {
            using var lg = new LgTvClient(fake.Port);
            var asked = false;
            var tv = await lg.PairAsync("127.0.0.1", "Ciao", () => asked = true);
            check(asked && tv.Key == "abc" && tv.Name == "LG OLED55C1" && tv.Pin == fake.Pin && tv.Macs!.SequenceEqual(["a8:23:fe:01:02:03"]) && tv.Id.StartsWith(LgTv.Prefix),
                "TV LG: abbinamento con chiave, certificato e indirizzi");
            await lg.SendAsync(tv, "volumeAdd");
            await lg.SendAsync(tv, "key:UP");
            await lg.SendAsync(tv, "key:DOWN");
            check(await fake.KeyAsync() == "type:button\nname:UP\n\n" && await fake.KeyAsync() == "type:button\nname:DOWN\n\n", "TV LG: i tasti vanno sul loro socket");
            // The pairing, then one connection kept for the commands and one for the keys.
            check(fake.Connections == 3 && fake.Received.Count(m => m["type"]?.ToString() == "register" && m["payload"]?["client-key"]?.ToString() == "abc") == 1,
                "TV LG: tasti di fila su una sola connessione");
            check(await Fails(() => lg.SendAsync(tv, "channelAdd")) == LgTvError.Refused, "TV LG: comando rifiutato dal TV segnalato");
            using var other = new LgTvClient(fake.Port);
            check(await Fails(() => other.SendAsync(tv with { Pin = new string('0', 64) }, "volumeAdd")) == LgTvError.Changed, "TV LG: certificato diverso allo stesso indirizzo rifiutato");
            check(await Fails(() => other.SendAsync(tv, "selfDestruct")) == LgTvError.UnknownCommand, "TV LG: comando sconosciuto rifiutato prima di collegarsi");
            fake.Deny = true;
            check(await Fails(() => other.PairAsync("127.0.0.1", "Ciao")) == LgTvError.Denied, "TV LG: rifiuto sul TV segnalato");
        }
        var closed = new TcpListener(IPAddress.Loopback, 0); closed.Start(); var port = ((IPEndPoint)closed.LocalEndpoint).Port; closed.Stop();
        using (var off = new LgTvClient(port))
            check(await Fails(() => off.SendAsync(new LgTv("lg:1", "Sala", "127.0.0.1", "abc", "p"), "volumeAdd")) == LgTvError.Unreachable, "TV LG: TV spento segnalato come non raggiungibile");
    }

    static async Task<LgTvError?> Fails(Func<Task> action)
    {
        try { await action(); return null; }
        catch (LgTvException e) { return e.Error; }
    }

    // A TV on a local socket, answering as webOS does, with a certificate of its own making.
    sealed class FakeTv : IAsyncDisposable
    {
        readonly TcpListener listener = new(IPAddress.Loopback, 0);
        readonly X509Certificate2 certificate;
        readonly CancellationTokenSource stop = new();
        readonly ConcurrentQueue<string> keys = new();
        readonly List<Task> clients = [];
        public ConcurrentQueue<JsonObject> Received { get; } = new();
        public int Port { get; }
        public string Pin { get; }
        public bool Deny { get; set; }
        public int Connections;

        public FakeTv()
        {
            using var rsa = RSA.Create(2048);
            using var made = new CertificateRequest("CN=LG webOS TV", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1).CreateSelfSigned(DateTimeOffset.Now.AddDays(-1), DateTimeOffset.Now.AddDays(1));
            // Exported and read again, as Windows serves TLS only with a key it can keep.
            certificate = new X509Certificate2(made.Export(X509ContentType.Pfx));
            Pin = LgTvClient.CertificatePin(certificate.RawData);
            listener.Start(); Port = ((IPEndPoint)listener.LocalEndpoint).Port;
            _ = AcceptAsync();
        }

        public async Task<string?> KeyAsync()
        {
            for (var i = 0; i < 100; i++) { if (keys.TryDequeue(out var key)) return key; await Task.Delay(50); }
            return null;
        }

        async Task AcceptAsync()
        {
            try { while (true) { var tcp = await listener.AcceptTcpClientAsync(stop.Token); lock (clients) clients.Add(ServeAsync(tcp)); } }
            catch (Exception e) when (e is OperationCanceledException or SocketException or ObjectDisposedException) { }
        }

        async Task ServeAsync(TcpClient tcp)
        {
            using var _ = tcp;
            try
            {
                using var tls = new SslStream(tcp.GetStream());
                await tls.AuthenticateAsServerAsync(certificate);
                var request = new StringBuilder();
                var one = new byte[1];
                while (!request.ToString().EndsWith("\r\n\r\n") && await tls.ReadAsync(one, stop.Token) == 1) request.Append((char)one[0]);
                var lines = request.ToString().Split("\r\n");
                var path = lines[0].Split(' ')[1];
                var key = lines.First(l => l.StartsWith("Sec-WebSocket-Key:", StringComparison.OrdinalIgnoreCase)).Split(':', 2)[1].Trim();
                var accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
                await tls.WriteAsync(Encoding.ASCII.GetBytes($"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n"), stop.Token);
                Interlocked.Increment(ref Connections);
                using var socket = WebSocket.CreateFromStream(tls, true, null, TimeSpan.FromSeconds(30));
                var buffer = new byte[16 * 1024];
                while (socket.State == WebSocketState.Open)
                {
                    var part = await socket.ReceiveAsync(buffer, stop.Token);
                    if (part.MessageType == WebSocketMessageType.Close) { await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None); break; }
                    var text = Encoding.UTF8.GetString(buffer, 0, part.Count);
                    if (path == "/pointer") { keys.Enqueue(text); continue; }
                    var message = JsonNode.Parse(text)!.AsObject();
                    Received.Enqueue(message);
                    foreach (var reply in Answer(message)) await socket.SendAsync(Encoding.UTF8.GetBytes(reply.ToJsonString()), WebSocketMessageType.Text, true, stop.Token);
                }
            }
            catch (Exception e) when (e is IOException or WebSocketException or OperationCanceledException or System.Security.Authentication.AuthenticationException or ObjectDisposedException) { }
        }

        IEnumerable<JsonObject> Answer(JsonObject message)
        {
            var id = message["id"]?.ToString();
            JsonObject Reply(string type, JsonObject payload) => new() { ["type"] = type, ["id"] = id, ["payload"] = payload };
            switch (message["type"]?.ToString())
            {
                case "hello": yield return Reply("hello", new()); break;
                case "register":
                    if (message["payload"]?["client-key"] is null)
                    {
                        yield return Reply("response", new() { ["pairingType"] = "PROMPT", ["returnValue"] = true });
                        if (Deny) { yield return new() { ["type"] = "error", ["id"] = id, ["error"] = "403 User denied access" }; break; }
                    }
                    yield return Reply("registered", new() { ["client-key"] = "abc" });
                    break;
                default:
                    yield return message["uri"]?.ToString() switch
                    {
                        "ssap://system/getSystemInfo" => Reply("response", new() { ["modelName"] = "OLED55C1" }),
                        "ssap://com.webos.service.connectionmanager/getinfo" => Reply("response", new() { ["returnValue"] = true, ["wiredInfo"] = new JsonObject { ["macAddress"] = "A8:23:FE:01:02:03" } }),
                        "ssap://com.webos.service.networkinput/getPointerInputSocket" => Reply("response", new() { ["returnValue"] = true, ["socketPath"] = $"wss://127.0.0.1:{Port}/pointer" }),
                        "ssap://tv/channelUp" => new() { ["type"] = "error", ["id"] = id, ["error"] = "500 Application error" },
                        _ => Reply("response", new() { ["returnValue"] = true }),
                    };
                    break;
            }
        }

        public async ValueTask DisposeAsync()
        {
            stop.Cancel(); listener.Stop();
            Task[] running; lock (clients) running = [.. clients];
            await Task.WhenAll(running).WaitAsync(TimeSpan.FromSeconds(5)).ContinueWith(_ => { });
            certificate.Dispose();
        }
    }
}
