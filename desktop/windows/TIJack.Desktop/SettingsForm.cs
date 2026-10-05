namespace TIJack.Desktop;

internal sealed class SettingsForm : Form
{
    private readonly AppSettings settings;
    private readonly ComboBox theme = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly CheckBox repeatSend = new() { Text = "Repeat Send — keep PC files selected" };
    private readonly CheckBox keepAwake = new() { Text = "Keep PC awake during transfers" };
    private readonly CheckBox simpleUi = new() { Text = "Simple Classroom UI" };
    private readonly CheckBox confirmDestructive = new() { Text = "Confirm delete / destructive actions" };
    private readonly ComboBox sendDuplicates = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox receiveDuplicates = new() { DropDownStyle = ComboBoxStyle.DropDownList };

    public SettingsForm(AppSettings settings)
    {
        this.settings = settings;

        Text = "TI-JACK Settings";
        Width = 560;
        Height = 470;
        StartPosition = FormStartPosition.CenterParent;
        MinimizeBox = false;
        MaximizeBox = false;

        var tabs = new TabControl { Dock = DockStyle.Fill };
        tabs.TabPages.Add(BuildAppearance());
        tabs.TabPages.Add(BuildClassroom());
        tabs.TabPages.Add(BuildTransfers());
        tabs.TabPages.Add(BuildHelp());

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            Height = 50,
            FlowDirection = FlowDirection.RightToLeft,
            Padding = new Padding(8)
        };

        var save = new Button { Text = "SAVE", Width = 90, DialogResult = DialogResult.OK };
        var cancel = new Button { Text = "CANCEL", Width = 90, DialogResult = DialogResult.Cancel };
        var reset = new Button { Text = "RESET", Width = 90 };
        reset.Click += (_, _) =>
        {
            if (MessageBox.Show(
                    this,
                    "Reset TI-JACK desktop settings to defaults?",
                    "Reset settings",
                    MessageBoxButtons.YesNo,
                    MessageBoxIcon.Question) != DialogResult.Yes)
                return;

            settings.Reset();
            LoadValues();
        };

        buttons.Controls.Add(save);
        buttons.Controls.Add(cancel);
        buttons.Controls.Add(reset);

        Controls.Add(tabs);
        Controls.Add(buttons);

        AcceptButton = save;
        CancelButton = cancel;
        LoadValues();

        save.Click += (_, _) => SaveValues();
    }

    private TabPage BuildAppearance()
    {
        var page = NewPage("Appearance");
        theme.Items.AddRange(Themes.Names);
        page.Controls.Add(Row("Color theme", theme));
        return page;
    }

    private TabPage BuildClassroom()
    {
        var page = NewPage("Classroom");
        repeatSend.Dock = DockStyle.Top;
        keepAwake.Dock = DockStyle.Top;
        simpleUi.Dock = DockStyle.Top;
        page.Controls.Add(simpleUi);
        page.Controls.Add(keepAwake);
        page.Controls.Add(repeatSend);
        return page;
    }

    private TabPage BuildTransfers()
    {
        var page = NewPage("Transfers");
        sendDuplicates.Items.AddRange(["Ask", "Replace", "Skip"]);
        receiveDuplicates.Items.AddRange(["Ask", "Replace", "Skip", "Rename Copy"]);
        confirmDestructive.Dock = DockStyle.Top;
        page.Controls.Add(confirmDestructive);
        page.Controls.Add(Row("Receive duplicates", receiveDuplicates));
        page.Controls.Add(Row("Send duplicates", sendDuplicates));
        return page;
    }

    private TabPage BuildHelp()
    {
        var page = NewPage("Help / About");
        var text = new TextBox
        {
            Multiline = true,
            ReadOnly = true,
            BorderStyle = BorderStyle.None,
            Dock = DockStyle.Fill,
            ScrollBars = ScrollBars.Vertical,
            Text =
                "TI-JACK Desktop v0.1\r\n\r\n" +
                "Universal file transfer for TI calculators.\r\n\r\n" +
                "Current desktop milestone:\r\n" +
                "• Windows two-pane classroom UI\r\n" +
                "• persistent settings/themes\r\n" +
                "• local file browser\r\n" +
                "• TI USB device detection\r\n\r\n" +
                "Transport status:\r\n" +
                "• TI-84 Evo: verified on Android; Windows backend being ported\r\n" +
                "• TI-Nspire CX II/CX II CAS: USB/protocol probe in progress\r\n\r\n" +
                "Project: Jawatech / jeremymfrank\r\n" +
                "Independent project; not affiliated with or endorsed by Texas Instruments."
        };
        page.Controls.Add(text);
        return page;
    }

    private static TabPage NewPage(string title) => new(title)
    {
        Padding = new Padding(18)
    };

    private static Control Row(string label, Control control)
    {
        var panel = new TableLayoutPanel
        {
            AutoSize = true,
            Dock = DockStyle.Top,
            ColumnCount = 2,
            Padding = new Padding(0, 0, 0, 12)
        };
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 45));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 55));
        panel.Controls.Add(new Label
        {
            Text = label,
            AutoSize = true,
            Anchor = AnchorStyles.Left,
            Margin = new Padding(0, 7, 10, 0)
        }, 0, 0);
        control.Dock = DockStyle.Fill;
        panel.Controls.Add(control, 1, 0);
        return panel;
    }

    private void LoadValues()
    {
        theme.SelectedItem = settings.Theme;
        repeatSend.Checked = settings.RepeatSend;
        keepAwake.Checked = settings.KeepAwake;
        simpleUi.Checked = settings.SimpleClassroomUi;
        confirmDestructive.Checked = settings.ConfirmDestructive;
        sendDuplicates.SelectedItem = settings.SendDuplicates;
        receiveDuplicates.SelectedItem = settings.ReceiveDuplicates;
    }

    private void SaveValues()
    {
        settings.Theme = theme.SelectedItem?.ToString() ?? "Amber";
        settings.RepeatSend = repeatSend.Checked;
        settings.KeepAwake = keepAwake.Checked;
        settings.SimpleClassroomUi = simpleUi.Checked;
        settings.ConfirmDestructive = confirmDestructive.Checked;
        settings.SendDuplicates = sendDuplicates.SelectedItem?.ToString() ?? "Ask";
        settings.ReceiveDuplicates = receiveDuplicates.SelectedItem?.ToString() ?? "Rename Copy";
        settings.Save();
    }
}
