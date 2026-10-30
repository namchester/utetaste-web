<# Read-only preflight: no app startup, migrations, container mutation or secret output.
   Docker parses .env internally; raw compose/inspect output stays in process memory.
   -SkipDocker checks source and local ports without contacting Docker/PostgreSQL.
#>
param(
    [switch]$SkipDocker,
    [switch]$Json,
    [ValidatePattern('^[a-z0-9][a-z0-9_-]*$')][string]$ProjectName = 'utetaste'
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Invoke-DockerRead {
    param([string[]]$DockerArguments, [string]$InputText = '')
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = 'docker'
    # Arguments contain only controlled flags, paths, project/container identifiers.
    # Secrets are never passed in CLI arguments or returned on errors.
    $start.Arguments = ($DockerArguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' '
    $start.WorkingDirectory = $projectRoot
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.RedirectStandardInput = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'Cannot execute Docker.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if ($InputText) { $process.StandardInput.Write($InputText.Replace("`r`n", "`n")) }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(30000)) {
            $process.Kill() # Terminates this read-only CLI probe, never a container.
            throw 'Docker read-only probe timed out.'
        }
        if ($process.ExitCode -ne 0) { throw 'Docker/read-only SQL probe failed; raw output suppressed to protect credentials.' }
        return $stdout.GetAwaiter().GetResult()
    } finally { $process.Dispose() }
}

$pom = New-Object xml
$pom.Load((Join-Path $projectRoot 'pom.xml'))
$wrapper = [IO.File]::ReadAllText((Join-Path $projectRoot '.mvn/wrapper/maven-wrapper.properties'))
$mavenVersion = [regex]::Match($wrapper, 'apache-maven-([0-9.]+)-bin').Groups[1].Value
$migrationVersions = @(Get-ChildItem (Join-Path $projectRoot 'src/main/resources/db/migration/V*.sql') | ForEach-Object { [regex]::Match($_.Name, '^V([^_]+)__').Groups[1].Value })
$portState = @(foreach ($port in @(8080,5432,5433)) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    [pscustomobject]@{ Port=$port; Listening=($listeners.Count -gt 0) }
})
$report = [ordered]@{
    CheckedAt = (Get-Date).ToString('o')
    Java = [string]$pom.project.properties.'java.version'
    SpringBoot = [string]$pom.project.parent.version
    Maven = $mavenVersion
    MainClass = 'vn.utetaste.UtetasteApplication'
    StandardStructure = (@('src/main/java','src/main/resources','src/test/java','src/test/resources','mvnw.cmd','.mvn/wrapper/maven-wrapper.properties') | Where-Object { -not (Test-Path (Join-Path $projectRoot $_)) }).Count -eq 0
    EnvFilePresent = Test-Path (Join-Path $projectRoot '.env')
    SourceMigrations = $migrationVersions
    LocalPorts = $portState
    Docker = 'SKIPPED'
    Environment = @()
    Database = 'NOT TESTED'
    WindowsDatabaseConnection = 'NOT TESTED: database port must already be published; no container is changed by this script.'
}

if (-not $SkipDocker) {
    $compose = @('compose','--project-directory',$projectRoot,'-p',$ProjectName,'-f',(Join-Path $projectRoot 'compose.yml'))
    $config = (Invoke-DockerRead ($compose + @('config','--format','json'))) | ConvertFrom-Json
    $dbId = (Invoke-DockerRead ($compose + @('ps','-q','db'))).Trim()
    if (-not $dbId) { throw 'Existing database container is not running. No container was started.' }
    $db = @((Invoke-DockerRead @('inspect',$dbId)) | ConvertFrom-Json)[0]
    $appId = (Invoke-DockerRead ($compose + @('ps','-q','app'))).Trim()
    $app = if ($appId) { @((Invoke-DockerRead @('inspect',$appId)) | ConvertFrom-Json)[0] } else { $null }
    $report.Docker = [ordered]@{
        Project = $ProjectName
        DbContainer = $db.Name.TrimStart('/')
        DbRunning = [bool]$db.State.Running
        DbHealth = if ($db.State.Health) { $db.State.Health.Status } else { 'unavailable' }
        DbImage = $db.Config.Image
        DatabaseVolumes = @($db.Mounts | Where-Object { $_.Destination -eq '/var/lib/postgresql/data' } | Select-Object Type,Name,Destination)
        DatabasePortMappings = $db.NetworkSettings.Ports.'5432/tcp'
        AppContainerRunning = ($null -ne $app -and [bool]$app.State.Running)
        ComposeDbHostCorrect = ($config.services.app.environment.DB_URL -eq 'jdbc:postgresql://db:5432/utetaste')
    }
    $current = @{}
    if ($app) { foreach ($entry in $app.Config.Env) { $key,$value=$entry -split '=',2; $current[$key]=$value } }
    $variables = $config.services.app.environment
    $databaseVariables = @{}
    foreach ($entry in $db.Config.Env) { $key,$value=$entry -split '=',2; $databaseVariables[$key]=$value }
    $report.Environment = @(foreach ($key in @('DB_PASSWORD','JWT_SECRET','OTP_SECRET','ADMIN_PASSWORD','DEMO_PASSWORD','SMTP_HOST','SMTP_PORT','SMTP_USERNAME','SMTP_PASSWORD','MAIL_FROM','CLOUDINARY_CLOUD_NAME','CLOUDINARY_API_KEY','CLOUDINARY_API_SECRET')) {
        $value=[string]$variables.$key
        [pscustomobject]@{
            Variable=$key
            Present=(-not [string]::IsNullOrWhiteSpace($value))
            Minimum32Bytes=if ($key -in @('JWT_SECRET','OTP_SECRET')) { [Text.Encoding]::UTF8.GetByteCount($value) -ge 32 } else { $null }
            MatchesRunningApp=if ($app) { $value -ceq [string]$current[$key] } else { $null }
        }
    })
    $report.EnvironmentChecks = [ordered]@{
        DatabasePasswordMatchesConfiguredContainer = ([string]$variables.DB_PASSWORD -ceq [string]$databaseVariables.POSTGRES_PASSWORD)
        DatabaseUserMatchesConfiguredContainer = ([string]$variables.DB_USER -ceq [string]$databaseVariables.POSTGRES_USER)
        JwtOtpIndependent = (-not [string]::IsNullOrWhiteSpace([string]$variables.OTP_SECRET) -and [string]$variables.JWT_SECRET -cne [string]$variables.OTP_SECRET)
        SmtpPasswordNoWhitespace = (-not ([string]$variables.SMTP_PASSWORD -match '\s'))
        # Configuration parity is not a real SMTP/Cloudinary service test.
        RealSmtpCloudinary = 'NOT TESTED'
    }
    $sql = @'
PGOPTIONS='-c default_transaction_read_only=on' PGPASSWORD="$POSTGRES_PASSWORD" psql -XAt -h 127.0.0.1 -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'UTETASTE_READ_ONLY_SQL'
SELECT json_build_object(
  'postgresVersion', current_setting('server_version'),
  'credentialTcpInsideContainer', true,
  'readOnly', current_setting('transaction_read_only'),
  'migrations', (SELECT json_agg(x ORDER BY installed_rank) FROM (SELECT installed_rank, version, description, success FROM public.flyway_schema_history) x),
  'counts', json_build_object('users',(SELECT count(*) FROM app_users),'orders',(SELECT count(*) FROM food_orders),'invoices',(SELECT count(*) FROM invoices))
);
UTETASTE_READ_ONLY_SQL
exit
'@
    $database = (Invoke-DockerRead ($compose + @('exec','-T','db','sh','-s')) ($sql + "`n")) | ConvertFrom-Json
    $report.Database = $database
    $applied = @($database.migrations | Where-Object { $_.success } | ForEach-Object { $_.version })
    $report.PendingMigrationVersions = @($migrationVersions | Where-Object { $_ -notin $applied })
    $report.FailedMigrationVersions = @($database.migrations | Where-Object { -not $_.success } | ForEach-Object { $_.version })
    $report.MigrationCheckScope = 'Version/state inventory only; not a replacement for Flyway checksum validation.'
}

if ($Json) { [pscustomobject]$report | ConvertTo-Json -Depth 10 }
else {
    Write-Output ('Java {0}; Spring Boot {1}; Maven {2}' -f $report.Java,$report.SpringBoot,$report.Maven)
    Write-Output ('Main: ' + $report.MainClass)
    Write-Output ('Maven structure: ' + $report.StandardStructure)
    $report.LocalPorts | Format-Table -AutoSize
    if (-not $SkipDocker) {
        $report.Docker | ConvertTo-Json -Depth 5
        $report.Environment | Format-Table -AutoSize
        $report.EnvironmentChecks | ConvertTo-Json
        $report.Database | ConvertTo-Json -Depth 5
        Write-Output ('Pending migrations: ' + ($report.PendingMigrationVersions -join ', '))
    }
    Write-Output 'Read-only preflight finished. No app, migration, Docker port/volume or data was changed.'
}
