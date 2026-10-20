using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Hommaterial.Core;

public sealed record VoiceChoice(string Label, List<HomeAction> Actions, bool Certain, double Score);
public static class VoiceParser
{
    static readonly HashSet<string> On = ["accendi", "accendere", "accendimi", "accenda", "attiva", "attivare", "on"];
    static readonly HashSet<string> Off = ["spegni", "spegnere", "spegnimi", "spenga", "spengi", "disattiva", "disattivare", "off"];
    static readonly HashSet<string> Run = ["esegui", "avvia", "lancia", "fai", "run", "start", "launch"];
    static readonly HashSet<string> All = ["tutto", "tutta", "tutti", "tutte", "all", "everything", "every"];
    static readonly HashSet<string> Filler = ["il", "lo", "la", "i", "gli", "le", "l", "un", "uno", "una", "di", "del", "dello", "della", "dei", "degli", "delle", "in", "nel", "nello", "nella", "a", "al", "alla", "per", "favore", "piacere", "grazie", "ora", "adesso", "subito", "e", "turn", "switch", "the", "an", "of", "at", "to", "my", "please", "now", "thanks"];
    static string[] Words(string text) => Regex.Split(string.Concat(text.ToLowerInvariant().Normalize(NormalizationForm.FormD).Where(c => CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark)), "[^a-z0-9]+").Where(s => s.Length > 0).ToArray();
    static bool Alike(string a, string b) => a == b || a.Length >= 4 && a.Length == b.Length && a[..^1] == b[..^1];
    static double Score(string[] said, string[] name, string[] room)
    {
        if (said.Length == 0 || name.Length == 0) return 0;
        return (double)name.Count(w => said.Any(s => Alike(s, w))) / name.Length * said.Count(w => name.Concat(room).Any(s => Alike(s, w))) / said.Length;
    }
    public static List<VoiceChoice> Parse(string sentence, HomeData home)
    {
        var words = Words(sentence); var on = words.Any(On.Contains); var off = words.Any(Off.Contains);
        var said = words.Where(w => !On.Contains(w) && !Off.Contains(w) && !Run.Contains(w) && !All.Contains(w) && !Filler.Contains(w)).ToArray();
        var result = new List<VoiceChoice>();
        if (on || off)
        {
            foreach (var d in home.Devices.Where(d => d.HasPower && !home.Hidden.Contains(d.ApplianceId)))
            {
                var score = Score(said, Words(d.Name), Words(home.RoomOf(d) ?? ""));
                if (score >= .4) result.Add(new((on ? "Accendi " : "Spegni ") + d.Name, [new("power", d.ApplianceId, on ? "on" : "off")], score == 1, score));
            }
            foreach (var group in home.Devices.Where(d => d.HasPower && !home.Hidden.Contains(d.ApplianceId)).GroupBy(home.RoomOf).Where(g => g.Key is not null))
            {
                var score = Score(said, Words(group.Key!), []);
                if (score >= .4) result.Add(new((on ? "Accendi tutto in " : "Spegni tutto in ") + group.Key, group.Select(d => new HomeAction("power", d.ApplianceId, on ? "on" : "off")).ToList(), false, score));
            }
        }
        var ranked = result.OrderByDescending(r => r.Score).ThenBy(r => words.Any(All.Contains) ? r.Actions.Count == 1 : r.Actions.Count > 1).ToList();
        if (ranked.Count > 0)
        {
            if (ranked.Count(r => r.Score == 1) > 1) ranked = ranked.Select(r => r with { Certain = false }).ToList();
            return ranked.Take(3).ToList();
        }
        bool Named(string name)
        {
            var wanted = Words(name).Where(w => !Filler.Contains(w)).ToArray();
            return wanted.Length > 0 && wanted.Length == said.Length && wanted.All(w => said.Any(s => Alike(s, w)));
        }
        if (!off && (on || words.Any(Run.Contains)) && home.Macros.FirstOrDefault(m => Named(m.Name)) is HomeMacro macro)
            return [new("Esegui " + macro.Name, [new("macro", macro.Id)], true, 1)];
        if ((on || off) && home.Remotes.FirstOrDefault(r => Named(r.Name)) is RemoteDevice remote)
            return [new((on ? "Accendi " : "Spegni ") + remote.Name, [new("remote", remote.Id, on ? "turnOn" : "turnOff")], true, 1)];
        return [];
    }
}
