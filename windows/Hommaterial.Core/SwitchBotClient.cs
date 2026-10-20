using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Hommaterial.Core;

public sealed class SwitchBotClient(Func<SwitchBotKeys?> readKeys, HttpMessageHandler? handler = null) : IDisposable
{
    readonly HttpClient http = new(handler ?? new HttpClientHandler()) { Timeout = TimeSpan.FromSeconds(30) };
    public static string Sign(string token, string secret, string time, string nonce) =>
        Convert.ToBase64String(HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), Encoding.UTF8.GetBytes(token + time + nonce))).ToUpperInvariant();
    async Task<JsonNode> CallAsync(HttpMethod method, string path, object? body = null, CancellationToken cancellation = default)
    {
        var keys = readKeys() ?? throw new InvalidOperationException("Collega prima l'account SwitchBot nelle Impostazioni.");
        var time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(System.Globalization.CultureInfo.InvariantCulture);
        var nonce = Guid.NewGuid().ToString();
        using var request = new HttpRequestMessage(method, "https://api.switch-bot.com/v1.1" + path);
        request.Headers.TryAddWithoutValidation("Authorization", keys.Token);
        request.Headers.TryAddWithoutValidation("sign", Sign(keys.Token, keys.Secret, time, nonce));
        request.Headers.TryAddWithoutValidation("t", time); request.Headers.TryAddWithoutValidation("nonce", nonce);
        if (body is not null) request.Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");
        using var response = await http.SendAsync(request, cancellation);
        if (!response.IsSuccessStatusCode) throw new HttpRequestException($"SwitchBot ha risposto con errore {(int)response.StatusCode}.");
        var json = JsonNode.Parse(await response.Content.ReadAsStringAsync(cancellation)) ?? throw new InvalidOperationException("Risposta SwitchBot non valida.");
        if (json["statusCode"]?.GetValue<int>() != 100) throw new InvalidOperationException("SwitchBot: " + json["message"]?.ToString());
        return json["body"] ?? new JsonObject();
    }
    public async Task<List<RemoteDevice>> RemotesAsync()
    {
        var json = await CallAsync(HttpMethod.Get, "/devices");
        return (json["infraredRemoteList"]?.AsArray() ?? []).Select(r => new RemoteDevice(r!["deviceId"]!.ToString(), r["deviceName"]?.ToString() ?? "", r["remoteType"]?.ToString() ?? "")).ToList();
    }
    public Task SendAsync(string id, string command, string parameter = "default", bool custom = false, CancellationToken cancellation = default) =>
        CallAsync(HttpMethod.Post, "/devices/" + Uri.EscapeDataString(id) + "/commands", new { command, parameter, commandType = custom ? "customize" : "command" }, cancellation);
    public void Dispose() => http.Dispose();
}
