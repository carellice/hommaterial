using System.Diagnostics;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.Json;
using Hommaterial.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Hommaterial.Windows;

public sealed partial class MainWindow
{
    internal static string AppVersion => typeof(MainWindow).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()!.InformationalVersion;
    readonly HttpClient updateHttp = new() { Timeout = TimeSpan.FromMinutes(15) };
    readonly DispatcherTimer updateClock = new() { Interval = TimeSpan.FromHours(6) };
    bool updateChecking, updateInstalling;
    AppUpdate? availableUpdate;

    async Task CheckUpdatesAsync(bool interactive = false)
    {
        if (updateChecking || updateInstalling) return;
        updateChecking = true;
        try
        {
            var architecture = RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "arm64" : "x64";
            availableUpdate = await new GitHubUpdater(updateHttp).CheckAsync(AppVersion, architecture, lifetime.Token);
            if (availableUpdate is null)
            {
                if (interactive) Message("Hommaterial " + AppVersion + " è aggiornato.", InfoBarSeverity.Success);
                return;
            }
            if (interactive) await OfferUpdateAsync();
            else
            {
                Message("È disponibile Hommaterial " + availableUpdate.Version + ". Scaricalo dalle Impostazioni.", InfoBarSeverity.Success);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error) { if (interactive) ShowError(error); else LocalStore.Log(error); }
        finally { updateChecking = false; }
    }

    async Task OfferUpdateAsync()
    {
        if (availableUpdate is null || dialogOpen || busy || pending.Count > 0 || updateInstalling) return;
        var update = availableUpdate;
        var body = new StackPanel { Spacing = 12 };
        body.Children.Add(Text($"Versione installata: {AppVersion} · Nuova versione: {update.Version}"));
        body.Children.Add(Text($"Download da GitHub: {update.Size / 1048576d:F1} MB. L'app si chiude per sostituire i file e poi si riapre. Account e impostazioni restano su questo PC. Durante il riavvio timer e automazioni sono sospesi."));
        var bar = new ProgressBar { Minimum = 0, Maximum = 100, Value = 0 };
        var status = Text("Pronto per il download.");
        var cancel = new CancellationTokenSource();
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token, cancel.Token);
        var download = Button("Scarica e riavvia", () => { }, true);
        var stop = Button("Annulla download", () => cancel.Cancel()); stop.IsEnabled = false;
        body.Children.Add(bar); body.Children.Add(status); body.Children.Add(download); body.Children.Add(stop);
        download.Click += async (_, _) =>
        {
            if (updateInstalling) return;
            updateInstalling = true; download.IsEnabled = false; stop.IsEnabled = true;
            try
            {
                // Portable installations must be writable without elevation.
                var probe = Path.Combine(AppContext.BaseDirectory, ".hommaterial-write-" + Guid.NewGuid().ToString("N"));
                File.WriteAllText(probe, ""); File.Delete(probe);
                var work = Path.Combine(LocalStore.Folder, "updates", Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(work);
                var zip = Path.Combine(work, "update.zip");
                await new GitHubUpdater(updateHttp).DownloadAsync(update, zip,
                    new Progress<double>(p => { bar.Value = p * 100; status.Text = $"Download {p:P0}"; }), linked.Token);
                status.Text = "Verifica ed estrazione…"; stop.IsEnabled = false;
                var staged = Path.Combine(work, "app");
                await Task.Run(() => UpdateArchive.Extract(zip, staged), linked.Token);
                linked.Token.ThrowIfCancellationRequested();
                var info = FileVersionInfo.GetVersionInfo(Path.Combine(staged, "Hommaterial.Windows.dll"));
                if (GitHubUpdater.ParseVersion(info.ProductVersion ?? "") != GitHubUpdater.ParseVersion(update.Version))
                    throw new InvalidDataException("La versione nel pacchetto non corrisponde alla release.");
                var script = Path.Combine(work, "Install-Update.ps1");
                File.Copy(Path.Combine(AppContext.BaseDirectory, "Install-Update.ps1"), script);
                var job = Path.Combine(work, "job.json");
                File.WriteAllText(job, JsonSerializer.Serialize(new { ProcessId = Environment.ProcessId,
                    Target = Path.TrimEndingDirectorySeparator(AppContext.BaseDirectory), Staged = staged, Work = work }));
                Save();
                var start = new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), @"WindowsPowerShell\v1.0\powershell.exe"))
                    { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = work };
                foreach (var argument in new[] { "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script, "-JobFile", job }) start.ArgumentList.Add(argument);
                using var helper = Process.Start(start) ?? throw new IOException("Impossibile avviare l'aggiornamento.");
                closingAllowed = true;
                Application.Current.Exit();
            }
            catch (OperationCanceledException) { status.Text = "Download annullato. Chiudi questa finestra e riprova."; }
            catch (UnauthorizedAccessException) { status.Text = "La cartella dell'app non è scrivibile. Sposta tutta la cartella Hommaterial in una cartella personale e riprova."; }
            catch (Exception error) { status.Text = error.Message; LocalStore.Log(error); }
            finally { updateInstalling = false; stop.IsEnabled = false; if (!cancel.IsCancellationRequested) download.IsEnabled = true; }
        };
        try { await ShowPanelAsync("Aggiorna Hommaterial", body); }
        finally { if (updateInstalling) cancel.Cancel(); }
    }
}
