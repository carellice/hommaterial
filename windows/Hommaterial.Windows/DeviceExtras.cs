using Hommaterial.Core;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.ApplicationModel.DataTransfer;
using Windows.Foundation;

namespace Hommaterial.Windows;

public sealed partial class MainWindow
{
    void AddDeviceExtras(StackPanel content, Device device)
    {
        AddFavoriteControl(content, device.ApplianceId); AddPlaceControl(content, device.ApplianceId);
        if (device.IsSensor)
        {
            content.Children.Add(Text("Andamento del sensore", 18, true));
            var range = new ComboBox { Header = "Periodo", Items = { "Ultime 24 ore", "Ultimi 7 giorni" }, SelectedIndex = 0 };
            var metric = new ComboBox { Header = "Misura", Items = { "Temperatura", "Umidità" }, SelectedIndex = 0 };
            var chart = new Canvas { Height = 210, MinWidth = 310, HorizontalAlignment = HorizontalAlignment.Stretch,
                Background = new SolidColorBrush(ColorHelper.FromArgb(1, 0, 0, 0)) };
            var statistics = Text("", 12); var pointed = Text("Sposta il puntatore sul grafico per leggere i valori.", 12);
            List<(SensorReading Reading, double Value)> samples = [];
            void Draw()
            {
                chart.Children.Clear();
                var since = DateTimeOffset.Now.AddDays(range.SelectedIndex == 0 ? -1 : -7).ToUnixTimeMilliseconds();
                samples = (home.History.GetValueOrDefault(device.ApplianceId) ?? []).Where(r => r.At >= since && (metric.SelectedIndex == 0 || r.Humidity is not null))
                    .Select(r => (r, metric.SelectedIndex == 0 ? r.Temperature : (double)r.Humidity!)).ToList();
                if (samples.Count == 0) { chart.Children.Add(Text("I valori saranno raccolti a ogni aggiornamento.\nIl grafico parte da questo dispositivo.", 13)); statistics.Text = ""; return; }
                var unit = metric.SelectedIndex == 0 ? "°C" : "%"; var low = samples.Min(r => r.Value); var high = samples.Max(r => r.Value);
                statistics.Text = $"Min {low:F1} · Media {samples.Average(r => r.Value):F1} · Max {high:F1} {unit}";
                var width = Math.Max(310, chart.ActualWidth); var min = low - .5; var max = high + .5;
                for (var i = 0; i <= 3; i++)
                {
                    var y = 20 + i * 48; var line = new Line { X1 = 43, X2 = width - 8, Y1 = y, Y2 = y, Stroke = Muted, Opacity = .2, StrokeThickness = 1 }; chart.Children.Add(line);
                    var label = Text((max - (max - min) * i / 3).ToString("F1"), 10); Canvas.SetTop(label, y - 7); chart.Children.Add(label);
                }
                var path = new Polyline { Stroke = Accent, StrokeThickness = 2.5, StrokeLineJoin = PenLineJoin.Round };
                var from = samples[0].Reading.At; var span = Math.Max(1, samples[^1].Reading.At - from);
                foreach (var sample in samples)
                    path.Points.Add(new Point(46 + (sample.Reading.At - from) * (width - 60) / span, 20 + (max - sample.Value) * 144 / (max - min)));
                chart.Children.Add(path);
                foreach (var point in path.Points)
                { var dot = new Ellipse { Width = 6, Height = 6, Fill = Accent }; Canvas.SetLeft(dot, point.X - 3); Canvas.SetTop(dot, point.Y - 3); chart.Children.Add(dot); }
                var start = Text(DateTimeOffset.FromUnixTimeMilliseconds(from).ToLocalTime().ToString(range.SelectedIndex == 0 ? "HH:mm" : "dd/MM HH:mm"), 10); Canvas.SetLeft(start, 43); Canvas.SetTop(start, 180); chart.Children.Add(start);
                var end = Text(DateTimeOffset.FromUnixTimeMilliseconds(samples[^1].Reading.At).ToLocalTime().ToString(range.SelectedIndex == 0 ? "HH:mm" : "dd/MM HH:mm"), 10); Canvas.SetLeft(end, width - 90); Canvas.SetTop(end, 180); chart.Children.Add(end);
            }
            chart.PointerMoved += (_, e) =>
            {
                if (samples.Count == 0) return;
                var fraction = Math.Clamp((e.GetCurrentPoint(chart).Position.X - 46) / Math.Max(1, chart.ActualWidth - 60), 0, 1);
                var at = samples[0].Reading.At + fraction * (samples[^1].Reading.At - samples[0].Reading.At);
                var sample = samples.MinBy(s => Math.Abs(s.Reading.At - at));
                pointed.Text = $"{DateTimeOffset.FromUnixTimeMilliseconds(sample.Reading.At).ToLocalTime():dd/MM HH:mm} · {sample.Value:F1} {(metric.SelectedIndex == 0 ? "°C" : "%")}";
            };
            chart.SizeChanged += (_, _) => Draw(); range.SelectionChanged += (_, _) => Draw(); metric.SelectionChanged += (_, _) => Draw();
            content.Children.Add(range); content.Children.Add(metric); content.Children.Add(chart); content.Children.Add(statistics); content.Children.Add(pointed); Draw();
            var alert = home.Alerts.GetValueOrDefault(device.ApplianceId) ?? new();
            var above = new CheckBox { Content = "Avvisa se supera", IsChecked = alert.Above is not null };
            var aboveValue = new NumberBox { Header = "Soglia superiore (°C)", Value = alert.Above ?? 28, Minimum = -100, Maximum = 100 };
            var below = new CheckBox { Content = "Avvisa se scende sotto", IsChecked = alert.Below is not null };
            var belowValue = new NumberBox { Header = "Soglia inferiore (°C)", Value = alert.Below ?? 16, Minimum = -100, Maximum = 100 };
            content.Children.Add(Text("Avvisi di temperatura", 18, true)); content.Children.Add(above); content.Children.Add(aboveValue); content.Children.Add(below); content.Children.Add(belowValue);
            content.Children.Add(Button("Salva avvisi", () =>
            {
                if (above.IsChecked == true && !double.IsFinite(aboveValue.Value) || below.IsChecked == true && !double.IsFinite(belowValue.Value)) { Message("Inserisci soglie valide."); return; }
                home.Alerts[device.ApplianceId] = new(above.IsChecked == true ? aboveValue.Value : null, below.IsChecked == true ? belowValue.Value : null);
                home.Tripped.Remove(device.ApplianceId + "|above"); home.Tripped.Remove(device.ApplianceId + "|below"); Save(); Message("Avvisi salvati.");
            }));
            if (demo)
            {
                var temperature = new NumberBox { Header = "Simula una temperatura (demo)", Minimum = -30, Maximum = 60, Value = home.States.GetValueOrDefault(device.ApplianceId)?.Temperature ?? 22 };
                content.Children.Add(temperature);
                content.Children.Add(AsyncButton("Aggiorna sensore demo", async () =>
                {
                    if (!double.IsFinite(temperature.Value)) return;
                    var state = (home.States.GetValueOrDefault(device.ApplianceId) ?? new()) with { Temperature = temperature.Value };
                    home.States[device.ApplianceId] = state;
                    await runtime.ReceiveAsync(new Dictionary<string, DeviceState> { [device.ApplianceId] = state }, DateTimeOffset.Now.ToUnixTimeMilliseconds()); Draw();
                    Message("Sensore demo aggiornato.");
                }));
            }
        }
        content.Children.Add(AsyncButton("Copia diagnostica", async () =>
        {
            var json = demo ? System.Text.Json.JsonSerializer.Serialize(new { device, state = home.States.GetValueOrDefault(device.ApplianceId) }, HomeData.JsonOptions) : await client.DiagnosticsAsync(device);
            var package = new DataPackage(); package.SetText(json); Clipboard.SetContent(package); Message("Diagnostica copiata.");
        }));
    }
}
