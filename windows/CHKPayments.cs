using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

namespace CHKPayments;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Contains("--self-test"))
        {
            try
            {
                using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("CHKPayments.runtime.zip");
                if (stream == null) throw new InvalidDataException("Runtime embarque introuvable");
                using var archive = new ZipArchive(stream, ZipArchiveMode.Read);
                foreach (string required in new[] { "node.exe", "payments/hub.mjs", "payments/sidecar.mjs", "payments/pair-code.mjs" })
                    if (!archive.Entries.Any(e => e.FullName.Replace('\\', '/') == required))
                        throw new InvalidDataException("Runtime incomplet: " + required);
                Environment.ExitCode = 0;
            }
            catch (Exception ex) { Console.Error.WriteLine(ex.Message); Environment.ExitCode = 1; }
            return;
        }
        ApplicationConfiguration.Initialize();
        Application.Run(new PaymentsDashboard(args.Contains("--autorun")));
    }
}

internal sealed class PaymentsDashboard : Form
{
    private const string ServerHost = "chk-universal-1.taild9e4de.ts.net";
    private const string HttpsPayments = "https://" + ServerHost + ":8443";
    private const string LocalHealth = "http://127.0.0.1:8791/health";
    private static readonly string Home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CHK-Paiements");
    private static readonly string Runtime = Path.Combine(Home, "runtime-v1");
    private static readonly string Data = Path.Combine(Home, "data");
    private static readonly string LogPath = Path.Combine(Home, "chk-paiements.log");
    private static readonly HttpClient Http = new HttpClient { Timeout = TimeSpan.FromSeconds(4) };

    private readonly Label status = new() { AutoSize = false, ForeColor = Color.FromArgb(160, 234, 223), Text = "Initialisation..." };
    private readonly Label remote = new() { AutoSize = false, ForeColor = Color.FromArgb(170, 190, 213), Text = "HTTPS distinct: non verifie" };
    private readonly TextBox code = new() { ReadOnly = true, BackColor = Color.FromArgb(23, 37, 60), ForeColor = Color.White, Text = "Aucun code genere", BorderStyle = BorderStyle.FixedSingle };
    private readonly CheckBox autostart = new() { Text = "Demarrer CHK Paiements avec Windows", AutoSize = true, ForeColor = Color.White };
    private readonly Button start = Button("DEMARRER LE SERVICE", 24, 146, 520, 43);
    private readonly Button pair = Button("GENERER ET COPIER LE CODE ANDROID", 24, 203, 520, 43);
    private readonly Button https = Button("ACTIVER LE HTTPS SEPARE 8443", 24, 310, 520, 43);
    private readonly Button verify = Button("VERIFIER LES CONNEXIONS", 24, 367, 520, 43);
    private readonly NotifyIcon tray;
    private Process? ownedNode;
    private bool busy;
    private bool quitting;
    private bool serviceReady;
    private readonly bool initialHide;

    internal PaymentsDashboard(bool hidden)
    {
        initialHide = hidden;
        Text = "CHK Paiements — Serveur personnel Sync 30 Numerique";
        Size = new Size(590, 515);
        MinimumSize = new Size(590, 515);
        MaximumSize = new Size(590, 515);
        Font = new Font("Segoe UI", 10f);
        BackColor = Color.FromArgb(12, 20, 35);
        ForeColor = Color.White;
        StartPosition = FormStartPosition.CenterScreen;
        Icon = SystemIcons.Shield;

        Controls.Add(new Label { Left = 24, Top = 18, Width = 520, Height = 37, Text = "CHK PAIEMENTS", ForeColor = Color.White, Font = new Font("Segoe UI", 21f, FontStyle.Bold) });
        Controls.Add(new Label { Left = 24, Top = 59, Width = 530, Height = 26, Text = "Module independant · CHK Universal Control 1.0.4 preserve", ForeColor = Color.FromArgb(133, 216, 203) });
        status.Left = 24; status.Top = 100; status.Width = 535; status.Height = 41; Controls.Add(status);

        start.Click += async (_, _) => await Run(async () => { await StartCore(); await ConfigureHttpsIfSafe(); });
        pair.Click += async (_, _) => await Run(GeneratePairCode);
        Controls.Add(start); Controls.Add(pair);
        code.Left = 24; code.Top = 260; code.Width = 520; code.Height = 28;
        code.Font = new Font("Consolas", 10.5f); Controls.Add(code);
        remote.Left = 24; remote.Top = 290; remote.Width = 545; remote.Height = 20; Controls.Add(remote);

        https.Click += async (_, _) => await Run(async () => { await StartCore(); await ConfigureHttpsIfSafe(); });
        verify.Click += async (_, _) => await Run(async () => { await UpdateStatus(); await CheckHttpsStatus(); });
        Controls.Add(https); Controls.Add(verify);

        autostart.Left = 24; autostart.Top = 420; Controls.Add(autostart);
        autostart.Checked = ReadAutoStart();
        autostart.CheckedChanged += (_, _) => { try { SetAutoStart(autostart.Checked); } catch (Exception ex) { ShowError(ex.Message); } };
        Controls.Add(new Label { Left = 24, Top = 448, Width = 540, Height = 22, ForeColor = Color.FromArgb(160, 170, 192), Text = "L'adresse /mcp universelle et les autres applications ne sont jamais modifiees." });

        tray = new NotifyIcon { Icon = SystemIcons.Shield, Text = "CHK Paiements — Sync 30 Numerique", Visible = true };
        var menu = new ContextMenuStrip();
        menu.Items.Add("Ouvrir CHK Paiements", null, (_, _) => ShowPanel());
        menu.Items.Add("Quitter CHK Paiements", null, (_, _) => { quitting = true; tray.Visible = false; Close(); });
        tray.ContextMenuStrip = menu;
        tray.DoubleClick += (_, _) => ShowPanel();
        FormClosing += (_, e) => {
            if (!quitting && e.CloseReason == CloseReason.UserClosing) {
                e.Cancel = true; Hide(); ShowInTaskbar = false;
                tray.ShowBalloonTip(900, "CHK Paiements", "Le serveur Paiements reste actif.", ToolTipIcon.Info);
            } else { tray.Visible = false; } // The child service is intentionally not stopped.
        };
        Shown += async (_, _) => {
            if (initialHide) { Hide(); ShowInTaskbar = false; }
            await Run(async () => { await StartCore(); await ConfigureHttpsIfSafe(); });
        };
    }
    private static Button Button(string text, int left, int top, int width, int height) => new() {
        Left = left, Top = top, Width = width, Height = height, Text = text, BackColor = Color.FromArgb(114, 218, 202),
        ForeColor = Color.FromArgb(12, 20, 35), FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI", 9.5f, FontStyle.Bold)
    };
    private void ShowPanel() { Show(); WindowState = FormWindowState.Normal; ShowInTaskbar = true; Activate(); }
    private void Say(string message) { if (IsDisposed) return; if (InvokeRequired) { BeginInvoke(() => Say(message)); return; } status.Text = message; }
    private void Remote(string message) { if (IsDisposed) return; if (InvokeRequired) { BeginInvoke(() => Remote(message)); return; } remote.Text = message; }
    private void ShowError(string message) {
        Say("ERREUR : " + message);
        if (!IsDisposed) MessageBox.Show(this, message, "CHK Paiements", MessageBoxButtons.OK, MessageBoxIcon.Warning);
    }
    private async Task Run(Func<Task> work) {
        if (busy) return; busy = true;
        start.Enabled = pair.Enabled = https.Enabled = verify.Enabled = false;
        try { await work(); }
        catch (Exception ex) { WriteLog(ex.Message); ShowError(ex.Message); }
        finally { busy = false; start.Enabled = pair.Enabled = https.Enabled = verify.Enabled = true; }
    }
    private static void WriteLog(string message) { try { Directory.CreateDirectory(Home); File.AppendAllText(LogPath, DateTime.UtcNow.ToString("s") + " " + message + Environment.NewLine); } catch { } }
    private static void ExtractRuntime()
    {
        string nodeExe = Path.Combine(Runtime, "node.exe");
        if (File.Exists(nodeExe) && File.Exists(Path.Combine(Runtime, "payments", "sidecar.mjs"))) return;
        Directory.CreateDirectory(Home);
        string temp = Path.Combine(Home, "runtime-new-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(temp);
        try {
            using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("CHKPayments.runtime.zip")
                 ?? throw new InvalidDataException("Le moteur local integre est absent de CHK Paiements.exe");
            ZipFile.ExtractToDirectory(stream, temp, true);
            foreach (string required in new[] { "node.exe", "payments\\hub.mjs", "payments\\sidecar.mjs", "payments\\pair-code.mjs" })
                if (!File.Exists(Path.Combine(temp, required)))
                    throw new InvalidDataException("Composant embarque manquant : " + required);
            if (Directory.Exists(Runtime)) Directory.Delete(Runtime, true);
            Directory.Move(temp, Runtime);
        } finally { if (Directory.Exists(temp)) Directory.Delete(temp, true); }
    }
    private static async Task<bool> LocalServiceReady()
    {
        try { string json = await Http.GetStringAsync(LocalHealth);
            using var doc = JsonDocument.Parse(json);
            return doc.RootElement.GetProperty("service").GetString() == "CHK Paiements Sidecar";
        } catch { return false; }
    }
    private async Task StartCore()
    {
        Say("Preparation du moteur Paiements integre...");
        await Task.Run(ExtractRuntime);
        Directory.CreateDirectory(Data);
        if (await LocalServiceReady()) {
            serviceReady = true; Say("MOTEUR PAIEMENTS ACTIF · localhost:8791");
            return;
        }
        using (var listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 8791)) {
            try { listener.Start(); }
            catch { throw new InvalidOperationException("Port local 8791 occupe. Aucune modification du service existant."); }
            listener.Stop();
        }
        var info = new ProcessStartInfo {
            FileName = Path.Combine(Runtime, "node.exe"),
            Arguments = "\"" + Path.Combine(Runtime, "payments", "sidecar.mjs") + "\"",
            WorkingDirectory = Runtime,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        info.Environment["PAYMENTS_PORT"] = "8791";
        info.Environment["PAYMENTS_DATA_FILE"] = Path.Combine(Data, "payments-orders.json");
        info.Environment["PAYMENTS_DEVICES_FILE"] = Path.Combine(Data, "payment-devices.json");
        ownedNode = Process.Start(info) ?? throw new InvalidOperationException("Impossible de demarrer le moteur Node.js");
        ownedNode.OutputDataReceived += (_, e) => { if (e.Data != null) WriteLog(e.Data); };
        ownedNode.ErrorDataReceived += (_, e) => { if (e.Data != null) WriteLog(e.Data); };
        ownedNode.BeginOutputReadLine(); ownedNode.BeginErrorReadLine();
        for (int i = 0; i < 36; i++) {
            if (await LocalServiceReady()) { serviceReady = true; Say("MOTEUR PAIEMENTS ACTIF · localhost:8791"); return; }
            if (ownedNode.HasExited) throw new InvalidOperationException("Le moteur Paiements a quitte au demarrage. Consulte le journal local.");
            await Task.Delay(250);
        }
        throw new InvalidOperationException("Le moteur Paiements ne repond pas sur le port 8791.");
    }
    private async Task GeneratePairCode()
    {
        if (!serviceReady) await StartCore();
        Say("Generation d'un nouveau code valable 10 minutes...");
        var info = new ProcessStartInfo {
            FileName = Path.Combine(Runtime, "node.exe"),
            Arguments = "\"" + Path.Combine(Runtime, "payments", "pair-code.mjs") + "\"",
            WorkingDirectory = Runtime,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        info.Environment["PAYMENTS_DEVICES_FILE"] = Path.Combine(Data, "payment-devices.json");
        using var process = Process.Start(info) ?? throw new IOException("Generation impossible");
        string stdout = await process.StandardOutput.ReadToEndAsync();
        string stderr = await process.StandardError.ReadToEndAsync();
        await process.WaitForExitAsync();
        var match = Regex.Match(stdout, @"\b[a-f0-9]{48}\b");
        if (process.ExitCode != 0 || !match.Success) throw new IOException("Code impossible : " + stderr);
        code.Text = match.Value;
        Clipboard.SetText(match.Value);
        Say("CODE GENERE ET COPIE · valable 10 minutes · a saisir sur Android");
    }
    private async Task UpdateStatus() {
        serviceReady = await LocalServiceReady();
        Say(serviceReady ? "MOTEUR PAIEMENTS ACTIF · localhost:8791" : "MOTEUR PAIEMENTS ARRETE · clic sur DEMARRER");
    }
    private static string? Tailscale()
    {
        foreach (string candidate in new[] {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Tailscale", "tailscale.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Tailscale", "tailscale.exe")
        }) if (File.Exists(candidate)) return candidate;
        return null;
    }
    private static async Task<(int, string)> InvokeTailscale(string exe, string args)
    {
        var psi = new ProcessStartInfo { FileName=exe, Arguments=args, CreateNoWindow=true,
            UseShellExecute=false, RedirectStandardOutput=true, RedirectStandardError=true };
        using var p = Process.Start(psi) ?? throw new IOException("Tailscale inaccessible");
        string stdout = await p.StandardOutput.ReadToEndAsync();
        string stderr = await p.StandardError.ReadToEndAsync();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(18));
        try { await p.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException) { try { p.Kill(true); } catch {} throw new TimeoutException("Tailscale ne repond pas"); }
        return (p.ExitCode, stdout + Environment.NewLine + stderr);
    }
    private async Task CheckHttpsStatus()
    {
        string? exe = Tailscale();
        if (exe == null) { Remote("HTTPS 8443 : Tailscale du PC indisponible."); return; }
        var (exit, content) = await InvokeTailscale(exe, "status --json");
        if (exit != 0) { Remote("Tailscale non connecte : HTTPS indisponible"); return; }
        using var doc = JsonDocument.Parse(content);
        string dns = doc.RootElement.GetProperty("Self").GetProperty("DNSName").GetString()?.TrimEnd('.') ?? "";
        if (!dns.Equals(ServerHost, StringComparison.OrdinalIgnoreCase)) {
            Remote("HTTPS 8443 non configure : ce PC utilise un autre nom Tailscale.");
            return;
        }
        var (code, config) = await InvokeTailscale(exe, "serve status --json");
        if (code != 0) { Remote("Tailscale actif, service HTTPS 8443 non verifie."); return; }
        Remote(config.Contains("8443") && config.Contains("8791")
            ? "HTTPS CHK Paiements 8443 : configuré, confirmer l'acces Android."
            : "HTTPS Paiements 8443 : non configure");
    }
    private async Task ConfigureHttpsIfSafe()
    {
        // Never call "serve reset", "funnel", port 443, or the universal MCP.
        string? exe = Tailscale();
        if (exe == null) { Remote("Tailscale Windows absent : serveur Paiements local seulement."); return; }
        var (statusCode, content) = await InvokeTailscale(exe, "status --json");
        if (statusCode != 0) { Remote("Tailscale non connecte, HTTPS en attente."); return; }
        using var document = JsonDocument.Parse(content);
        string dns = document.RootElement.GetProperty("Self").GetProperty("DNSName").GetString()?.TrimEnd('.') ?? "";
        if (!dns.Equals(ServerHost, StringComparison.OrdinalIgnoreCase)) {
            Remote("HTTPS bloque par securite : nom Tailscale de ce PC different de " + ServerHost);
            return;
        }
        var (code, config) = await InvokeTailscale(exe, "serve status --json");
        if (code != 0) { Remote("Configuration Tailscale Serve illisible : aucune route modifiee."); return; }
        if (config.Contains("8443")) {
            if (config.Contains("8791")) Remote("HTTPS 8443 deja reserve au module Paiements · " + HttpsPayments);
            else Remote("Port 8443 deja utilise : aucune route modifiee.");
            return;
        }
        var (result, output) = await InvokeTailscale(exe, "serve --bg --https=8443 http://127.0.0.1:8791");
        if (result != 0) { Remote("Tailscale a refuse le port 8443. MCP universel intact."); WriteLog(output); return; }
        Remote("HTTPS 8443 demande : " + HttpsPayments + " · verifier sur Android.");
    }
    private static string StartupKey = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static bool ReadAutoStart() {
        using var reg = Registry.CurrentUser.OpenSubKey(StartupKey);
        return reg?.GetValue("CHK-Paiements") != null;
    }
    private static void SetAutoStart(bool enabled) {
        using var reg = Registry.CurrentUser.CreateSubKey(StartupKey);
        if (reg == null) throw new IOException("Registre Windows inaccessible.");
        if (enabled) reg.SetValue("CHK-Paiements", "\"" + Environment.ProcessPath + "\" --autorun");
        else reg.DeleteValue("CHK-Paiements", false);
    }
}