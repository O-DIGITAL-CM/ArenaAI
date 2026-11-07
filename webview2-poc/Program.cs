using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace Arena.WebView2Prototype;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        try
        {
            ProcessResourceLimits.Apply();
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "Windows could not enforce the WebView2 prototype's one-core-equivalent CPU and 1 GiB "
                + "aggregate memory limits, so the browser was not started.\n\n" + ex.Message,
                "Resource limits unavailable", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        Application.Run(new BrowserForm(ReadStartUrl(args)));
    }

    private static Uri ReadStartUrl(string[] args)
    {
        for (int i = 0; i + 1 < args.Length; i++)
        {
            if (string.Equals(args[i], "--url", StringComparison.OrdinalIgnoreCase)
                && Uri.TryCreate(args[i + 1], UriKind.Absolute, out Uri? supplied)
                && (supplied.Scheme == Uri.UriSchemeHttp || supplied.Scheme == Uri.UriSchemeHttps))
            {
                return supplied;
            }
        }

        return new Uri("https://arena.ai/");
    }
}

internal sealed class BrowserForm : Form
{
    private readonly Uri _startUrl;
    private readonly WebView2 _browser = new();
    private readonly ToolStrip _toolbar = new();
    private readonly ToolStripButton _back = new("Back");
    private readonly ToolStripButton _forward = new("Forward");
    private readonly ToolStripButton _reload = new("Reload");
    private readonly ToolStripButton _home = new("Arena");
    private readonly ToolStripButton _go = new("Go");
    private readonly ToolStripTextBox _address = new() { AutoSize = false, Width = 760 };
    private bool _initialized;

    public BrowserForm(Uri startUrl)
    {
        _startUrl = startUrl;
        Text = "Arena — WebView2 prototype";
        Width = 1280;
        Height = 850;
        MinimumSize = new Size(800, 560);
        StartPosition = FormStartPosition.CenterScreen;

        _toolbar.GripStyle = ToolStripGripStyle.Hidden;
        _toolbar.Items.AddRange([_back, _forward, _reload, _home, _address, _go]);
        _toolbar.Dock = DockStyle.Top;
        _browser.Dock = DockStyle.Fill;
        Controls.Add(_browser);
        Controls.Add(_toolbar);
        Controls.SetChildIndex(_toolbar, 0);

        _back.Click += (_, _) => NavigateBack();
        _forward.Click += (_, _) => NavigateForward();
        _reload.Click += (_, _) => _browser.CoreWebView2?.Reload();
        _home.Click += (_, _) => Navigate(new Uri("https://arena.ai/"));
        _go.Click += (_, _) => NavigateFromAddressBar();
        _address.TextBox.KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.Enter)
            {
                e.SuppressKeyPress = true;
                NavigateFromAddressBar();
            }
        };
        Shown += async (_, _) => await InitializeBrowserAsync();
    }

    private async Task InitializeBrowserAsync()
    {
        if (_initialized)
        {
            return;
        }
        _initialized = true;

        try
        {
            string profilePath = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Arena", "WebView2Prototype");
            Directory.CreateDirectory(profilePath);

            // This is a separate WebView2 profile. No Chrome/Edge cookies are imported.
            CoreWebView2Environment environment = await CoreWebView2Environment.CreateAsync(
                browserExecutableFolder: null,
                userDataFolder: profilePath,
                options: null);
            await _browser.EnsureCoreWebView2Async(environment);

            CoreWebView2 core = _browser.CoreWebView2;
            core.HistoryChanged += (_, _) => UpdateNavigationButtons();
            core.NavigationCompleted += (_, _) => UpdateAddressBar();
            core.DocumentTitleChanged += (_, _) => UpdateWindowTitle();
            core.Settings.AreDefaultContextMenusEnabled = true;
            core.Settings.AreDevToolsEnabled = false;

            // Keep WebView2's real Edge/Chromium user agent. No UA spoofing or
            // Cloudflare challenge/cookie manipulation is performed.
            _address.Text = _startUrl.ToString();
            Navigate(_startUrl);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this,
                "The WebView2 prototype could not start. Confirm that the Microsoft Edge WebView2 Runtime "
                + "is installed, then retry.\n\n" + ex.Message,
                "WebView2 unavailable", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Close();
        }
    }

    private void NavigateBack()
    {
        if (_browser.CoreWebView2 is { CanGoBack: true } core)
        {
            core.GoBack();
        }
    }

    private void NavigateForward()
    {
        if (_browser.CoreWebView2 is { CanGoForward: true } core)
        {
            core.GoForward();
        }
    }

    private void NavigateFromAddressBar()
    {
        string value = _address.Text.Trim();
        if (!value.Contains("://", StringComparison.Ordinal))
        {
            value = "https://" + value;
        }

        if (Uri.TryCreate(value, UriKind.Absolute, out Uri? uri)
            && (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps))
        {
            Navigate(uri);
        }
        else
        {
            MessageBox.Show(this, "Enter a valid http:// or https:// address.",
                "Invalid address", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }

    private void Navigate(Uri uri)
    {
        if (_browser.CoreWebView2 is not null)
        {
            _browser.CoreWebView2.Navigate(uri.ToString());
        }
    }

    private void UpdateAddressBar()
    {
        if (_browser.Source is not null)
        {
            _address.Text = _browser.Source.ToString();
        }
        UpdateNavigationButtons();
    }

    private void UpdateNavigationButtons()
    {
        CoreWebView2? core = _browser.CoreWebView2;
        _back.Enabled = core?.CanGoBack == true;
        _forward.Enabled = core?.CanGoForward == true;
    }

    private void UpdateWindowTitle()
    {
        string? title = _browser.CoreWebView2?.DocumentTitle;
        Text = string.IsNullOrWhiteSpace(title)
            ? "Arena — WebView2 prototype"
            : title + " — Arena WebView2 prototype";
    }
}
