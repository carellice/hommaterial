using System.IO.Compression;

namespace Hommaterial.Core;

public static class UpdateArchive
{
    public static void Extract(string archive, string destination)
    {
        using var zip = ZipFile.OpenRead(archive);
        long size = 0;
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var entry in zip.Entries)
        {
            var name = entry.FullName.Replace('\\', '/');
            var parts = name.TrimEnd('/').Split('/');
            if (parts.Any(p => p is "" or "." or ".." || p.Contains(':') || p.EndsWith('.') || p.EndsWith(' ')) ||
                name.StartsWith('/') || !names.Add(name.TrimEnd('/')) || ((entry.ExternalAttributes >> 16) & 0xF000) == 0xA000)
                throw new InvalidDataException("Percorso non valido nel pacchetto di aggiornamento.");
            size += entry.Length;
            if (size > 2_000_000_000 || zip.Entries.Count > 20000) throw new InvalidDataException("Pacchetto di aggiornamento troppo grande.");
        }
        zip.ExtractToDirectory(destination);
        foreach (var required in new[] { "Hommaterial.Windows.exe", "Hommaterial.Windows.dll", "Hommaterial.Windows.pri", "Hommaterial.Windows.deps.json", "Assets/App.ico" })
            if (!File.Exists(Path.Combine(destination, required))) throw new InvalidDataException("Pacchetto Windows incompleto: " + required);
    }
}
