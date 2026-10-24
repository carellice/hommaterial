namespace Hommaterial.Core;

public sealed record BackupSection(string Id, string Title, params string[] Fields);

public static class BackupSections
{
    public static readonly BackupSection[] All = [
        new("macros", "Dispositivi personalizzati", "macros"),
        new("groups", "Gruppi", "groups"),
        new("rooms", "Stanze e assegnazioni", "rooms", "roomList"),
        new("favorites", "Preferiti", "favorites"),
        new("hidden", "Dispositivi nascosti", "hidden", "hiddenRemotes"),
        new("tiles", "Ordine e larghezza riquadri", "order", "tileSpans"),
        new("remotes", "Telecomandi e TV LG", "remoteButtons", "lgTvs"),
        new("schedules", "Azioni programmate", "schedules"),
        new("automations", "Automazioni", "automations"),
        new("alerts", "Avvisi dei sensori", "alerts"),
        new("shortcuts", "Widget e collegamenti Android", "shortcuts", "widget", "tiles"),
        new("settings", "Aspetto e impostazioni", "autoUpdate", "theme", "layout", "language", "haptics", "hapticLevel"),
    ];
}

public static class TileLayout
{
    public static int Span(IReadOnlyDictionary<string, int> spans, string key, int columns) =>
        Math.Clamp(spans.GetValueOrDefault(key, 1), 1, Math.Max(1, Math.Min(2, columns)));

    public static List<(int Row, int Column, int Span)> Arrange(IEnumerable<int> spans, int columns)
    {
        columns = Math.Max(1, columns);
        var result = new List<(int Row, int Column, int Span)>();
        int row = 0, column = 0;
        foreach (var requested in spans)
        {
            var span = Math.Clamp(requested, 1, Math.Min(2, columns));
            if (column + span > columns) { row++; column = 0; }
            result.Add((row, column, span)); column += span;
        }
        return result;
    }
}
