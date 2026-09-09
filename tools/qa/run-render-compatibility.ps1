param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [string]$CompiledClasses = '',
    [string]$IrisJar = '',
    [string]$DependencyCache = (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1')
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$taskProject = (Resolve-Path -LiteralPath $ProjectRoot).Path
if (-not $CompiledClasses) { $CompiledClasses = Join-Path $taskProject 'build\classes\java\main' }
if (-not $IrisJar) {
    $IrisJar = Join-Path $env:APPDATA 'ModrinthApp\profiles\Fabric 1.21.11\mods\iris-fabric-1.10.7+mc1.21.11.jar'
    if (-not (Test-Path -LiteralPath $IrisJar)) { $IrisJar += '.disabled' }
}
if (-not (Test-Path -LiteralPath $IrisJar)) { throw 'Pass -IrisJar with an installed Iris artifact; this test does not download or enable it.' }
$taskOutput = [IO.Path]::GetFullPath($OutputRoot)
$taskClasses = Join-Path $taskOutput 'classes'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskLibraries = @(rg --files (Join-Path $DependencyCache 'org.ow2.asm') -g 'asm-9.9.jar' -g 'asm-tree-9.9.jar' -g 'asm-commons-9.9.jar')
$taskLibraries += @(rg --files (Join-Path $DependencyCache 'org.slf4j\slf4j-api\2.0.17') -g '*.jar')
$taskLibraries += @(rg --files (Join-Path $DependencyCache 'org.joml\joml\1.10.8') -g 'joml-1.10.8.jar')
if ($taskLibraries.Count -ne 5) { throw 'Expected five exact cached test dependencies; no downloads are attempted.' }
$taskClasspath = (@($taskClasses, $CompiledClasses) + $taskLibraries) -join ';'
$taskCompiler = Join-Path $JdkHome 'bin\javac.exe'
$taskJava = Join-Path $JdkHome 'bin\java.exe'
& $taskCompiler --release 21 -proc:none -classpath $taskClasspath -d $taskClasses (Join-Path $taskProject 'tools\qa\RenderCompatibilityCheck.java') 2>&1 | Set-Content -LiteralPath (Join-Path $taskOutput 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Render compatibility test compilation failed; see compile.log.' }
$taskLines = @(& $taskJava -classpath $taskClasspath RenderCompatibilityCheck $CompiledClasses $IrisJar 2>&1)
$taskCode = $LASTEXITCODE
$taskLines | Set-Content -LiteralPath (Join-Path $taskOutput 'run.log')
if ($taskCode -ne 0) { throw "Render compatibility test failed:`n$(($taskLines | Select-Object -Last 12 | Out-String).Trim())" }
$taskLines | Where-Object { "$_" -match '^PASS ' } | Write-Output
