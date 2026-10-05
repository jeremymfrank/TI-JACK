using Microsoft.Win32;

namespace TIJack.Desktop;

internal enum CalculatorKind
{
    Ti84Evo,
    TiNspireCxIi,
    UnknownTi
}

internal sealed record CalculatorDevice(
    CalculatorKind Kind,
    string DisplayName,
    string VidPid,
    string InstancePath,
    string? FriendlyName);

internal static class WindowsUsbDiscovery
{
    private const string UsbEnumPath = @"SYSTEM\CurrentControlSet\Enum\USB";

    public static IReadOnlyList<CalculatorDevice> FindTexasInstrumentsDevices()
    {
        var result = new List<CalculatorDevice>();

        try
        {
            using var usb = Registry.LocalMachine.OpenSubKey(UsbEnumPath);
            if (usb is null) return result;

            foreach (var vidPidKeyName in usb.GetSubKeyNames())
            {
                if (!vidPidKeyName.StartsWith("VID_0451&PID_", StringComparison.OrdinalIgnoreCase))
                    continue;

                var kind = Classify(vidPidKeyName);
                using var vidPidKey = usb.OpenSubKey(vidPidKeyName);
                if (vidPidKey is null) continue;

                foreach (var instanceName in vidPidKey.GetSubKeyNames())
                {
                    using var instanceKey = vidPidKey.OpenSubKey(instanceName);
                    if (instanceKey is null) continue;

                    var friendly = ReadString(instanceKey, "FriendlyName")
                        ?? CleanDeviceDescription(ReadString(instanceKey, "DeviceDesc"));

                    var display = kind switch
                    {
                        CalculatorKind.Ti84Evo => "TI-84 Evo",
                        CalculatorKind.TiNspireCxIi => "TI-Nspire CX II / CX II CAS",
                        _ => friendly ?? "Texas Instruments USB device"
                    };

                    result.Add(new CalculatorDevice(
                        kind,
                        display,
                        FormatVidPid(vidPidKeyName),
                        $@"USB\{vidPidKeyName}\{instanceName}",
                        friendly));
                }
            }
        }
        catch
        {
        }

        return result
            .OrderBy(d => d.Kind)
            .ThenBy(d => d.DisplayName, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    public static string DiagnosticText(IReadOnlyList<CalculatorDevice> devices)
    {
        if (devices.Count == 0)
            return "No Texas Instruments USB devices are currently visible to Windows.";

        return string.Join(
            Environment.NewLine + Environment.NewLine,
            devices.Select(d =>
                $"{d.DisplayName}{Environment.NewLine}" +
                $"VID:PID {d.VidPid}{Environment.NewLine}" +
                $"PNP {d.InstancePath}{Environment.NewLine}" +
                $"Friendly name: {d.FriendlyName ?? "(none)"}"));
    }

    private static CalculatorKind Classify(string keyName)
    {
        if (keyName.Contains("PID_E018", StringComparison.OrdinalIgnoreCase))
            return CalculatorKind.Ti84Evo;
        if (keyName.Contains("PID_E022", StringComparison.OrdinalIgnoreCase))
            return CalculatorKind.TiNspireCxIi;
        return CalculatorKind.UnknownTi;
    }

    private static string FormatVidPid(string keyName)
    {
        var vid = Between(keyName, "VID_", "&") ?? "????";
        var pidIndex = keyName.IndexOf("PID_", StringComparison.OrdinalIgnoreCase);
        var pid = pidIndex >= 0 && keyName.Length >= pidIndex + 8
            ? keyName.Substring(pidIndex + 4, 4)
            : "????";
        return $"{vid}:{pid}".ToUpperInvariant();
    }

    private static string? Between(string value, string start, string end)
    {
        var a = value.IndexOf(start, StringComparison.OrdinalIgnoreCase);
        if (a < 0) return null;
        a += start.Length;
        var b = value.IndexOf(end, a, StringComparison.OrdinalIgnoreCase);
        return b < 0 ? value[a..] : value[a..b];
    }

    private static string? ReadString(RegistryKey key, string name) =>
        key.GetValue(name) as string;

    private static string? CleanDeviceDescription(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        var semicolon = value.LastIndexOf(';');
        return semicolon >= 0 && semicolon + 1 < value.Length
            ? value[(semicolon + 1)..]
            : value;
    }
}
