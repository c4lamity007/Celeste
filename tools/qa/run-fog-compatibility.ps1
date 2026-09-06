param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$JdkHome = '',
    [string]$SodiumJar = '',
    [string]$DependencyCache = (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'),
    [string]$CompiledClasses = ''
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$outputPath = [IO.Path]::GetFullPath($OutputRoot)
if (-not $CompiledClasses) { $CompiledClasses = Join-Path $projectPath 'build\classes\java\main' }
if (-not $SodiumJar) {
    $SodiumJar = Join-Path $env:APPDATA 'ModrinthApp\profiles\Fabric 1.21.11\mods\sodium-fabric-0.8.13+mc1.21.11.jar'
}
if (-not (Test-Path -LiteralPath $SodiumJar)) {
    throw 'Pass -SodiumJar pointing to the installed Sodium 0.8.13 Minecraft 1.21.11 jar. This regression does not download or install mods.'
}
$compiledCompatibility = Join-Path $CompiledClasses 'dev\c4lam\celeste\module\impl\visual\expansion\CustomFogCompatibility.class'
if (-not (Test-Path -LiteralPath $compiledCompatibility)) { throw 'Compile the edited client first: the actual CustomFogCompatibility.class is required.' }
$candidates = if ($JdkHome) { @($JdkHome) } else {
    @($env:JAVA_HOME, (Join-Path $env:USERPROFILE '.cache\codex-jdks\temurin-21')) | Where-Object { $_ }
}
$resolvedJdk = $null
foreach ($candidate in $candidates) {
    $compiler = Join-Path $candidate 'bin\javac.exe'
    if (Test-Path -LiteralPath $compiler) {
        $version = (& $compiler -version 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $version -match '^javac 21(?:\.|\s|$)') { $resolvedJdk = $candidate; break }
    }
}
if (-not $resolvedJdk) { throw 'Pass -JdkHome with an available Java 21 JDK.' }
$java = Join-Path $resolvedJdk 'bin\java.exe'
$javac = Join-Path $resolvedJdk 'bin\javac.exe'
$classes = Join-Path $outputPath 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
function Find-CachedFogJar {
    param([string]$Subdirectory, [string]$Filename)
    $found = @(rg --files (Join-Path $DependencyCache $Subdirectory) -g $Filename)
    if ($found.Count -ne 1) { throw "Expected exactly one cached $Filename under $Subdirectory. No downloads are attempted." }
    return $found[0]
}
$libraries = @(
    (Find-CachedFogJar 'org.ow2.asm\asm\9.9' 'asm-9.9.jar'),
    (Find-CachedFogJar 'org.ow2.asm\asm-tree\9.9' 'asm-tree-9.9.jar'),
    (Find-CachedFogJar 'org.ow2.asm\asm-commons\9.9' 'asm-commons-9.9.jar'),
    (Find-CachedFogJar 'org.joml\joml\1.10.8' 'joml-1.10.8.jar')
)
$yarn = Find-CachedFogJar 'net.fabricmc\yarn\1.21.11+build.6' 'yarn-1.21.11+build.6-v2.jar'
$minecraft = Join-Path $projectPath '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
if (-not (Test-Path -LiteralPath $minecraft)) { throw 'Selected project must have its exact cached Minecraft 1.21.11 merged jar.' }
function Invoke-FogProcess {
    param([string]$Executable, [string[]]$Arguments, [string]$Label)
    $lines = @(& $Executable @Arguments 2>&1)
    $code = $LASTEXITCODE
    $lines | Set-Content -LiteralPath (Join-Path $outputPath "$Label.log") -Encoding utf8
    if ($code -ne 0) { throw "$Label failed (exit $code):`n$(($lines | Select-Object -Last 12 | Out-String).Trim())" }
    return $lines
}
$classpath = (@($CompiledClasses, $minecraft) + $libraries) -join ';'
$null = Invoke-FogProcess $javac @('--release', '21', '-proc:none', '-classpath', $classpath, '-d', $classes,
    (Join-Path $projectPath 'tools\qa\FogCompatibilityCheck.java')) 'fog-compatibility-compile'
# Sodium is deliberately NOT on the runtime classpath: the fixture independently loads only
# its actual capture/parameter classes for the present branch and rejects them when absent.
$result = @(Invoke-FogProcess $java @('-Djoml.nounsafe=true', '-classpath', "$classes;$classpath", 'FogCompatibilityCheck',
    $CompiledClasses, $minecraft, $SodiumJar, $yarn) 'fog-compatibility-run')
$result | Where-Object { "$_" -match '^PASS Fog' } | Write-Output
