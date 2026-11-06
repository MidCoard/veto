param(
    [ValidateSet('Start', 'Stop', 'Status', 'Install', 'Uninstall', 'Run')]
    [string]$Action = 'Start',
    [string]$Config = (Join-Path $PSScriptRoot 'work/tmp/training-service/config.json')
)
$ErrorActionPreference = 'Stop'
$configPath = (Resolve-Path -LiteralPath $Config).Path
$settings = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$url = "http://127.0.0.1:$($settings.port)"
$startup = [Environment]::GetFolderPath('Startup')
$shortcutPath = Join-Path $startup 'Veto Local Service.lnk'
$powerShell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
$runArguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`" -Action Run -Config `"$configPath`""

function Read-State {
    Invoke-RestMethod -Uri "$url/api/state" -TimeoutSec 5 -ErrorAction Stop
}

function Install-Service {
    if (-not (Test-Path -LiteralPath $settings.servicePython)) { throw 'Service Python missing.' }
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = $powerShell
    $shortcut.Arguments = $runArguments
    $shortcut.WorkingDirectory = $PSScriptRoot
    $shortcut.WindowStyle = 7
    $shortcut.Description = 'Veto backend and local training management'
    $shortcut.Save()
}

switch ($Action) {
    'Install' { Install-Service; Write-Output 'Installed: Veto starts at user sign-in. No training starts automatically.' }
    'Start' {
        Install-Service
        try {
            $null = Read-State
            Write-Output "Service is already running: $url"
        } catch {
            $process = Start-Process -FilePath $powerShell -ArgumentList $runArguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -ErrorAction Stop
            Write-Output "Service start requested (supervisor PID $($process.Id)). Training page: $url"
        }
    }
    'Status' { Read-State | Select-Object backend, frontend, busy, stopping, runs | ConvertTo-Json -Depth 5 }
    'Stop' {
        $state = Read-State
        Invoke-RestMethod -Uri "$url/api/service/stop" -Method Post -ContentType 'application/json' `
            -Headers @{Origin=$url; 'X-Veto-CSRF'=$state.csrf} `
            -Body (@{revision=$state.revision} | ConvertTo-Json) -TimeoutSec 35 -ErrorAction Stop | Out-Null
        Write-Output 'Stop requested. The service exits after training saves or finishes exporting; check Status for confirmation.'
    }
    'Uninstall' {
        try { $null = Read-State; $running = $true } catch { $running = $false }
        if ($running) { throw 'Stop the service and wait for it to exit before uninstalling.' }
        if (Test-Path -LiteralPath $shortcutPath) { Remove-Item -LiteralPath $shortcutPath -ErrorAction Stop }
        Write-Output 'Automatic startup removed. Model and checkpoint files are retained.'
    }
    'Run' {
        $hasher = [System.Security.Cryptography.SHA256]::Create()
        try { $hash = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($PSScriptRoot))).Replace('-', '') }
        finally { $hasher.Dispose() }
        $mutex = [System.Threading.Mutex]::new($false, "Local\VetoService-$hash")
        $owned = $false
        try {
            try { $owned = $mutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $owned = $true }
            if (-not $owned) { exit 0 }
            $script = Join-Path $PSScriptRoot 'veto-training/service/server.py'
            for ($attempt = 0; $attempt -lt 3; $attempt++) {
                $child = Start-Process -FilePath $settings.servicePython -WorkingDirectory $PSScriptRoot -WindowStyle Hidden `
                    -ArgumentList "-B `"$script`" `"$configPath`"" -PassThru -Wait -ErrorAction Stop
                if ($child.ExitCode -eq 0) { exit 0 }
                if ($attempt -lt 2) { Start-Sleep -Seconds 60 }
            }
            exit 1
        } finally {
            if ($owned) { $mutex.ReleaseMutex() }
            $mutex.Dispose()
        }
    }
}
