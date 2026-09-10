param(
    [Parameter(Mandatory = $true)]
    [string]$Jar
)

$ErrorActionPreference = 'Stop'
$repoRoot = $PSScriptRoot
$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$runtimeDir = Join-Path $repoRoot 'work/tmp/backend-runtime'
$auditDir = Join-Path $repoRoot 'audit'
New-Item -ItemType Directory -Path $runtimeDir -ErrorAction SilentlyContinue | Out-Null
$runtimeJar = Join-Path $runtimeDir ('veto-' + [guid]::NewGuid().ToString() + '.jar')
Copy-Item -LiteralPath $jarPath -Destination $runtimeJar
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$launchId = Get-Date -Format 'yyyyMMdd-HHmmss'
$process = Start-Process -FilePath $java -WindowStyle Hidden -WorkingDirectory $repoRoot `
    -ArgumentList @('--enable-native-access=ALL-UNNAMED',
        "`"-Djava.io.tmpdir=$runtimeDir`"", "`"-Djdk.net.unixdomain.tmpdir=$runtimeDir`"",
        "`"-Dveto.observability.audit-log-path=$auditDir`"", '-jar', "`"$runtimeJar`"") `
    -RedirectStandardOutput (Join-Path $runtimeDir "$launchId-out.log") `
    -RedirectStandardError (Join-Path $runtimeDir "$launchId-err.log") -PassThru
Write-Output "Backend process started: PID=$($process.Id); cwd=$repoRoot; audit=$auditDir"
Write-Output "Startup logs: $runtimeDir/$launchId-out.log (check readiness before use)"
