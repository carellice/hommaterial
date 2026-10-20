using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.XamlTypeInfo;

namespace Hommaterial.Windows;

internal static class Program
{
    [STAThread]
    static void Main()
    {
        try
        {
            WinRT.ComWrappersSupport.InitializeComWrappers();
            Application.Start(parameters =>
            {
                try
                {
                    SynchronizationContext.SetSynchronizationContext(new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread()));
                    _ = new HommaterialApp();
                }
                catch (Exception error) { LocalStore.Log(error); throw; }
            });
        }
        catch (Exception error) { LocalStore.Log(error); throw; }
    }
}

public sealed class HommaterialApp : Application, IXamlMetadataProvider
{
    readonly XamlControlsXamlMetaDataProvider metadata = new();
    public IXamlType GetXamlType(Type type) => metadata.GetXamlType(type);
    public IXamlType GetXamlType(string fullName) => metadata.GetXamlType(fullName);
    public XmlnsDefinition[] GetXmlnsDefinitions() => metadata.GetXmlnsDefinitions();
    Window? window;
    public HommaterialApp()
    {
        UnhandledException += (_, e) =>
        {
            LocalStore.Log(e.Exception);
        };
    }
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        Resources = new ResourceDictionary();
        AppPalette.Configure(Resources);
        Resources.MergedDictionaries.Add(new XamlControlsResources());
        window = new MainWindow();
        window.Activate();
    }
}
