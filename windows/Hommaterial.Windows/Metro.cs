using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace Hommaterial.Windows;

/// <summary>
/// The Modern UI look of Windows Phone, chosen in Settings → Appearance → Style, as on Android and on
/// the web: pure black or white under the window, square tiles in one vivid accent, square framed
/// buttons and the controls of WinUI restyled through their own resources. Off, nothing here is applied.
/// </summary>
internal static class Metro
{
    public static bool On { get; private set; }
    public static bool Dark { get; private set; }

    /// <summary>The cobalt of Windows Phone, near the blue of the logo.</summary>
    public static readonly Color Accent = ColorHelper.FromArgb(255, 0x1B, 0x6F, 0xE2);
    static readonly Color AccentLight = ColorHelper.FromArgb(255, 0x3D, 0x87, 0xEC);
    static readonly Color AccentDeep = ColorHelper.FromArgb(255, 0x14, 0x58, 0xB8);

    public static Brush Background(bool dark) => new SolidColorBrush(dark ? Colors.Black : Colors.White);
    public static Brush Ink(bool dark) => new SolidColorBrush(dark ? Colors.White : Colors.Black);
    public static Brush Tile(bool dark) => new SolidColorBrush(dark ? ColorHelper.FromArgb(255, 0x2B, 0x2B, 0x2B) : ColorHelper.FromArgb(255, 0xE6, 0xE6, 0xE6));
    static Brush TileHover(bool dark) => new SolidColorBrush(dark ? ColorHelper.FromArgb(255, 0x36, 0x36, 0x36) : ColorHelper.FromArgb(255, 0xDC, 0xDC, 0xDC));
    public static Brush Panel(bool dark) => new SolidColorBrush(dark ? ColorHelper.FromArgb(255, 0x1F, 0x1F, 0x1F) : ColorHelper.FromArgb(255, 0xF0, 0xF0, 0xF0));

    static readonly string[] Keys =
    [
        "ButtonBackground", "ButtonBackgroundPointerOver", "ButtonBackgroundPressed",
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
        "TextControlBackground", "TextControlBackgroundPointerOver", "TextControlBorderBrush", "TextControlBorderBrushFocused",
        "NavigationViewContentBackground", "NavigationViewContentGridBorderBrush",
        "NavigationViewExpandedPaneBackground", "NavigationViewDefaultPaneBackground",
        "NavigationViewSelectionIndicatorForeground",
        "CardBackgroundFillColorDefaultBrush", "CardStrokeColorDefaultBrush",
        "ControlCornerRadius", "OverlayCornerRadius", "ButtonBorderThemeThickness", "ToggleSwitchOuterBorderStrokeThickness",
        "SliderTrackFill", "SliderTrackFillPointerOver", "SliderTrackFillPressed",
        "SliderTrackValueFill", "SliderTrackValueFillPointerOver", "SliderTrackValueFillPressed",
        "SliderThumbBackground", "SliderThumbBackgroundPointerOver", "SliderThumbBackgroundPressed",
        "SliderTrackThemeHeight", "SliderHorizontalThumbWidth", "SliderHorizontalThumbHeight", "SliderThumbCornerRadius",
    ];

    /// <summary>
    /// Puts the look on [root] or takes it away. Off, it only takes its resources back: the
    /// skeuomorphic look, applied after it, resets the window.
    /// </summary>
    public static void Apply(Window window, Grid root, Grid titleBar, bool on)
    {
        On = on;
        var resources = root.Resources;
        foreach (var key in Keys) resources.Remove(key);
        if (!on) return;
        // Read from what was asked: the actual theme of [root] catches up only at the next layout.
        var dark = Dark = root.RequestedTheme switch
        {
            ElementTheme.Dark => true,
            ElementTheme.Light => false,
            _ => Application.Current.RequestedTheme == ApplicationTheme.Dark,
        };
        window.SystemBackdrop = null;
        root.Background = Background(dark);
        titleBar.Background = Background(dark);
        titleBar.RequestedTheme = ElementTheme.Default;
        window.AppWindow.TitleBar.ButtonForegroundColor = dark ? Colors.White : Colors.Black;
        window.AppWindow.TitleBar.ButtonBackgroundColor = Colors.Transparent;
        var white = new SolidColorBrush(Colors.White);
        var ink = Ink(dark);
        var clear = new SolidColorBrush(Colors.Transparent);
        void Set(object value, params string[] keys) { foreach (var key in keys) resources[key] = value; }
        // Every button of the phone: a frame of two pixels on the bare background, filled when pressed.
        Set(clear, "ButtonBackground");
        Set(new SolidColorBrush(dark ? ColorHelper.FromArgb(40, 255, 255, 255) : ColorHelper.FromArgb(24, 0, 0, 0)), "ButtonBackgroundPointerOver");
        Set(new SolidColorBrush(Accent), "ButtonBackgroundPressed", "AccentButtonBackground", "ToggleSwitchFillOn", "NavigationViewSelectionIndicatorForeground");
        Set(ink, "ButtonForeground", "ButtonForegroundPointerOver", "ButtonBorderBrush", "ButtonBorderBrushPointerOver", "ComboBoxBorderBrush", "TextControlBorderBrush",
            "ToggleSwitchStrokeOff", "ToggleSwitchStrokeOffPointerOver", "ToggleSwitchKnobFillOff", "ToggleSwitchKnobFillOffPointerOver", "ToggleSwitchKnobFillOffPressed",
            "ToggleSwitchStrokeOn", "ToggleSwitchKnobFillOn", "ToggleSwitchKnobFillOnPointerOver", "ToggleSwitchKnobFillOnPressed");
        Set(white, "ButtonForegroundPressed", "AccentButtonForeground", "AccentButtonForegroundPointerOver", "AccentButtonForegroundPressed");
        Set(new SolidColorBrush(Accent), "ButtonBorderBrushPressed", "AccentButtonBorderBrush", "TextControlBorderBrushFocused");
        Set(new SolidColorBrush(AccentLight), "AccentButtonBackgroundPointerOver", "AccentButtonBorderBrushPointerOver", "ToggleSwitchFillOnPointerOver");
        Set(new SolidColorBrush(AccentDeep), "AccentButtonBackgroundPressed", "AccentButtonBorderBrushPressed", "ToggleSwitchFillOnPressed");
        Set(clear, "ToggleSwitchFillOff", "ToggleSwitchFillOffPointerOver", "ToggleSwitchFillOffPressed", "ComboBoxBackground",
            "NavigationViewContentBackground", "NavigationViewContentGridBorderBrush");
        Set(TileHover(dark), "ComboBoxBackgroundPointerOver");
        Set(Panel(dark), "TextControlBackground", "TextControlBackgroundPointerOver", "CardBackgroundFillColorDefaultBrush",
            "NavigationViewExpandedPaneBackground", "NavigationViewDefaultPaneBackground");
        Set(clear, "CardStrokeColorDefaultBrush");
        // Square everywhere, and the thick frames of the phone.
        Set(new CornerRadius(0), "ControlCornerRadius", "OverlayCornerRadius");
        Set(new Thickness(2), "ButtonBorderThemeThickness");
        Set(2.0, "ToggleSwitchOuterBorderStrokeThickness");
        // The slider of the phone: a flat bar filling with the accent and a narrow square thumb.
        Set(TileHover(dark), "SliderTrackFill", "SliderTrackFillPointerOver", "SliderTrackFillPressed");
        Set(new SolidColorBrush(Accent), "SliderTrackValueFill", "SliderTrackValueFillPointerOver", "SliderTrackValueFillPressed");
        Set(ink, "SliderThumbBackground", "SliderThumbBackgroundPointerOver", "SliderThumbBackgroundPressed");
        Set(12.0, "SliderTrackThemeHeight");
        Set(12.0, "SliderHorizontalThumbWidth");
        Set(28.0, "SliderHorizontalThumbHeight");
        Set(new CornerRadius(0), "SliderThumbCornerRadius");
        Skeuo.Refresh(root);
    }

    /// <summary>A card of the home: a square of the accent when on, a gray square when off.</summary>
    public static void ApplyCard(Button button, bool on, bool highlighted)
    {
        var dark = button.ActualTheme == ElementTheme.Dark;
        Brush foreground = on ? new SolidColorBrush(Colors.White) : Ink(dark);
        Brush normal = on ? new SolidColorBrush(Accent) : Tile(dark), hover = on ? new SolidColorBrush(AccentLight) : TileHover(dark),
            pressed = on ? new SolidColorBrush(AccentDeep) : new SolidColorBrush(Accent);
        Brush border = highlighted ? Ink(dark) : new SolidColorBrush(Colors.Transparent);
        foreach (var state in new[] { "", "PointerOver", "Pressed", "Disabled" })
        {
            button.Resources["ButtonBackground" + state] = state == "PointerOver" ? hover : state == "Pressed" ? pressed : normal;
            button.Resources["ButtonForeground" + state] = state == "Pressed" ? new SolidColorBrush(Colors.White) : foreground;
            button.Resources["ButtonBorderBrush" + state] = border;
        }
        button.Background = normal;
        button.Foreground = foreground;
        button.BorderBrush = border;
        button.BorderThickness = new Thickness(highlighted ? 3 : 0);
        button.CornerRadius = new CornerRadius(0);
    }
}
