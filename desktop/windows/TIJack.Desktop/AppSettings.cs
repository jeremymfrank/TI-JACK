using System.Text.Json;

namespace TIJack.Desktop;

internal sealed class AppSettings
{
    public string Theme { get; set; } = "Amber";
    public bool RepeatSend { get; set; }
    public bool KeepAwake { get; set; }
    public bool ConfirmDestructive { get; set; } = true;
    public string SendDuplicates { get; set; } = "Ask";
    public string ReceiveDuplicates { get; set; } = "Rename Copy";
    public bool SimpleClassroomUi { get; set; }

    private static string SettingsPath =>
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "TI-JACK",
            "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (!File.Exists(SettingsPath))
                return new AppSettings();

            return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(SettingsPath))
                ?? new AppSettings();
        }
        catch
        {
            return new AppSettings();
        }
    }

    public void Save()
    {
        var directory = Path.GetDirectoryName(SettingsPath)!;
        Directory.CreateDirectory(directory);
        File.WriteAllText(
            SettingsPath,
            JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }

    public void Reset()
    {
        Theme = "Amber";
        RepeatSend = false;
        KeepAwake = false;
        ConfirmDestructive = true;
        SendDuplicates = "Ask";
        ReceiveDuplicates = "Rename Copy";
        SimpleClassroomUi = false;
        Save();
    }
}

internal readonly record struct ThemePalette(
    Color Background,
    Color Panel,
    Color Selection,
    Color Accent,
    Color Dim,
    Color Good,
    Color Danger,
    Color OnAccent);

internal static class Themes
{
    public static ThemePalette Get(string name) => name switch
    {
        "TI Blue" => new(
            Color.FromArgb(11, 17, 27), Color.FromArgb(16, 27, 43),
            Color.FromArgb(24, 57, 95), Color.FromArgb(88, 166, 255),
            Color.FromArgb(124, 150, 179), Color.FromArgb(113, 211, 138),
            Color.FromArgb(255, 123, 114), Color.FromArgb(11, 17, 27)),
        "Classic Green" => new(
            Color.FromArgb(7, 17, 10), Color.FromArgb(11, 27, 16),
            Color.FromArgb(20, 53, 29), Color.FromArgb(111, 226, 123),
            Color.FromArgb(111, 166, 118), Color.FromArgb(154, 245, 163),
            Color.FromArgb(242, 123, 117), Color.FromArgb(7, 17, 10)),
        "High Contrast" => new(
            Color.Black, Color.FromArgb(10, 10, 10),
            Color.FromArgb(39, 32, 0), Color.FromArgb(255, 216, 0),
            Color.FromArgb(215, 215, 215), Color.FromArgb(124, 255, 124),
            Color.FromArgb(255, 112, 112), Color.Black),
        "Light Classroom" => new(
            Color.FromArgb(243, 246, 250), Color.White,
            Color.FromArgb(220, 235, 255), Color.FromArgb(17, 85, 163),
            Color.FromArgb(89, 102, 117), Color.FromArgb(37, 118, 58),
            Color.FromArgb(168, 42, 42), Color.White),
        _ => new(
            Color.FromArgb(17, 16, 14), Color.FromArgb(24, 22, 18),
            Color.FromArgb(42, 36, 21), Color.FromArgb(210, 166, 42),
            Color.FromArgb(142, 118, 45), Color.FromArgb(105, 185, 107),
            Color.FromArgb(183, 101, 94), Color.FromArgb(17, 16, 14))
    };

    public static readonly string[] Names =
        ["Amber", "TI Blue", "Classic Green", "High Contrast", "Light Classroom"];
}
