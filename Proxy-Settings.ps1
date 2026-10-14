# 网络设置辅助函数。仅在显式选择 -UseSystemProxy 时调用。
function Save-HuatiProxy {
    param([string]$Path)
    $key = Get-Item -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    $saved = @{}
    foreach ($name in @('ProxyEnable', 'ProxyServer', 'ProxyOverride', 'AutoConfigURL')) {
        $present = $key.GetValueNames() -contains $name
        $saved[$name] = @{ present = $present; value = $(if ($present) { $key.GetValue($name) } else { $null }) }
    }
    $connection = Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings\Connections' -ErrorAction SilentlyContinue
    if ($connection.DefaultConnectionSettings.Length -ge 12) {
        $saved['ConnectionFlags'] = [BitConverter]::ToInt32($connection.DefaultConnectionSettings, 8)
    }
    $saved | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Set-HuatiProxyConnection {
    param([int]$Flags, [string]$Server, [string]$Bypass, [string]$AutoConfig)
    if (-not ('HuatiProxyConnection' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class HuatiProxyConnection {
    [StructLayout(LayoutKind.Sequential)]
    struct Option { public int key; public IntPtr value; }
    [StructLayout(LayoutKind.Sequential)]
    struct Options { public int size; public IntPtr connection; public int count; public int error; public IntPtr options; }
    [DllImport("wininet.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool InternetSetOptionW(IntPtr handle, int option, ref Options buffer, int length);
    public static void Set(int flags, string server, string bypass, string autoConfig) {
        IntPtr[] strings = { Marshal.StringToHGlobalUni(server ?? ""), Marshal.StringToHGlobalUni(bypass ?? ""), Marshal.StringToHGlobalUni(autoConfig ?? "") };
        int itemSize = Marshal.SizeOf(typeof(Option));
        IntPtr items = Marshal.AllocHGlobal(itemSize * 4);
        try {
            Option[] options = { new Option { key=1, value=new IntPtr(flags) }, new Option { key=2, value=strings[0] }, new Option { key=3, value=strings[1] }, new Option { key=4, value=strings[2] } };
            for (int i=0; i<options.Length; i++) Marshal.StructureToPtr(options[i], IntPtr.Add(items, i*itemSize), false);
            Options list = new Options { size=Marshal.SizeOf(typeof(Options)), connection=IntPtr.Zero, count=4, error=0, options=items };
            if (!InternetSetOptionW(IntPtr.Zero, 75, ref list, list.size)) throw new Win32Exception(Marshal.GetLastWin32Error());
        } finally { Marshal.FreeHGlobal(items); foreach (IntPtr pointer in strings) Marshal.FreeHGlobal(pointer); }
    }
}
'@
    }
    [HuatiProxyConnection]::Set($Flags, $Server, $Bypass, $AutoConfig)
    Update-HuatiProxyNotification
}

function Update-HuatiProxyNotification {
    if (-not ('HuatiWinInet' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class HuatiWinInet {
    [DllImport("wininet.dll", SetLastError=true)]
    public static extern bool InternetSetOption(IntPtr handle, int option, IntPtr buffer, int length);
}
'@
    }
    [void][HuatiWinInet]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0)
    [void][HuatiWinInet]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0)
}

function Restore-HuatiProxy {
    param([string]$Path)
    $saved = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    $registryPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    $flags = if ($null -ne $saved.ConnectionFlags) { [int]$saved.ConnectionFlags } else {
        1 + $(if ($saved.ProxyEnable.value) { 2 } else { 0 }) + $(if ($saved.AutoConfigURL.value) { 4 } else { 0 })
    }
    Set-HuatiProxyConnection -Flags $flags -Server $saved.ProxyServer.value -Bypass $saved.ProxyOverride.value -AutoConfig $saved.AutoConfigURL.value
    foreach ($name in @('ProxyEnable', 'ProxyServer', 'ProxyOverride', 'AutoConfigURL')) {
        $entry = $saved.$name
        if ($entry.present) {
            $kind = if ($name -eq 'ProxyEnable') { 'DWord' } else { 'String' }
            New-ItemProperty -LiteralPath $registryPath -Name $name -Value $entry.value -PropertyType $kind -Force | Out-Null
        }
        else {
            Remove-ItemProperty -LiteralPath $registryPath -Name $name -ErrorAction SilentlyContinue
        }
    }
    Update-HuatiProxyNotification
}
