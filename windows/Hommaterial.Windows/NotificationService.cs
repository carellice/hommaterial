using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;

namespace Hommaterial.Windows;

internal sealed class NotificationService(Action<string, string> fallback) : IDisposable
{
    bool attempted, registered;
    public string Status => registered ? "Notifiche Windows attive" : attempted ? "Notifiche tramite area di notifica Windows" : "Pronte al primo avviso";
    public void Show(string title, string text)
    {
        try
        {
            if (!attempted)
            {
                attempted = true;
                AppNotificationManager.Default.NotificationInvoked += (_, _) => { };
                AppNotificationManager.Default.Register(); registered = true;
            }
            if (!registered) { fallback(title, text); return; }
            AppNotificationManager.Default.Show(new AppNotificationBuilder().AddText(title).AddText(text).BuildNotification());
        }
        catch (Exception e) { LocalStore.Log(e); registered = false; fallback(title, text); }
    }
    public void Dispose()
    {
        if (registered) try { AppNotificationManager.Default.Unregister(); } catch { }
    }
}
