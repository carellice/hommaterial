using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Hommaterial.Core;

static class UpdateTests
{
    public static async Task Run(Action<bool, string> check)
    {
        JsonObject Release(string version, string architecture = "x64", bool prerelease = false)
        {
            var name = $"Hommaterial-v{version}-windows-{architecture}.zip";
            return new() { ["tag_name"] = "v" + version, ["draft"] = false, ["prerelease"] = prerelease,
                ["assets"] = new JsonArray(new JsonObject { ["name"] = name, ["size"] = 3L,
                    ["browser_download_url"] = $"https://github.com/carellice/hommaterial/releases/download/v{version}/{name}",
                    ["digest"] = "sha256:" + Convert.ToHexString(SHA256.HashData(new byte[] { 1, 2, 3 })) }) };
        }
        check(GitHubUpdater.ParseVersion("1.33") == GitHubUpdater.ParseVersion("v1.33.0.0") &&
            GitHubUpdater.ParseVersion("1.10") > GitHubUpdater.ParseVersion("1.9"), "versioni comuni Android/Windows e confronto numerico");
        var releases = new JsonArray(Release("1.34"), Release("1.35", prerelease: true), Release("1.36", "arm64"));
        check(GitHubUpdater.FindUpdate(releases, "1.33", "x64")?.Version == "1.34", "aggiornamenti: filtra prerelease e architettura");
        check(GitHubUpdater.FindUpdate(releases, "1.34.0", "x64") is null, "aggiornamenti: nessun downgrade o stessa versione");
        var androidOnly = Release("1.99"); androidOnly["assets"] = new JsonArray(); releases.Insert(0, androidOnly);
        check(GitHubUpdater.FindUpdate(releases, "1.33", "x64")?.Version == "1.34", "release solo Android non nasconde l'ultimo aggiornamento Windows");
        var bad = Release("1.40"); bad["assets"]![0]!["browser_download_url"] = "https://evil.test/update.zip";
        check(GitHubUpdater.FindUpdate(new JsonArray(bad), "1.33", "x64") is null, "aggiornamenti: rifiuta asset fuori dal repository");
        bad = Release("1.40"); bad["assets"]![0]!["digest"] = "sha256:abc";
        check(GitHubUpdater.FindUpdate(new JsonArray(bad), "1.33", "x64") is null, "aggiornamenti: richiede digest SHA-256 valido");
        var folder = Path.Combine(Path.GetTempPath(), "Hommaterial-UpdateTest-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        try
        {
            using var http = new HttpClient(new DownloadHandler());
            var update = GitHubUpdater.FindUpdate(new JsonArray(Release("1.34")), "1.33", "x64")!;
            var file = Path.Combine(folder, "download.zip");
            await new GitHubUpdater(http).DownloadAsync(update, file);
            check(File.ReadAllBytes(file).SequenceEqual(new byte[] { 1, 2, 3 }), "download verificato con dimensione e SHA-256");
            File.Delete(file);
            try { await new GitHubUpdater(http).DownloadAsync(update with { Sha256 = new string('0', 64) }, file); throw new Exception("Hash accettato"); }
            catch (InvalidDataException) { check(!File.Exists(file), "download manomesso eliminato senza installazione"); }
            try { await new GitHubUpdater(http).DownloadAsync(update with { Size = 2 }, file); throw new Exception("Dimensione accettata"); }
            catch (InvalidDataException) { check(!File.Exists(file), "download oltre la dimensione dichiarata rifiutato"); }
            var zipPath = Path.Combine(folder, "malicious.zip");
            using (var zip = ZipFile.Open(zipPath, ZipArchiveMode.Create)) zip.CreateEntry("../escape.txt");
            try { UpdateArchive.Extract(zipPath, Path.Combine(folder, "app")); throw new Exception("Traversal accettato"); }
            catch (InvalidDataException) { check(!File.Exists(Path.Combine(folder, "escape.txt")), "archivio con traversal rifiutato prima dell'estrazione"); }
            File.Delete(zipPath);
            using (var zip = ZipFile.Open(zipPath, ZipArchiveMode.Create)) zip.CreateEntry("other.txt");
            try { UpdateArchive.Extract(zipPath, Path.Combine(folder, "incomplete")); throw new Exception("Archivio incompleto accettato"); }
            catch (InvalidDataException) { check(true, "archivio privo dei file Windows necessari rifiutato"); }
        }
        finally { Directory.Delete(folder, true); }
    }

    sealed class DownloadHandler : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) =>
            Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(new byte[] { 1, 2, 3 }) });
    }
}
