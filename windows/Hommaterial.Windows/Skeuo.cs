using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Foundation;
using Windows.UI;

namespace Hommaterial.Windows;

/// <summary>
/// The skeuomorphic look, chosen in Settings → Appearance → Style, as on Android and on the web:
/// linen or leather under the window, ivory keys, cards lit as blue glass, a brushed bar on top and
/// the controls of WinUI restyled through their own resources. Off, nothing here is applied.
/// </summary>
internal static class Skeuo
{
    public static bool On { get; private set; }
    /// <summary>Whether the window shows the dark theme, for the pieces built away from it.</summary>
    public static bool Dark { get; private set; }
    /// <summary>True while [Refresh] flips the theme: the window must not apply the look again then.</summary>
    public static bool Refreshing { get; private set; }

    static readonly Dictionary<bool, ImageBrush> textures = [];

    static Color C(uint argb) => ColorHelper.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb);

    static Color Mix(Color a, Color b, double t) => ColorHelper.FromArgb(255,
        (byte)(a.R + (b.R - a.R) * t), (byte)(a.G + (b.G - a.G) * t), (byte)(a.B + (b.B - a.B) * t));

    static LinearGradientBrush Vertical(params (double At, Color Color)[] stops)
    {
        var brush = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(0, 1) };
        foreach (var (at, color) in stops) brush.GradientStops.Add(new GradientStop { Offset = at, Color = color });
        return brush;
    }

    static LinearGradientBrush Key(Color top, Color upper, Color lower, Color bottom) => Vertical((0, top), (0.5, upper), (0.51, lower), (1, bottom));

    static Color Tint(bool dark) => dark ? Mix(AppPalette.LogoBlue, AppPalette.LogoLightBlue, 0.25) : AppPalette.LogoBlue;

    public static Brush Raised(bool dark) => dark ? Key(C(0xFF57514A), C(0xFF48423B), C(0xFF3E3832), C(0xFF34302A)) : Key(C(0xFFFFFFFF), C(0xFFF6F2EA), C(0xFFECE6DA), C(0xFFE2DBCD));
    public static Brush Hover(bool dark) => dark ? Key(C(0xFF615A52), C(0xFF524B43), C(0xFF48423B), C(0xFF3E3832)) : Key(C(0xFFFFFFFF), C(0xFFFBF8F2), C(0xFFF3EEE4), C(0xFFEAE4D8));
    public static Brush Pressed(bool dark) => dark ? Vertical((0, C(0xFF2A2621)), (1, C(0xFF36312B))) : Vertical((0, C(0xFFD9D2C3)), (1, C(0xFFE8E2D6)));
    public static Brush Panel(bool dark) => dark ? Vertical((0, C(0xFF403A33)), (1, C(0xFF35302A))) : Vertical((0, C(0xFFFFFEFB)), (1, C(0xFFF5F1E8)));
    public static Brush Edge(bool dark) => new SolidColorBrush(dark ? C(0xB3000000) : C(0x52463419));
    public static Brush Ink(bool dark) => new SolidColorBrush(dark ? C(0xFFEFE8DC) : C(0xFF2E2820));
    public static Brush Engraved(bool dark) => new SolidColorBrush(dark ? C(0xFFA1978A) : C(0xFF8B8274));

    /// <summary>The accent as lit glass, with the gloss on its upper half.</summary>
    public static Brush Gel(bool dark, double light = 0)
    {
        var tint = Mix(Tint(dark), Colors.White, light);
        return Key(Mix(tint, Colors.White, 0.4), Mix(tint, Colors.White, 0.12), tint, Mix(tint, Colors.Black, 0.22));
    }
    public static Brush GelPressed(bool dark) => Vertical((0, Mix(Tint(dark), Colors.Black, 0.3)), (1, Tint(dark)));
    public static Brush GelEdge(bool dark) => new SolidColorBrush(Mix(Tint(dark), Colors.Black, 0.5));

    static Color Metal(bool dark) => dark ? Mix(AppPalette.LogoBlue, C(0xFF2E3238), 0.7) : Mix(AppPalette.LogoBlue, C(0xFF6E7886), 0.5);
    public static Brush MetalBar(bool dark) => Vertical((0, Mix(Metal(dark), Colors.White, dark ? 0.2 : 0.45)), (0.55, Metal(dark)), (1, Mix(Metal(dark), Colors.Black, 0.18)));

    /// <summary>The table under the window, worked out once per theme: linen, or leather in the dark.</summary>
    public static ImageBrush Texture(bool dark)
    {
        if (textures.TryGetValue(dark, out var cached)) return cached;
        const int size = 1536;
        var pixels = new byte[size * size * 4];
        static float Hash(int x, int y, int seed)
        {
            unchecked
            {
                var h = x * 374761393 + y * 668265263 + seed * 144269504;
                h = (h ^ (int)((uint)h >> 13)) * 1274126177;
                return ((h ^ (int)((uint)h >> 16)) & 0xFFFF) / 65535f;
            }
        }
        static float Noise(float x, float y, int cells, int seed)
        {
            float fx = x * cells / size, fy = y * cells / size;
            int x0 = (int)MathF.Floor(fx), y0 = (int)MathF.Floor(fy);
            float tx = fx - x0, ty = fy - y0;
            tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty);
            float At(int cx, int cy) => Hash(((cx % cells) + cells) % cells, ((cy % cells) + cells) % cells, seed);
            var top = At(x0, y0) + (At(x0 + 1, y0) - At(x0, y0)) * tx;
            var bottom = At(x0, y0 + 1) + (At(x0 + 1, y0 + 1) - At(x0, y0 + 1)) * tx;
            return top + (bottom - top) * ty;
        }
        int[] baseColor = dark ? [0x26, 0x22, 0x1E] : [0xE4, 0xDE, 0xD2];
        for (var y = 0; y < size; y++)
            for (var x = 0; x < size; x++)
            {
                float shade;
                if (dark)
                {
                    var blotch = Noise(x, y, 28, 1) * 0.6f + Noise(x, y, 110, 2) * 0.4f;
                    var crease = 1 - MathF.Abs(MathF.Sin(blotch * 18));
                    shade = (blotch - 0.5f) * 14 - crease * crease * 9 + (Hash(x, y, 3) - 0.5f) * 5;
                }
                else shade = (Hash(0, y, 4) - 0.5f) * 9 + (Hash(x, 0, 5) - 0.5f) * 7 + (Noise(x, y, 96, 6) - 0.5f) * 5 + (Hash(x, y, 7) - 0.5f) * 4;
                var i = (y * size + x) * 4;
                pixels[i] = (byte)Math.Clamp(baseColor[2] + shade * 0.9f, 0, 255);
                pixels[i + 1] = (byte)Math.Clamp(baseColor[1] + shade, 0, 255);
                pixels[i + 2] = (byte)Math.Clamp(baseColor[0] + shade, 0, 255);
                pixels[i + 3] = 255;
            }
        var bitmap = new WriteableBitmap(size, size);
        using (var stream = bitmap.PixelBuffer.AsStream()) stream.Write(pixels, 0, pixels.Length);
        bitmap.Invalidate();
        return textures[dark] = new ImageBrush { ImageSource = bitmap, Stretch = Stretch.UniformToFill };
    }

    static readonly string[] Keys =
    [
        "ButtonBackground", "ButtonBackgroundPointerOver", "ButtonBackgroundPressed", "ButtonBackgroundDisabled",
        "ButtonForeground", "ButtonForegroundPointerOver", "ButtonForegroundPressed",
        "ButtonBorderBrush", "ButtonBorderBrushPointerOver", "ButtonBorderBrushPressed",
        "AccentButtonBackground", "AccentButtonBackgroundPointerOver", "AccentButtonBackgroundPressed",
        "AccentButtonForeground", "AccentButtonForegroundPointerOver", "AccentButtonForegroundPressed",
        "AccentButtonBorderBrush", "AccentButtonBorderBrushPointerOver", "AccentButtonBorderBrushPressed",
        "ToggleSwitchFillOn", "ToggleSwitchFillOnPointerOver", "ToggleSwitchFillOnPressed",
        "ToggleSwitchFillOff", "ToggleSwitchFillOffPointerOver", "ToggleSwitchFillOffPressed",
        "ToggleSwitchStrokeOn", "ToggleSwitchStrokeOff", "ToggleSwitchStrokeOffPointerOver",
        "ToggleSwitchKnobFillOn", "ToggleSwitchKnobFillOnPointerOver", "ToggleSwitchKnobFillOnPressed",
        "ToggleSwitchKnobFillOff", "ToggleSwitchKnobFillOffPointerOver", "ToggleSwitchKnobFillOffPressed",
        "ComboBoxBackground", "ComboBoxBackgroundPointerOver", "ComboBoxBorderBrush",
        "TextControlBackground", "TextControlBackgroundPointerOver", "TextControlBorderBrush",
        "NavigationViewContentBackground", "NavigationViewContentGridBorderBrush",
        "NavigationViewExpandedPaneBackground", "NavigationViewDefaultPaneBackground",
        "CardBackgroundFillColorDefaultBrush", "CardStrokeColorDefaultBrush",
    ];

    /// <summary>Puts the look on [root] or takes it away, for the theme it shows.</summary>
    public static void Apply(Window window, Grid root, Grid titleBar, bool on)
    {
        On = on;
        var resources = root.Resources;
        foreach (var key in Keys) resources.Remove(key);
        if (!on)
        {
            root.Background = null;
            titleBar.Background = null;
            titleBar.RequestedTheme = ElementTheme.Default;
            window.SystemBackdrop ??= new MicaBackdrop();
            window.AppWindow.TitleBar.ButtonForegroundColor = null;
            Refresh(root);
            return;
        }
        var dark = Dark = root.ActualTheme == ElementTheme.Dark;
        window.SystemBackdrop = null;
        root.Background = Texture(dark);
        titleBar.Background = MetalBar(dark);
        titleBar.RequestedTheme = ElementTheme.Dark;
        window.AppWindow.TitleBar.ButtonForegroundColor = Colors.White;
        window.AppWindow.TitleBar.ButtonBackgroundColor = Colors.Transparent;
        var white = new SolidColorBrush(Colors.White);
        void Set(Brush brush, params string[] keys) { foreach (var key in keys) resources[key] = brush; }
        Set(Raised(dark), "ButtonBackground", "ComboBoxBackground");
        Set(Hover(dark), "ButtonBackgroundPointerOver", "ComboBoxBackgroundPointerOver");
        Set(Pressed(dark), "ButtonBackgroundPressed", "ButtonBackgroundDisabled");
        Set(Ink(dark), "ButtonForeground", "ButtonForegroundPointerOver", "ButtonForegroundPressed");
        Set(Edge(dark), "ButtonBorderBrush", "ButtonBorderBrushPointerOver", "ButtonBorderBrushPressed", "ComboBoxBorderBrush", "TextControlBorderBrush", "CardStrokeColorDefaultBrush");
        Set(Gel(dark), "AccentButtonBackground", "ToggleSwitchFillOn");
        Set(Gel(dark, 0.1), "AccentButtonBackgroundPointerOver", "ToggleSwitchFillOnPointerOver");
        Set(GelPressed(dark), "AccentButtonBackgroundPressed", "ToggleSwitchFillOnPressed");
        Set(white, "AccentButtonForeground", "AccentButtonForegroundPointerOver", "AccentButtonForegroundPressed");
        Set(GelEdge(dark), "AccentButtonBorderBrush", "AccentButtonBorderBrushPointerOver", "AccentButtonBorderBrushPressed", "ToggleSwitchStrokeOn");
        // The groove of the switch is cut into the surface; the knob is chrome.
        Set(dark ? Vertical((0, C(0xFF1A1714)), (1, C(0xFF34302A))) : Vertical((0, C(0xFFD8D4CC)), (1, C(0xFFF7F5F1))), "ToggleSwitchFillOff", "ToggleSwitchFillOffPointerOver", "ToggleSwitchFillOffPressed");
        Set(Edge(dark), "ToggleSwitchStrokeOff", "ToggleSwitchStrokeOffPointerOver");
        Set(Vertical((0, Colors.White), (1, C(0xFFCFCFCF))), "ToggleSwitchKnobFillOn", "ToggleSwitchKnobFillOnPointerOver", "ToggleSwitchKnobFillOnPressed",
            "ToggleSwitchKnobFillOff", "ToggleSwitchKnobFillOffPointerOver", "ToggleSwitchKnobFillOffPressed");
        Set(new SolidColorBrush(dark ? C(0xFF1E1B18) : Colors.White), "TextControlBackground", "TextControlBackgroundPointerOver");
        Set(new SolidColorBrush(Colors.Transparent), "NavigationViewContentBackground", "NavigationViewContentGridBorderBrush");
        Set(new SolidColorBrush(dark ? C(0x66000000) : C(0x59FFFFFF)), "NavigationViewExpandedPaneBackground", "NavigationViewDefaultPaneBackground");
        Set(Panel(dark), "CardBackgroundFillColorDefaultBrush");
        Refresh(root);
    }

    /// <summary>Controls read their resources again only when their theme changes: a flip makes them.</summary>
    internal static void Refresh(FrameworkElement root)
    {
        Refreshing = true;
        var theme = root.RequestedTheme;
        root.RequestedTheme = root.ActualTheme == ElementTheme.Dark ? ElementTheme.Light : ElementTheme.Dark;
        root.RequestedTheme = theme;
        Refreshing = false;
    }

    /// <summary>A card of the home: an ivory key, or lit glass when on.</summary>
    public static void ApplyCard(Button button, bool on, bool highlighted)
    {
        var dark = button.ActualTheme == ElementTheme.Dark;
        Brush foreground = on ? new SolidColorBrush(Colors.White) : Ink(dark);
        Brush normal = on ? Gel(dark) : Raised(dark), hover = on ? Gel(dark, 0.08) : Hover(dark), pressed = on ? GelPressed(dark) : Pressed(dark);
        Brush border = highlighted ? new SolidColorBrush(AppPalette.LogoLightBlue) : on ? GelEdge(dark) : Edge(dark);
        foreach (var state in new[] { "", "PointerOver", "Pressed", "Disabled" })
        {
            button.Resources["ButtonBackground" + state] = state == "PointerOver" ? hover : state == "Pressed" ? pressed : normal;
            button.Resources["ButtonForeground" + state] = foreground;
            button.Resources["ButtonBorderBrush" + state] = border;
        }
        button.Background = normal;
        button.Foreground = foreground;
        button.BorderBrush = border;
        // A thicker lower edge: the key stands out of the table.
        button.BorderThickness = new Thickness(1, 1, 1, highlighted ? 3 : 2);
    }
}
