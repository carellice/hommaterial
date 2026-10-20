using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Hommaterial.Core;

public sealed record LoginAttempt(string DeviceId, string Frc, string MapMd, string Verifier, string Url);
public sealed record AlexaSession(string Cookie, string Csrf);

// Port of the existing Android client. These are the same unofficial Alexa endpoints.
public sealed class AlexaClient : IDisposable
{
    public const string UserAgent = "AmazonWebView/Amazon Alexa/2.2.651540.0/iOS/18.3.1/iPhone";
    const string AppVersion = "2.2.651540.0";
    const string DeviceType = "A2IVLV5VM2W81";
    readonly HttpClient http;
    readonly SemaphoreSlim sessionLock = new(1);
    readonly Func<string?> readToken;
    readonly Action<string> saveToken;
    AlexaSession? session;
    public Marketplace Market { get; }
    public bool IsLoggedIn => !string.IsNullOrEmpty(readToken());

    public AlexaClient(Marketplace market, Func<string?> readToken, Action<string> saveToken, HttpMessageHandler? handler = null)
    {
        Market = market;
        this.readToken = readToken;
        this.saveToken = saveToken;
        http = new(handler ?? new HttpClientHandler { UseCookies = false, AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(35) };
    }

    static string Base64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    public LoginAttempt NewLoginAttempt()
    {
        var device = Convert.ToHexString(Encoding.UTF8.GetBytes(Convert.ToHexString(RandomNumberGenerator.GetBytes(16)) + "#" + DeviceType)).ToLowerInvariant();
        var verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        var map = Convert.ToBase64String(Encoding.UTF8.GetBytes("{\"device_user_dictionary\":[],\"device_registration_data\":{\"software_version\":\"1\"},\"app_identifier\":{\"app_version\":\"" + AppVersion + "\",\"bundle_id\":\"com.amazon.echo\"}}"));
        var query = new Dictionary<string, string>
        {
            ["openid.return_to"] = "https://www.amazon.com/ap/maplanding", ["openid.assoc_handle"] = "amzn_dp_project_dee_ios",
            ["openid.identity"] = "http://specs.openid.net/auth/2.0/identifier_select", ["pageId"] = "amzn_dp_project_dee_ios",
            ["accountStatusPolicy"] = "P1", ["openid.claimed_id"] = "http://specs.openid.net/auth/2.0/identifier_select",
            ["openid.mode"] = "checkid_setup", ["openid.ns.oa2"] = "http://www.amazon.com/ap/ext/oauth/2",
            ["openid.oa2.client_id"] = "device:" + device, ["openid.ns.pape"] = "http://specs.openid.net/extensions/pape/1.0",
            ["openid.oa2.response_type"] = "code", ["openid.ns"] = "http://specs.openid.net/auth/2.0",
            ["openid.pape.max_auth_age"] = "0", ["openid.oa2.scope"] = "device_auth_access",
            ["openid.oa2.code_challenge_method"] = "S256", ["openid.oa2.code_challenge"] = Base64Url(SHA256.HashData(Encoding.UTF8.GetBytes(verifier))),
            ["language"] = Market.Locale.Replace('-', '_')
        };
        var url = "https://www.amazon.com/ap/signin?" + string.Join("&", query.Select(p => Uri.EscapeDataString(p.Key) + "=" + Uri.EscapeDataString(p.Value)));
        return new(device, Convert.ToBase64String(RandomNumberGenerator.GetBytes(313)), map, verifier, url);
    }

    public static string AuthorizationCode(string redirect)
    {
        if (!Uri.TryCreate(redirect, UriKind.Absolute, out var uri) || uri.Scheme != "https" || uri.Host != "www.amazon.com" || uri.AbsolutePath != "/ap/maplanding")
            throw new InvalidOperationException("Incolla l'indirizzo completo della pagina Amazon maplanding ottenuta dopo l'accesso.");
        foreach (var pair in uri.Query.TrimStart('?').Split('&'))
        {
            var parts = pair.Split('=', 2);
            if (parts.Length == 2 && Uri.UnescapeDataString(parts[0]) == "openid.oa2.authorization_code" && parts[1].Length > 0)
                return Uri.UnescapeDataString(parts[1].Replace('+', ' '));
        }
        throw new InvalidOperationException("L'indirizzo non contiene il codice di autorizzazione. Ripeti l'accesso.");
    }

    public async Task CompleteLoginAsync(LoginAttempt attempt, string redirect)
    {
        var cookies = new[] { new { Name = "frc", Value = attempt.Frc }, new { Name = "map-md", Value = attempt.MapMd } };
        var payload = new
        {
            requested_extensions = new[] { "device_info", "customer_info" },
            cookies = new { domain = ".amazon.com", website_cookies = cookies },
            registration_data = new { domain = "Device", app_version = AppVersion, device_type = DeviceType,
                device_name = "%FIRST_NAME%'s%DUPE_STRATEGY_1ST%Hommaterial", os_version = "18.3.1",
                device_serial = Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant(), device_model = "iPhone", app_name = "Hommaterial", software_version = "1" },
            auth_data = new { client_id = attempt.DeviceId, authorization_code = AuthorizationCode(redirect), code_verifier = attempt.Verifier, code_algorithm = "SHA-256", client_domain = "DeviceLegacy" },
            user_context_map = new { frc = attempt.Frc }, requested_token_type = new[] { "bearer", "mac_dms", "website_cookies" }
        };
        using var request = Request(HttpMethod.Post, "https://api.amazon.com/auth/register", payload);
        request.Headers.TryAddWithoutValidation("Cookie", $"frc={attempt.Frc}; map-md={attempt.MapMd}");
        request.Headers.TryAddWithoutValidation("x-amzn-identity-auth-domain", "api.amazon.com");
        var json = await SendAsync(request);
        var token = json["response"]?["success"]?["tokens"]?["bearer"]?["refresh_token"]?.ToString();
        if (string.IsNullOrEmpty(token)) throw new InvalidOperationException("Amazon ha rifiutato la registrazione. Ripeti l'accesso.");
        saveToken(token);
        session = null;
        await SessionAsync();
    }

    HttpRequestMessage Request(HttpMethod method, string url, object? body = null)
    {
        var request = new HttpRequestMessage(method, url);
        request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        request.Headers.TryAddWithoutValidation("Accept-Language", Market.Locale);
        request.Headers.TryAddWithoutValidation("Accept", "application/json");
        if (body is not null) request.Content = new StringContent(System.Text.Json.JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");
        return request;
    }

    async Task<JsonNode> SendAsync(HttpRequestMessage request)
    {
        using var response = await http.SendAsync(request);
        if (!response.IsSuccessStatusCode) throw new HttpRequestException($"Amazon ha risposto con errore {(int)response.StatusCode}.", null, response.StatusCode);
        var body = await response.Content.ReadAsStringAsync();
        try { return string.IsNullOrWhiteSpace(body) ? new JsonObject() : JsonNode.Parse(body) ?? new JsonObject(); }
        catch (System.Text.Json.JsonException) { throw new InvalidOperationException("Risposta inattesa da Amazon. Riprova più tardi."); }
    }

    async Task<AlexaSession> SessionAsync(bool force = false)
    {
        var previous = session;
        if (!force && session is not null) return session;
        await sessionLock.WaitAsync();
        try
        {
            if (session is not null && (!force || session != previous)) return session;
            var token = readToken() ?? throw new InvalidOperationException("Accedi al tuo account Amazon.");
            using var exchange = Request(HttpMethod.Post, $"https://www.{Market.Domain}/ap/exchangetoken/cookies");
            exchange.Headers.TryAddWithoutValidation("x-amzn-identity-auth-domain", "api." + Market.Domain);
            exchange.Content = new FormUrlEncodedContent(new Dictionary<string, string> {
                ["di.os.name"] = "iOS", ["app_version"] = AppVersion, ["domain"] = "." + Market.Domain,
                ["source_token"] = token, ["requested_token_type"] = "auth_cookies", ["source_token_type"] = "refresh_token",
                ["di.hw.version"] = "iPhone", ["di.sdk.version"] = "6.12.4", ["app_name"] = "Hommaterial", ["di.os.version"] = "16.6" });
            var json = await SendAsync(exchange);
            var cookies = json["response"]?["tokens"]?["cookies"]?["." + Market.Domain]?.AsArray()
                ?? throw new InvalidOperationException("Sessione Amazon scaduta o marketplace errato. Ripeti l'accesso.");
            var jar = new Dictionary<string, string>();
            foreach (var cookie in cookies)
                if (cookie?["Name"] is not null) jar[cookie["Name"]!.ToString()] = cookie["Value"]!.ToString();
            using var csrf = Request(HttpMethod.Get, $"https://{Market.Host}/api/language");
            csrf.Headers.TryAddWithoutValidation("Cookie", string.Join("; ", jar.Select(p => p.Key + "=" + p.Value)));
            csrf.Headers.TryAddWithoutValidation("Referer", $"https://{Market.Host}/spa/index.html");
            csrf.Headers.TryAddWithoutValidation("Origin", "https://" + Market.Host);
            using var response = await http.SendAsync(csrf);
            if (response.Headers.TryGetValues("Set-Cookie", out var headers))
                foreach (var header in headers)
                {
                    var pair = header.Split(';')[0].Split('=', 2);
                    if (pair.Length == 2) jar[pair[0].Trim()] = pair[1].Trim();
                }
            if (!jar.TryGetValue("csrf", out var csrfValue)) throw new InvalidOperationException("Amazon non ha fornito il cookie CSRF. Ripeti l'accesso.");
            return session = new(string.Join("; ", jar.Select(p => p.Key + "=" + p.Value)), csrfValue);
        }
        finally { sessionLock.Release(); }
    }

    async Task<JsonNode> CallAsync(HttpMethod method, string path, object? body = null)
    {
        var current = await SessionAsync();
        for (var attempt = 0; ; attempt++)
        {
            using var request = Request(method, "https://" + Market.Host + path, body);
            request.Headers.TryAddWithoutValidation("Cookie", current.Cookie);
            request.Headers.TryAddWithoutValidation("csrf", current.Csrf);
            request.Headers.TryAddWithoutValidation("Origin", "https://" + Market.Host);
            request.Headers.TryAddWithoutValidation("Referer", $"https://{Market.Host}/spa/index.html");
            try { return await SendAsync(request); }
            catch (HttpRequestException e) when (e.StatusCode == HttpStatusCode.Unauthorized && attempt == 0)
            { current = await SessionAsync(true); }
        }
    }

    public async Task<List<Device>> DevicesAsync()
    {
        var endpoints = CallAsync(HttpMethod.Post, "/nexus/v1/graphql", new { query = "query Endpoints { endpoints { items { friendlyName displayCategories { primary { value } } legacyAppliance { applianceId entityId isEnabled capabilities } } } }" });
        var groups = CallAsync(HttpMethod.Get, "/api/phoenix/group");
        await Task.WhenAll(endpoints, groups);
        return AlexaParser.Devices(await endpoints, await groups);
    }

    public async Task<Dictionary<string, DeviceState>> StatesAsync(IEnumerable<Device> devices)
    {
        var requests = devices.Select(d => new { entityId = d.ApplianceId, entityType = "APPLIANCE" }).ToArray();
        if (requests.Length == 0) return [];
        return AlexaParser.States(await CallAsync(HttpMethod.Post, "/api/phoenix/state", new { stateRequests = requests }));
    }

    public async Task ControlAsync(Device device, object parameters)
    {
        var response = await CallAsync(HttpMethod.Put, "/api/phoenix/state", new { controlRequests = new[] { new { entityId = device.EntityId, entityType = "APPLIANCE", parameters } } });
        if (response["errors"]?.AsArray().FirstOrDefault() is JsonNode error)
            throw new InvalidOperationException($"Comando non riuscito per {device.Name}: {error["code"]}.");
    }
    public async Task<string> DiagnosticsAsync(Device device)
    {
        var endpoints = await CallAsync(HttpMethod.Post, "/nexus/v1/graphql", new { query = "query Endpoints { endpoints { items { friendlyName displayCategories { primary { value } } legacyAppliance { applianceId entityId isEnabled capabilities } } } }" });
        var description = endpoints["data"]?["endpoints"]?["items"]?.AsArray().FirstOrDefault(d => d?["legacyAppliance"]?["applianceId"]?.ToString() == device.ApplianceId);
        var state = await CallAsync(HttpMethod.Post, "/api/phoenix/state", new { stateRequests = new[] { new { entityId = device.ApplianceId, entityType = "APPLIANCE" } } });
        return new JsonObject { ["device"] = description?.DeepClone(), ["state"] = state.DeepClone() }.ToJsonString(HomeData.JsonOptions);
    }
    public void Dispose() { http.Dispose(); sessionLock.Dispose(); }
}
