using System.Net;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Hommaterial.Core;

public sealed record AppUpdate(string Version, string Name, Uri Download, long Size, string Sha256);

/// <summary>Only stable, architecture-specific archives from this repository are accepted.</summary>
public sealed class GitHubUpdater(HttpClient http)
{
    public const string Repository = "carellice/hommaterial";
    public const string ReleasesUrl = "https://api.github.com/repos/" + Repository + "/releases?per_page=100";
    public static Version ParseVersion(string value)
    {
        value = value.TrimStart('v');
        if (!Regex.IsMatch(value, @"^\d+\.\d+(\.\d+){0,2}$") || !Version.TryParse(value, out var parsed))
            throw new InvalidDataException("Versione della release non valida.");
        return new(parsed.Major, parsed.Minor, Math.Max(0, parsed.Build), Math.Max(0, parsed.Revision));
    }

    public static AppUpdate? FindUpdate(JsonArray releases, string current, string architecture)
    {
        if (architecture is not ("x64" or "arm64")) throw new ArgumentException("Architettura non supportata.");
        var installed = ParseVersion(current);
        var candidates = new List<AppUpdate>();
        foreach (var release in releases.OfType<JsonObject>())
        {
            if (release["draft"]?.GetValue<bool>() == true || release["prerelease"]?.GetValue<bool>() == true) continue;
            var tag = release["tag_name"]?.GetValue<string>() ?? "";
            Version version;
            try { version = ParseVersion(tag); } catch (InvalidDataException) { continue; }
            if (version <= installed) continue;
            var name = $"Hommaterial-v{tag.TrimStart('v')}-windows-{architecture}.zip";
            var asset = (release["assets"] as JsonArray)?.OfType<JsonObject>().FirstOrDefault(a => a["name"]?.GetValue<string>() == name);
            if (asset is null) continue; // Android-only releases remain usable by Android.
            var url = asset["browser_download_url"]?.GetValue<string>() ?? "";
            var expected = $"https://github.com/{Repository}/releases/download/{tag}/{name}";
            var digest = asset["digest"]?.GetValue<string>() ?? "";
            if (url != expected || !Regex.IsMatch(digest, "^sha256:[a-fA-F0-9]{64}$")) continue;
            var size = asset["size"]?.GetValue<long>() ?? 0;
            if (size <= 0 || size > 1_000_000_000) continue;
            candidates.Add(new(tag.TrimStart('v'), name, new Uri(url), size, digest[7..]));
        }
        return candidates.OrderByDescending(c => ParseVersion(c.Version)).FirstOrDefault();
    }

    public async Task<AppUpdate?> CheckAsync(string current, string architecture, CancellationToken ct = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, ReleasesUrl);
        request.Headers.UserAgent.ParseAdd("Hommaterial/" + current);
        request.Headers.Accept.ParseAdd("application/vnd.github+json");
        using var response = await http.SendAsync(request, ct);
        if (response.StatusCode == HttpStatusCode.NotFound) return null;
        if ((int)response.StatusCode is 403 or 429) throw new HttpRequestException("Limite GitHub raggiunto. Riprova più tardi.");
        response.EnsureSuccessStatusCode();
        var releases = JsonNode.Parse(await response.Content.ReadAsStringAsync(ct)) as JsonArray
            ?? throw new InvalidDataException("Risposta GitHub non valida.");
        return FindUpdate(releases, current, architecture);
    }

    public async Task DownloadAsync(AppUpdate update, string destination, IProgress<double>? progress = null, CancellationToken ct = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, update.Download);
        request.Headers.UserAgent.ParseAdd("Hommaterial-Updater");
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();
        try
        {
            await using var source = await response.Content.ReadAsStreamAsync(ct);
            await using var target = new FileStream(destination, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true);
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var buffer = new byte[65536];
            long received = 0;
            int count;
            while ((count = await source.ReadAsync(buffer, ct)) > 0)
            {
                received += count;
                if (received > update.Size) throw new InvalidDataException("Dimensione del download non valida.");
                hash.AppendData(buffer, 0, count);
                await target.WriteAsync(buffer.AsMemory(0, count), ct);
                progress?.Report((double)received / update.Size);
            }
            if (received != update.Size || !Convert.ToHexString(hash.GetHashAndReset()).Equals(update.Sha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Il download non corrisponde all'impronta SHA-256 pubblicata da GitHub. Aggiornamento annullato.");
        }
        catch { File.Delete(destination); throw; }
    }
}
