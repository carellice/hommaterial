using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Hommaterial.Core;

var passed = 0;
void Check(bool ok, string name) { if (!ok) throw new Exception("FAIL: " + name); passed++; Console.WriteLine("PASS: " + name); }
var endpoints = JsonNode.Parse("""
{"data":{"endpoints":{"items":[
{"friendlyName":"Lampada","displayCategories":{"primary":{"value":"LIGHT"}},"legacyAppliance":{"applianceId":"appliance-light","entityId":"entity-light","capabilities":[{"interfaceName":"Alexa.PowerController"},{"interfaceName":"Alexa.BrightnessController"}]}},
{"friendlyName":"Echo","displayCategories":{"primary":{"value":"ALEXA_VOICE_ENABLED"}},"legacyAppliance":{"applianceId":"echo","capabilities":[{"interfaceName":"Alexa.PowerController"}]}},
{"friendlyName":"Disabilitata","legacyAppliance":{"applianceId":"disabled","isEnabled":false,"capabilities":[{"interfaceName":"Alexa.PowerController"}]}},
{"friendlyName":"Sensore","legacyAppliance":{"applianceId":"sensor","entityId":"entity-sensor","capabilities":[{"interfaceName":"Alexa.TemperatureSensor"}]}}
]}}}
""")!;
var groups = JsonNode.Parse("""{"applianceGroups":[{"name":"Salotto","applianceIds":["appliance-light"]},{"name":"Secondo gruppo","applianceIds":["appliance-light"]}]}""")!;
var devices = AlexaParser.Devices(endpoints, groups);
Check(devices.Count == 2 && devices.All(d => d.Name != "Echo"), "esclude Echo e dispositivi disabilitati, conserva sensori");
var light = devices.Single(d => d.Name == "Lampada");
Check(light.Room == "Salotto" && light.HasBrightness && light.EntityId == "entity-light", "prima stanza Alexa e capacità della luce");
var states = AlexaParser.States(JsonNode.Parse("""
{"deviceStates":[{"entity":{"entityId":"appliance-light"},"capabilityStates":["{\"name\":\"powerState\",\"value\":\"ON\"}","{\"name\":\"brightness\",\"value\":65}","invalid"]},
{"entity":{"entityId":"sensor"},"capabilityStates":[{"name":"temperature","value":{"value":22.4}},{"name":"relativeHumidity","value":48}]}],"errors":[{"entity":{"entityId":"offline"}}]}
""")!);
Check(states["appliance-light"].Power == true && states["appliance-light"].Brightness == 65, "stati JSON codificati e capacità malformate");
Check(states["sensor"].Temperature == 22.4 && states["sensor"].Humidity == 48 && !states["offline"].Reachable, "sensori e dispositivi non raggiungibili");
Check(AlexaClient.AuthorizationCode("https://www.amazon.com/ap/maplanding?openid.oa2.authorization_code=test%2Bcode") == "test+code", "decodifica redirect Amazon");
foreach (var bad in new[] { "https://evil.test/ap/maplanding?openid.oa2.authorization_code=x", "http://www.amazon.com/ap/maplanding?openid.oa2.authorization_code=x", "https://www.amazon.com/ap/maplanding", "secret" })
{
    try { AlexaClient.AuthorizationCode(bad); throw new Exception("Redirect accettato: " + bad); }
    catch (InvalidOperationException) { passed++; }
}
Console.WriteLine("PASS: rifiuta redirect non attendibili o senza codice (4 casi)");
string? token = "test-refresh";
var exchangeCount = 0;
var controlCount = 0;
var stateCount = 0;
var rejectCommand = false;
using var client = new AlexaClient(Marketplace.All[0], () => token, value => token = value, new FakeHandler(async request =>
{
    var path = request.RequestUri!.AbsolutePath;
    var body = request.Content is null ? "" : await request.Content.ReadAsStringAsync();
    if (path == "/ap/exchangetoken/cookies")
    {
        exchangeCount++;
        if (!body.Contains("source_token=test-refresh")) throw new Exception("Refresh token non inviato");
        return FakeHandler.Json("""{"response":{"tokens":{"cookies":{".amazon.it":[{"Name":"session-id","Value":"test-cookie"}]}}}}""");
    }
    if (path == "/api/language")
    {
        var response = FakeHandler.Json("{}"); response.Headers.Add("Set-Cookie", "csrf=test-csrf; Secure; Path=/"); return response;
    }
    if (request.Headers.GetValues("csrf").Single() != "test-csrf") throw new Exception("CSRF mancante");
    if (path == "/api/phoenix/state" && request.Method == HttpMethod.Post)
    {
        stateCount++;
        if (stateCount == 1) return new(HttpStatusCode.Unauthorized);
        if (JsonNode.Parse(body)?["stateRequests"]?[0]?["entityId"]?.ToString() != "appliance-light") throw new Exception("Query stato usa ID errato");
        return FakeHandler.Json("""{"deviceStates":[]}""");
    }
    if (path == "/api/phoenix/state" && request.Method == HttpMethod.Put)
    {
        controlCount++;
        var command = JsonNode.Parse(body)?["controlRequests"]?[0];
        if (command?["entityId"]?.ToString() != "entity-light" || command["parameters"]?["action"]?.ToString() != "turnOn") throw new Exception("Comando usa ID o azione errati");
        return FakeHandler.Json(rejectCommand ? """{"errors":[{"code":"ENDPOINT_UNREACHABLE"}]}""" : "{}");
    }
    throw new Exception("Richiesta inattesa: " + path);
}));
var login = client.NewLoginAttempt();
var login2 = client.NewLoginAttempt();
var challenge = Convert.ToBase64String(SHA256.HashData(Encoding.UTF8.GetBytes(login.Verifier))).TrimEnd('=').Replace('+', '-').Replace('/', '_');
Check(login.Url.Contains(Uri.EscapeDataString(challenge)) && login.DeviceId != login2.DeviceId && login.Verifier != login2.Verifier, "PKCE S256 e valori casuali distinti a ogni accesso");
await client.StatesAsync([light]);
Check(exchangeCount == 2 && stateCount == 2, "rinnova la sessione una sola volta dopo HTTP 401");
await client.ControlAsync(light, new { action = "turnOn" });
Check(controlCount == 1 && exchangeCount == 2, "comando usa entityId, lettura usa applianceId e sessione riutilizzata");
rejectCommand = true;
try { await client.ControlAsync(light, new { action = "turnOn" }); throw new Exception("Errore comando ignorato"); }
catch (InvalidOperationException e) { Check(e.Message.Contains("ENDPOINT_UNREACHABLE"), "propaga il rifiuto del dispositivo senza fingere successo"); }
var demo = DemoHome.Create();
demo.States["demo-1"] = new(false);
Check(DemoHome.Create().States["demo-1"].Power == true && demo.Devices.All(d => d.ApplianceId.StartsWith("demo-")), "demo isolata, senza identificativi reali");
if (OperatingSystem.IsWindows())
{
    var folder = Path.Combine(AppContext.BaseDirectory, "Hommaterial.Tests-" + Guid.NewGuid());
    var store = new Hommaterial.Windows.LocalStore(folder);
    try
    {
        store.SaveToken("local-test-token");
        Check(store.ReadToken() == "local-test-token" && !Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(folder, "session.bin"))).Contains("local-test-token"), "DPAPI: token decifrabile per l'utente corrente e mai in chiaro sul disco");
        store.Save(demo);
        Check(store.Load().Favorites.SetEquals(demo.Favorites) && store.Load().Devices.Count == demo.Devices.Count, "cache e preferiti sopravvivono al caricamento");
        store.SaveLgTvs([new("lg:1", "Sala", "192.168.1.20", "lg-test-key", "pin", ["a8:23:fe:01:02:03"], new() { ["netflix"] = "Netflix" })]);
        Check(store.ReadLgTvs() is [{ Key: "lg-test-key", Apps.Count: 1 }] && !Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(folder, "lgtv.bin"))).Contains("lg-test-key"), "DPAPI: TV LG abbinati cifrati sul disco");
        store.SaveLgTvs([]);
        store.Clear();
        Check(store.ReadToken() is null && store.Load().Devices.Count == 0, "disconnessione cancella token e dati dell'account");
    }
    finally
    {
        foreach (var name in new[] { "session.bin", "home.json", "home.json.tmp", "lgtv.bin" }) File.Delete(Path.Combine(folder, name));
        Directory.Delete(folder);
    }
}
await FeatureTests.Run(Check);
await LgTvTests.Run(Check);
await UpdateTests.Run(Check);
Console.WriteLine($"\n{passed} verifiche superate.");

sealed class FakeHandler(Func<HttpRequestMessage, Task<HttpResponseMessage>> handle) : HttpMessageHandler
{
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => handle(request);
    public static HttpResponseMessage Json(string value) => new(HttpStatusCode.OK) { Content = new StringContent(value, Encoding.UTF8, "application/json") };
}
