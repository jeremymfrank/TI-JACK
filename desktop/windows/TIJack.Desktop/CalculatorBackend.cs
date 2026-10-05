namespace TIJack.Desktop;

[Flags]
internal enum CalculatorCapabilities
{
    None = 0,
    Browse = 1 << 0,
    Send = 1 << 1,
    Receive = 1 << 2,
    Delete = 1 << 3,
    Rename = 1 << 4,
    CreateFolder = 1 << 5,
    MemoryMove = 1 << 6
}

internal interface ICalculatorBackend
{
    string Name { get; }
    CalculatorCapabilities Capabilities { get; }
    string Status { get; }
}

internal sealed class ProbeBackend : ICalculatorBackend
{
    public ProbeBackend(CalculatorDevice device)
    {
        Device = device;
    }

    public CalculatorDevice Device { get; }
    public string Name => Device.DisplayName;
    public CalculatorCapabilities Capabilities => CalculatorCapabilities.None;

    public string Status => Device.Kind switch
    {
        CalculatorKind.Ti84Evo =>
            "EVO DETECTED · WINDOWS TRANSFER BACKEND IS BEING PORTED FROM THE VERIFIED ANDROID TRANSPORT",
        CalculatorKind.TiNspireCxIi =>
            "CX II DETECTED · PROTOCOL PROBE ONLY UNTIL USB SESSION/LISTING IS VERIFIED ON HARDWARE",
        _ => "TI USB DEVICE DETECTED · UNSUPPORTED MODEL"
    };
}
