using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Hommaterial.Core;

namespace Hommaterial.Windows;

// DPAPI binds the refresh token to the current Windows user. No plaintext credentials on disk.
internal sealed class LocalStore
{
    public static string Folder => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Hommaterial", "Windows");
    readonly string dataFolder;
    string DataPath => Path.Combine(dataFolder, "home.json");
    string TokenPath => Path.Combine(dataFolder, "session.bin");
    public LocalStore(string? dataFolder = null)
    {
        this.dataFolder = dataFolder ?? Folder;
        Directory.CreateDirectory(this.dataFolder);
    }
    public HomeData Load()
    {
        if (!File.Exists(DataPath)) return new();
        try { return JsonSerializer.Deserialize<HomeData>(File.ReadAllText(DataPath), HomeData.JsonOptions) ?? new(); }
        catch (JsonException e) { Log(e); return new(); }
    }
    public void Save(HomeData data)
    {
        var temporary = DataPath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(data, HomeData.JsonOptions));
        File.Move(temporary, DataPath, true);
    }
    public string? ReadToken()
    {
        if (!File.Exists(TokenPath)) return null;
        try { return Encoding.UTF8.GetString(Protect(File.ReadAllBytes(TokenPath), false)); }
        catch (System.ComponentModel.Win32Exception e) { Log(e); return null; }
    }
    public void SaveToken(string token) => File.WriteAllBytes(TokenPath, Protect(Encoding.UTF8.GetBytes(token), true));
    public SwitchBotKeys? ReadSwitchBot()
    {
        var path = Path.Combine(dataFolder, "switchbot.bin");
        if (!File.Exists(path)) return null;
        try { return JsonSerializer.Deserialize<SwitchBotKeys>(Protect(File.ReadAllBytes(path), false)); }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or JsonException) { Log(e); return null; }
    }
    public void SaveSwitchBot(SwitchBotKeys? keys)
    {
        var path = Path.Combine(dataFolder, "switchbot.bin");
        if (keys is null) File.Delete(path);
        else File.WriteAllBytes(path, Protect(JsonSerializer.SerializeToUtf8Bytes(keys), true));
    }
    // The paired LG TVs, with their keys: encrypted as the SwitchBot ones.
    public List<LgTv> ReadLgTvs()
    {
        var path = Path.Combine(dataFolder, "lgtv.bin");
        if (!File.Exists(path)) return [];
        try { return JsonSerializer.Deserialize<List<LgTv>>(Protect(File.ReadAllBytes(path), false), BackupCodec.PortableJson) ?? []; }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or JsonException) { Log(e); return []; }
    }
    public void SaveLgTvs(List<LgTv> tvs)
    {
        var path = Path.Combine(dataFolder, "lgtv.bin");
        if (tvs.Count == 0) File.Delete(path);
        else File.WriteAllBytes(path, Protect(JsonSerializer.SerializeToUtf8Bytes(tvs, BackupCodec.PortableJson), true));
    }
    public void Clear()
    {
        File.Delete(TokenPath);
        File.Delete(DataPath);
    }
    public static void Log(Exception error)
    {
        try { Directory.CreateDirectory(Folder); File.AppendAllText(Path.Combine(Folder, "errors.log"), $"{DateTimeOffset.Now:O} {error.GetType().Name} (0x{error.HResult:X8}): {error.Message}\n{error.StackTrace}\n"); }
        catch { /* Logging must not crash the app. Never log tokens, cookies or redirects. */ }
    }

    [StructLayout(LayoutKind.Sequential)] struct Blob { public int Length; public IntPtr Data; }
    [DllImport("crypt32.dll", SetLastError = true)] static extern bool CryptProtectData(ref Blob input, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("crypt32.dll", SetLastError = true)] static extern bool CryptUnprotectData(ref Blob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr memory);
    static byte[] Protect(byte[] value, bool encrypt)
    {
        var input = new Blob { Length = value.Length, Data = Marshal.AllocHGlobal(value.Length) };
        try
        {
            Marshal.Copy(value, 0, input.Data, value.Length);
            Blob output;
            var ok = encrypt ? CryptProtectData(ref input, null, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output)
                : CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output);
            if (!ok) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            try { var result = new byte[output.Length]; Marshal.Copy(output.Data, result, 0, output.Length); return result; }
            finally { LocalFree(output.Data); }
        }
        finally { Marshal.FreeHGlobal(input.Data); }
    }
}
