$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Test-Path -LiteralPath '.env')) {
    py scripts/init-env.py
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
$launchInfo = New-Object Diagnostics.ProcessStartInfo
$launchInfo.FileName = $env:ComSpec
$launchInfo.Arguments = '/d /s /c ""' + (Join-Path (Get-Location) 'mvnw.cmd') + '" spring-boot:run"'
$launchInfo.UseShellExecute = $false
$launchInfo.CreateNoWindow = $true
$envLineNumber = 0
foreach ($envLine in Get-Content -LiteralPath '.env' -Encoding UTF8) {
    $envLineNumber++
    $envEntry = $envLine.Trim()
    if (-not $envEntry -or $envEntry.StartsWith('#')) { continue }
    if ($envEntry -cnotmatch '^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        throw "Invalid .env format at line $envLineNumber."
    }
    $envName = $Matches[1]
    $envValue = $Matches[2].Trim()
    if ($envValue.StartsWith("'") -or $envValue.StartsWith('"')) {
        $envQuote = $envValue[0]
        $envQuoteEnd = $envValue.IndexOf($envQuote, 1)
        if ($envQuoteEnd -lt 1) { throw "Invalid .env format at line $envLineNumber." }
        $envSuffix = $envValue.Substring($envQuoteEnd + 1).Trim()
        if ($envSuffix -and -not $envSuffix.StartsWith('#')) {
            throw "Invalid .env format at line $envLineNumber."
        }
        $envValue = $envValue.Substring(1, $envQuoteEnd - 1)
    } else {
        $envValue = [regex]::Replace($envValue, '\s+#.*$', '').TrimEnd()
    }
    $launchInfo.EnvironmentVariables[$envName] = $envValue
}
$launchProcess = [Diagnostics.Process]::Start($launchInfo)
try {
    $launchProcess.WaitForExit()
    $launchExitCode = $launchProcess.ExitCode
} finally { $launchProcess.Dispose() }
exit $launchExitCode
