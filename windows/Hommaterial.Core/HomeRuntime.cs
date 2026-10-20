namespace Hommaterial.Core;

// One shared execution path for timers, schedules, automations and manual macros.
public sealed class HomeRuntime(Func<HomeData> data, Func<HomeAction, CancellationToken, Task> execute,
    Action save, Action<string, string> notify)
{
    bool ticking;
    public void Prime(AutomationRule rule)
    {
        var home = data(); var key = "automation:" + rule.Id;
        if (rule.Holds(home.States.GetValueOrDefault(rule.Device) ?? new()) == true) home.Tripped.Add(key);
        else home.Tripped.Remove(key);
    }
    public async Task TickAsync(long now, CancellationToken cancellation = default)
    {
        if (ticking) return;
        ticking = true;
        try
        {
            var home = data();
            foreach (var schedule in home.Schedules.Where(s => s.Enabled && s.At <= now && (s.RetryAt is null || s.RetryAt <= now)).ToList())
            {
                if (!ReferenceEquals(home, data())) break;
                var expired = now - schedule.At > 10 * 60_000;
                var completed = expired;
                if (expired) notify("Azione scaduta", schedule.Label + ": il PC non era disponibile all'orario previsto.");
                else
                {
                    try
                    {
                        await RunActionAsync(schedule.Action, cancellation);
                        completed = true; notify(schedule.IsTimer ? "Timer eseguito" : "Azione programmata eseguita", schedule.Label);
                    }
                    catch (Exception e) when (e is not OperationCanceledException)
                    {
                        if ((e is HttpRequestException or TimeoutException) && now - schedule.At < 9 * 60_000)
                        { schedule.RetryAt = now + 60_000; save(); continue; }
                        completed = true; notify("Azione non riuscita", schedule.Label + ": " + e.Message);
                    }
                }
                if (completed)
                {
                    if (schedule.Days.Count == 0) home.Schedules.Remove(schedule);
                    else { schedule.At = ScheduleClock.Next(schedule.Hour, schedule.Minute, schedule.Days, now); schedule.RetryAt = null; }
                    save();
                }
            }
        }
        finally { ticking = false; }
    }
    public async Task ReceiveAsync(IReadOnlyDictionary<string, DeviceState> states, long now, CancellationToken cancellation = default)
    {
        var home = data();
        var due = new List<AutomationRule>();
        foreach (var rule in home.Automations.Where(a => a.Enabled))
        {
            if (!states.TryGetValue(rule.Device, out var state)) continue;
            var key = "automation:" + rule.Id;
            if (rule.Holds(state) == true && home.Tripped.Add(key)) due.Add(rule);
            else if (rule.Cleared(state) == true) home.Tripped.Remove(key);
        }
        foreach (var (id, state) in states)
        {
            if (!state.Reachable || state.Temperature is not double t || !double.IsFinite(t)) continue;
            if (!home.History.TryGetValue(id, out var readings)) home.History[id] = readings = [];
            readings.RemoveAll(r => now - r.At > 7L * 24 * 60 * 60_000);
            if (readings.Count == 0 || now - readings[^1].At >= 10 * 60_000) readings.Add(new(now, t, state.Humidity));
            if (!home.Alerts.TryGetValue(id, out var alert)) continue;
            var name = home.Devices.FirstOrDefault(d => d.ApplianceId == id)?.Name ?? id;
            void Check(string side, bool holds, bool cleared, double threshold)
            {
                var key = id + "|" + side;
                if (holds && home.Tripped.Add(key)) notify($"{name}: {t:F1} °C", $"Temperatura {(side == "above" ? "sopra" : "sotto")} {threshold:F1} °C.");
                else if (cleared) home.Tripped.Remove(key);
            }
            if (alert.Above is double above) Check("above", t > above, t <= above - .3, above);
            if (alert.Below is double below) Check("below", t < below, t >= below + .3, below);
        }
        // Persist edge latches before awaiting so concurrent polling cannot duplicate an automation.
        save();
        foreach (var rule in due)
        {
            if (!ReferenceEquals(home, data())) break;
            try { await RunActionAsync(rule.Action, cancellation); notify("Automazione eseguita", rule.Title + " → " + rule.Label); }
            catch (Exception e) when (e is not OperationCanceledException) { notify("Automazione non riuscita", rule.Label + ": " + e.Message); }
        }
    }
    public async Task RunActionAsync(HomeAction action, CancellationToken cancellation = default)
    {
        BackupCodec.ValidateAction(action);
        if (action.Type == "wait") { await Task.Delay(TimeSpan.FromSeconds(int.Parse(action.Value)), cancellation); return; }
        if (action.Type != "macro") { await execute(action, cancellation); return; }
        var home = data();
        var macro = home.Macros.FirstOrDefault(m => m.Id == action.Target) ?? throw new InvalidOperationException("Dispositivo personalizzato non trovato.");
        List<HomeAction> steps;
        if (macro.Condition is string id)
        {
            var state = home.States.GetValueOrDefault(id);
            if (state is null || !state.Reachable || state.Power is null) throw new InvalidOperationException("Aggiorna prima lo stato del dispositivo da cui dipende questa azione.");
            steps = state.Power == true ? macro.Steps ?? [] : macro.Otherwise ?? [];
        }
        else steps = macro.Steps ?? [];
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(TimeSpan.FromMinutes(5));
        foreach (var step in steps)
        {
            timeout.Token.ThrowIfCancellationRequested();
            if (!ReferenceEquals(home, data())) throw new OperationCanceledException();
            BackupCodec.ValidateAction(step, false);
            if (step.Type == "wait") await Task.Delay(TimeSpan.FromSeconds(int.Parse(step.Value)), timeout.Token);
            else await execute(step, timeout.Token);
        }
    }
}
