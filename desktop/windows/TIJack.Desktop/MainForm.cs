namespace TIJack.Desktop;

internal sealed class MainForm : Form
{
    private readonly AppSettings settings = AppSettings.Load();

    private readonly Label connectionStatus = new();
    private readonly Label detailStatus = new();
    private readonly Label pcFolderLabel = new();
    private readonly ListView pcList = new();
    private readonly ListView calculatorList = new();
    private readonly ComboBox deviceBox = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly Button sendButton = new();
    private readonly Button saveButton = new();
    private readonly Button calcDeleteButton = new();
    private readonly Button calcSelectAllButton = new();
    private readonly Button calcClearButton = new();
    private readonly Button chooseFolderButton = new();
    private readonly Button pcDeleteButton = new();

    private readonly List<CalculatorDevice> devices = [];
    private string? currentFolder;
    private ICalculatorBackend? backend;

    public MainForm()
    {
        Text = "TI-JACK";
        Width = 1180;
        Height = 760;
        MinimumSize = new Size(920, 620);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Consolas", 9.5f);

        BuildUi();
        ApplyTheme();
        RefreshDevices();
        RefreshPcFiles();

        FormClosing += (_, _) => settings.Save();
    }

    private void BuildUi()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 4,
            ColumnCount = 1,
            Padding = new Padding(14)
        };
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 54));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
        Controls.Add(root);

        var header = new Panel { Dock = DockStyle.Fill };
        var title = new Label
        {
            Text = "TI-JACK",
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("Consolas", 22, FontStyle.Bold)
        };
        var gear = new Button
        {
            Text = "⚙",
            Width = 42,
            Height = 36,
            FlatStyle = FlatStyle.Flat,
            Anchor = AnchorStyles.Top | AnchorStyles.Right,
            Top = 5
        };
        gear.FlatAppearance.BorderSize = 0;
        header.Resize += (_, _) => gear.Left = header.ClientSize.Width - gear.Width - 2;
        gear.Click += (_, _) =>
        {
            using var dialog = new SettingsForm(settings);
            if (dialog.ShowDialog(this) == DialogResult.OK)
            {
                ApplyTheme();
                ApplyClassroomMode();
            }
        };
        header.Controls.Add(title);
        header.Controls.Add(gear);
        root.Controls.Add(header, 0, 0);

        var statusPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 2,
            ColumnCount = 1
        };
        connectionStatus.TextAlign = ContentAlignment.MiddleCenter;
        connectionStatus.Font = new Font("Consolas", 11.5f, FontStyle.Bold);
        detailStatus.TextAlign = ContentAlignment.TopCenter;
        detailStatus.Font = new Font("Consolas", 8.5f);
        statusPanel.Controls.Add(connectionStatus, 0, 0);
        statusPanel.Controls.Add(detailStatus, 0, 1);
        root.Controls.Add(statusPanel, 0, 1);

        var split = new SplitContainer
        {
            Dock = DockStyle.Fill,
            Orientation = Orientation.Vertical,
            SplitterWidth = 8,
            SplitterDistance = 550
        };
        split.Panel1.Controls.Add(BuildPcPane());
        split.Panel2.Controls.Add(BuildCalculatorPane());
        root.Controls.Add(split, 0, 2);

        var footer = new Label
        {
            Text = "SCHOOL PROPERTY",
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("Consolas", 8.5f, FontStyle.Bold)
        };
        root.Controls.Add(footer, 0, 3);

        ApplyClassroomMode();
    }

    private Control BuildPcPane()
    {
        var panel = BasePane();

        var top = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = 66,
            ColumnCount = 2
        };
        top.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        top.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 120));

        var labels = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 2,
            ColumnCount = 1
        };
        labels.Controls.Add(new Label
        {
            Text = "PC",
            Dock = DockStyle.Fill,
            Font = new Font("Consolas", 12, FontStyle.Bold)
        }, 0, 0);
        pcFolderLabel.Dock = DockStyle.Fill;
        pcFolderLabel.Text = "NO FOLDER SELECTED";
        pcFolderLabel.AutoEllipsis = true;
        labels.Controls.Add(pcFolderLabel, 0, 1);

        chooseFolderButton.Text = "CHOOSE FOLDER";
        chooseFolderButton.Dock = DockStyle.Fill;
        chooseFolderButton.Click += (_, _) => ChooseFolder();

        top.Controls.Add(labels, 0, 0);
        top.Controls.Add(chooseFolderButton, 1, 0);
        panel.Controls.Add(top);

        ConfigurePcList();
        panel.Controls.Add(pcList);

        var actions = ButtonRow();
        var selectAll = ActionButton("SELECT ALL", (_, _) =>
        {
            foreach (ListViewItem item in pcList.Items) item.Selected = true;
            UpdateActionState();
        });
        var clear = ActionButton("CLEAR", (_, _) =>
        {
            pcList.SelectedItems.Clear();
            UpdateActionState();
        });
        pcDeleteButton.Text = "DELETE";
        pcDeleteButton.Width = 120;
        pcDeleteButton.Height = 32;
        pcDeleteButton.Click += (_, _) => DeletePcSelection();
        actions.Controls.Add(selectAll);
        actions.Controls.Add(clear);
        actions.Controls.Add(pcDeleteButton);
        panel.Controls.Add(actions);

        sendButton.Text = "SEND SELECTED →";
        sendButton.Height = 38;
        sendButton.Dock = DockStyle.Bottom;
        sendButton.Click += (_, _) =>
            MessageBox.Show(
                this,
                backend is null ? "No supported calculator is connected." : backend.Status,
                "TI-JACK transport status",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
        panel.Controls.Add(sendButton);

        return panel;
    }

    private Control BuildCalculatorPane()
    {
        var panel = BasePane();

        var top = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = 66,
            ColumnCount = 3
        };
        top.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        top.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 220));
        top.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 92));

        var labels = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 2,
            ColumnCount = 1
        };
        labels.Controls.Add(new Label
        {
            Text = "CALCULATOR",
            Dock = DockStyle.Fill,
            Font = new Font("Consolas", 12, FontStyle.Bold)
        }, 0, 0);
        labels.Controls.Add(new Label
        {
            Text = "WINDOWS USB",
            Dock = DockStyle.Fill
        }, 0, 1);

        deviceBox.Dock = DockStyle.Fill;
        deviceBox.SelectedIndexChanged += (_, _) => SelectDevice();

        var rescan = new Button { Text = "RESCAN", Dock = DockStyle.Fill };
        rescan.Click += (_, _) => RefreshDevices();

        top.Controls.Add(labels, 0, 0);
        top.Controls.Add(deviceBox, 1, 0);
        top.Controls.Add(rescan, 2, 0);
        panel.Controls.Add(top);

        calculatorList.View = View.Details;
        calculatorList.FullRowSelect = true;
        calculatorList.MultiSelect = true;
        calculatorList.Dock = DockStyle.Fill;
        calculatorList.Columns.Add("NAME", 270);
        calculatorList.Columns.Add("SIZE", 100, HorizontalAlignment.Right);
        calculatorList.Columns.Add("TYPE", 90);
        calculatorList.SelectedIndexChanged += (_, _) => UpdateActionState();
        panel.Controls.Add(calculatorList);

        var actions = ButtonRow();
        calcSelectAllButton.Text = "SELECT ALL";
        calcSelectAllButton.Width = 120;
        calcSelectAllButton.Height = 32;
        calcSelectAllButton.Click += (_, _) =>
        {
            foreach (ListViewItem item in calculatorList.Items) item.Selected = true;
            UpdateActionState();
        };
        calcClearButton.Text = "CLEAR";
        calcClearButton.Width = 120;
        calcClearButton.Height = 32;
        calcClearButton.Click += (_, _) =>
        {
            calculatorList.SelectedItems.Clear();
            UpdateActionState();
        };
        calcDeleteButton.Text = "DELETE";
        calcDeleteButton.Width = 120;
        calcDeleteButton.Height = 32;
        calcDeleteButton.Click += (_, _) =>
            MessageBox.Show(this, "Calculator delete will enable only when the selected backend verifies it.",
                "Not enabled yet", MessageBoxButtons.OK, MessageBoxIcon.Information);
        actions.Controls.Add(calcSelectAllButton);
        actions.Controls.Add(calcClearButton);
        actions.Controls.Add(calcDeleteButton);
        panel.Controls.Add(actions);

        saveButton.Text = "← SAVE SELECTED";
        saveButton.Height = 38;
        saveButton.Dock = DockStyle.Bottom;
        panel.Controls.Add(saveButton);

        return panel;
    }

    private static Panel BasePane() => new()
    {
        Dock = DockStyle.Fill,
        Padding = new Padding(8)
    };

    private static FlowLayoutPanel ButtonRow() => new()
    {
        Dock = DockStyle.Bottom,
        Height = 38,
        FlowDirection = FlowDirection.LeftToRight,
        WrapContents = false
    };

    private static Button ActionButton(string text, EventHandler click)
    {
        var button = new Button { Text = text, Width = 120, Height = 32 };
        button.Click += click;
        return button;
    }

    private void ConfigurePcList()
    {
        pcList.View = View.Details;
        pcList.FullRowSelect = true;
        pcList.MultiSelect = true;
        pcList.Dock = DockStyle.Fill;
        pcList.Columns.Add("NAME", 310);
        pcList.Columns.Add("SIZE", 100, HorizontalAlignment.Right);
        pcList.Columns.Add("TYPE", 100);
        pcList.SelectedIndexChanged += (_, _) => UpdateActionState();
        pcList.DoubleClick += (_, _) =>
        {
            if (pcList.SelectedItems.Count != 1) return;
            var path = pcList.SelectedItems[0].Tag as string;
            if (path is null || !Directory.Exists(path)) return;
            currentFolder = path;
            RefreshPcFiles();
        };
    }

    private void ChooseFolder()
    {
        using var dialog = new FolderBrowserDialog
        {
            Description = "Choose the PC folder TI-JACK should browse",
            UseDescriptionForTitle = true,
            SelectedPath = currentFolder ??
                Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments)
        };

        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        currentFolder = dialog.SelectedPath;
        RefreshPcFiles();
    }

    private void RefreshPcFiles()
    {
        pcList.BeginUpdate();
        try
        {
            pcList.Items.Clear();

            if (string.IsNullOrWhiteSpace(currentFolder) || !Directory.Exists(currentFolder))
            {
                pcFolderLabel.Text = "NO FOLDER SELECTED";
                return;
            }

            pcFolderLabel.Text = currentFolder;

            foreach (var directory in Directory.EnumerateDirectories(currentFolder)
                         .OrderBy(x => x, StringComparer.OrdinalIgnoreCase))
            {
                var info = new DirectoryInfo(directory);
                var item = new ListViewItem(info.Name) { Tag = directory };
                item.SubItems.Add("");
                item.SubItems.Add("FOLDER");
                pcList.Items.Add(item);
            }

            foreach (var file in Directory.EnumerateFiles(currentFolder)
                         .OrderBy(x => x, StringComparer.OrdinalIgnoreCase))
            {
                var info = new FileInfo(file);
                var item = new ListViewItem(info.Name) { Tag = file };
                item.SubItems.Add(FormatBytes(info.Length));
                item.SubItems.Add(FileBadge(info.Extension));
                pcList.Items.Add(item);
            }
        }
        catch (Exception ex)
        {
            detailStatus.Text = "PC FOLDER ERROR · " + ex.Message;
        }
        finally
        {
            pcList.EndUpdate();
            UpdateActionState();
        }
    }

    private void DeletePcSelection()
    {
        var paths = pcList.SelectedItems
            .Cast<ListViewItem>()
            .Select(i => i.Tag as string)
            .Where(p => p is not null && File.Exists(p))
            .Cast<string>()
            .ToList();

        if (paths.Count == 0) return;

        if (settings.ConfirmDestructive &&
            MessageBox.Show(
                this,
                $"Delete {paths.Count} selected PC file{(paths.Count == 1 ? "" : "s")}?",
                "Delete PC files",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes)
            return;

        var failed = 0;
        foreach (var path in paths)
        {
            try { File.Delete(path); }
            catch { failed++; }
        }

        RefreshPcFiles();
        detailStatus.Text = $"{paths.Count - failed} DELETED · {failed} FAILED";
    }

    private void RefreshDevices()
    {
        devices.Clear();
        devices.AddRange(WindowsUsbDiscovery.FindTexasInstrumentsDevices());

        deviceBox.BeginUpdate();
        try
        {
            deviceBox.Items.Clear();
            foreach (var device in devices)
                deviceBox.Items.Add($"{device.DisplayName} · {device.VidPid}");
        }
        finally
        {
            deviceBox.EndUpdate();
        }

        if (deviceBox.Items.Count > 0)
            deviceBox.SelectedIndex = 0;
        else
        {
            backend = null;
            connectionStatus.Text = "● NO SUPPORTED TI CALCULATOR DETECTED";
            detailStatus.Text = "Connect a TI-84 Evo or TI-Nspire CX II/CX II CAS, then click RESCAN.";
            calculatorList.Items.Clear();
            UpdateActionState();
        }

        ApplyTheme();
    }

    private void SelectDevice()
    {
        if (deviceBox.SelectedIndex < 0 || deviceBox.SelectedIndex >= devices.Count)
        {
            backend = null;
            UpdateActionState();
            return;
        }

        var device = devices[deviceBox.SelectedIndex];
        backend = new ProbeBackend(device);
        connectionStatus.Text = "● " + device.DisplayName.ToUpperInvariant() + " DETECTED";
        detailStatus.Text = backend.Status;

        calculatorList.Items.Clear();
        var placeholder = new ListViewItem("(backend not enabled yet)");
        placeholder.SubItems.Add("");
        placeholder.SubItems.Add("PROBE");
        calculatorList.Items.Add(placeholder);

        UpdateActionState();
        ApplyTheme();
    }

    private void UpdateActionState()
    {
        var caps = backend?.Capabilities ?? CalculatorCapabilities.None;
        sendButton.Enabled = pcList.SelectedItems.Count > 0 &&
                             caps.HasFlag(CalculatorCapabilities.Send);
        saveButton.Enabled = calculatorList.SelectedItems.Count > 0 &&
                             caps.HasFlag(CalculatorCapabilities.Receive);
        calcDeleteButton.Enabled = calculatorList.SelectedItems.Count > 0 &&
                                   caps.HasFlag(CalculatorCapabilities.Delete);
        calcSelectAllButton.Enabled = calculatorList.Items.Count > 0 &&
                                      caps.HasFlag(CalculatorCapabilities.Browse);
        calcClearButton.Enabled = calculatorList.SelectedItems.Count > 0;
        pcDeleteButton.Enabled = pcList.SelectedItems
            .Cast<ListViewItem>()
            .Any(i => i.Tag is string path && File.Exists(path));

        sendButton.Text = pcList.SelectedItems.Count == 0
            ? "SEND SELECTED →"
            : $"SEND {pcList.SelectedItems.Count} →";
        saveButton.Text = calculatorList.SelectedItems.Count == 0
            ? "← SAVE SELECTED"
            : $"← SAVE {calculatorList.SelectedItems.Count}";
    }

    private void ApplyClassroomMode()
    {
        if (settings.KeepAwake)
            NativePower.KeepAwake();
        else
            NativePower.AllowSleep();

        calcDeleteButton.Visible = !settings.SimpleClassroomUi;
    }

    private void ApplyTheme()
    {
        var palette = Themes.Get(settings.Theme);
        BackColor = palette.Background;
        ForeColor = palette.Accent;
        ApplyThemeRecursive(this, palette);
        connectionStatus.ForeColor = backend is null ? palette.Accent : palette.Good;
        detailStatus.ForeColor = palette.Dim;
        pcDeleteButton.BackColor = palette.Danger;
        pcDeleteButton.ForeColor = palette.OnAccent;
        calcDeleteButton.BackColor = palette.Danger;
        calcDeleteButton.ForeColor = palette.OnAccent;
    }

    private static void ApplyThemeRecursive(Control root, ThemePalette p)
    {
        foreach (Control control in root.Controls)
        {
            switch (control)
            {
                case Button button:
                    button.BackColor = p.Accent;
                    button.ForeColor = p.OnAccent;
                    button.FlatStyle = FlatStyle.Flat;
                    button.FlatAppearance.BorderSize = 0;
                    break;
                case ListView list:
                    list.BackColor = p.Panel;
                    list.ForeColor = p.Accent;
                    break;
                case ComboBox combo:
                    combo.BackColor = p.Panel;
                    combo.ForeColor = p.Accent;
                    break;
                case TextBox text:
                    text.BackColor = p.Panel;
                    text.ForeColor = p.Accent;
                    break;
                default:
                    control.BackColor = p.Background;
                    control.ForeColor = p.Accent;
                    break;
            }

            ApplyThemeRecursive(control, p);
        }
    }

    private static string FileBadge(string extension)
    {
        var ext = extension.ToLowerInvariant();
        if (ext == ".tns") return "NSPIRE";
        if (ext is ".png" or ".jpg" or ".jpeg" or ".webp" or ".gif") return "MEDIA";
        if (ext == ".8xp") return "LEGACY";
        if (ext.EndsWith("2", StringComparison.Ordinal)) return "EVO";
        return ext.TrimStart('.').ToUpperInvariant();
    }

    private static string FormatBytes(long value) => value switch
    {
        < 1024 => $"{value} B",
        < 1024 * 1024 => $"{value / 1024.0:F1} KB",
        _ => $"{value / (1024.0 * 1024.0):F1} MB"
    };
}

internal static class NativePower
{
    private const uint ES_CONTINUOUS = 0x80000000;
    private const uint ES_SYSTEM_REQUIRED = 0x00000001;
    private const uint ES_DISPLAY_REQUIRED = 0x00000002;

    [System.Runtime.InteropServices.DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint esFlags);

    public static void KeepAwake() =>
        SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED);

    public static void AllowSleep() =>
        SetThreadExecutionState(ES_CONTINUOUS);
}
