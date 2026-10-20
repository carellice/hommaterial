using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.UI;
using Windows.UI.ViewManagement;

namespace Hommaterial.Windows;

internal static class AppPalette
{
    // The two blues of pwa/icons/icon.svg: dark on light surfaces, bright on dark surfaces.
    public static readonly Color LogoBlue = ColorHelper.FromArgb(255, 26, 63, 176);
    public static readonly Color LogoLightBlue = ColorHelper.FromArgb(255, 61, 139, 255);
    public static SolidColorBrush Accent(ElementTheme theme) => new(theme == ElementTheme.Dark ? LogoLightBlue : LogoBlue);

    public static void Configure(ResourceDictionary resources)
    {
        resources["SystemAccentColor"] = LogoBlue;
        resources["SystemAccentColorDark1"] = ColorHelper.FromArgb(255, 22, 53, 149);
        resources["SystemAccentColorDark2"] = ColorHelper.FromArgb(255, 16, 40, 112);
        resources["SystemAccentColorDark3"] = ColorHelper.FromArgb(255, 10, 30, 90);
        resources["SystemAccentColorLight1"] = LogoLightBlue;
        resources["SystemAccentColorLight2"] = ColorHelper.FromArgb(255, 122, 177, 255);
        resources["SystemAccentColorLight3"] = ColorHelper.FromArgb(255, 184, 213, 255);
    }

    public static void ApplyCard(Button button, bool on, bool highlighted)
    {
        var accent = Accent(button.ActualTheme);
        var dark = button.ActualTheme == ElementTheme.Dark;
        Brush foreground = new SolidColorBrush(dark ? ColorHelper.FromArgb(255, 245, 245, 245) : ColorHelper.FromArgb(255, 27, 30, 39));
        Brush normal, hover, pressed, border;
        if (new AccessibilitySettings().HighContrast)
        {
            static SolidColorBrush SystemBrush(System.Drawing.Color color) => new(ColorHelper.FromArgb(color.A, color.R, color.G, color.B));
            accent = SystemBrush(System.Drawing.SystemColors.Highlight);
            normal = on ? accent : SystemBrush(System.Drawing.SystemColors.Window);
            hover = pressed = normal;
            foreground = SystemBrush(on ? System.Drawing.SystemColors.HighlightText : System.Drawing.SystemColors.WindowText);
            border = highlighted || on ? accent : foreground;
        }
        else
        {
            SolidColorBrush Tint(byte opacity)
            {
                var blue = button.ActualTheme == ElementTheme.Dark ? LogoLightBlue : LogoBlue;
                return new(ColorHelper.FromArgb(opacity, blue.R, blue.G, blue.B));
            }
            normal = on ? Tint(36) : new SolidColorBrush(dark ? ColorHelper.FromArgb(13, 255, 255, 255) : ColorHelper.FromArgb(180, 255, 255, 255));
            hover = on ? Tint(52) : new SolidColorBrush(dark ? ColorHelper.FromArgb(25, 255, 255, 255) : ColorHelper.FromArgb(255, 244, 246, 250));
            pressed = on ? Tint(64) : new SolidColorBrush(dark ? ColorHelper.FromArgb(33, 255, 255, 255) : ColorHelper.FromArgb(255, 234, 240, 250));
            border = on || highlighted ? accent : new SolidColorBrush(dark ? ColorHelper.FromArgb(38, 255, 255, 255) : ColorHelper.FromArgb(25, 0, 0, 0));
        }

        // The default Button visual states replace its local Background on pointer-over/press.
        // Override those resources per card so an active device keeps its state in every state.
        foreach (var state in new[] { "", "PointerOver", "Pressed", "Disabled" })
        {
            button.Resources["ButtonBackground" + state] = state == "PointerOver" ? hover : state == "Pressed" ? pressed : normal;
            button.Resources["ButtonForeground" + state] = foreground;
            button.Resources["ButtonBorderBrush" + state] = border;
        }
        button.Background = normal;
        button.Foreground = foreground;
        button.BorderBrush = border;
    }
}
