param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$JdkHome = '',
    [string]$DependencyCache = (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'),
    [ValidateRange(32, 2048)][int]$Tile = 320
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$outputPath = [System.IO.Path]::GetFullPath($OutputRoot)

function Resolve-QaJdk21 {
    param([string]$Requested)
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($Requested) {
        $candidates.Add($Requested)
    } else {
        if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
        $candidates.Add((Join-Path $env:USERPROFILE '.cache\codex-jdks\temurin-21'))
        $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($javacCommand) { $candidates.Add((Split-Path -Parent (Split-Path -Parent $javacCommand.Source))) }
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        $javac = Join-Path $candidate 'bin\javac.exe'
        $java = Join-Path $candidate 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) { continue }
        $version = (& $javac -version 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $version -match '^javac 21(?:\.|\s|$)') {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'JDK 21 was not found. Pass -JdkHome with a Java 21 installation containing bin\java.exe and bin\javac.exe.'
}

function Invoke-QaProcess {
    param([string]$Executable, [string[]]$Arguments, [string]$LogPath, [string]$Label)
    $processOutput = @(& $Executable @Arguments 2>&1)
    $processExit = $LASTEXITCODE
    if ($processOutput.Count -eq 0) {
        "[No process output; exit $processExit]" | Set-Content -LiteralPath $LogPath -Encoding utf8
    } else {
        $processOutput | Set-Content -LiteralPath $LogPath -Encoding utf8
    }
    if ($processExit -ne 0) {
        $tail = ($processOutput | Select-Object -Last 8 | Out-String).Trim()
        throw "$Label failed (exit $processExit). Log: $LogPath`n$tail"
    }
    return $processOutput
}

$resolvedJdk = Resolve-QaJdk21 -Requested $JdkHome
$resources = Join-Path $projectPath 'src\main\resources'
$minecraftJar = Join-Path $projectPath '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
if (-not (Test-Path -LiteralPath $minecraftJar -PathType Leaf)) {
    throw 'The selected project is missing its exact merged Minecraft 1.21.11 cache. This runner does not download dependencies or use another project cache.'
}
$lwjglCache = Join-Path $DependencyCache 'org.lwjgl'
if (-not (Test-Path -LiteralPath $lwjglCache)) { throw "LWJGL dependency cache missing: $lwjglCache" }
if (Get-Command rg -ErrorAction SilentlyContinue) {
    $jarCandidates = @(rg --files $lwjglCache -g '*.jar')
} else {
    $jarCandidates = @(Get-ChildItem -LiteralPath $lwjglCache -Recurse -File -Filter '*.jar' | ForEach-Object { $_.FullName })
}
$libraries = @($jarCandidates | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [System.IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
} | Sort-Object)
if ($libraries.Count -ne 8) { throw "Expected eight LWJGL 3.3.3 Java/Windows-native jars, found $($libraries.Count). No dependency download is attempted." }

$package = Join-Path $projectPath 'src\main\java\dev\c4lam\celeste\module\impl\visual\expansion'
$qaSources = Join-Path $projectPath 'tools\qa'
$sources = @(
    (Join-Path $package 'SolidVisualMesh.java'),
    (Join-Path $package 'VolumeSurfaceMesh.java'),
    (Join-Path $package 'TargetAuraShape.java'),
    (Join-Path $package 'BlockHighlightMesh.java'),
    (Join-Path $qaSources 'ImpactCheck.java'),
    (Join-Path $qaSources 'ChamsAuroraCheck.java'),
    (Join-Path $qaSources 'PrismMaterialCheck.java'),
    (Join-Path $qaSources 'VisualShowcase.java'),
    (Join-Path $qaSources 'VolumeShowcase.java'),
    (Join-Path $qaSources 'UnifiedVolumeCheck.java'),
    (Join-Path $qaSources 'BlockHighlightMeshCheck.java'),
    (Join-Path $qaSources 'BlockHighlightDepthCheck.java')
)
foreach ($source in $sources) {
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Required block highlight check source missing: $source" }
}
# Unique classes ensure a failed compilation cannot run a previous passing build.
$classes = Join-Path $outputPath ('classes-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$classpath = $libraries -join ';'
$null = Invoke-QaProcess -Executable (Join-Path $resolvedJdk 'bin\javac.exe') -Arguments (
    @('--release', '21', '-classpath', $classpath, '-d', $classes) + $sources
) -LogPath (Join-Path $outputPath 'compile.log') -Label 'Block highlight harness compilation'
$shapeLog = @(Invoke-QaProcess -Executable (Join-Path $resolvedJdk 'bin\java.exe') -Arguments @(
    '-classpath', $classes, 'BlockHighlightMeshCheck'
) -LogPath (Join-Path $outputPath 'shape.log') -Label 'Block highlight pure shape checks')
$shapeSummary = ($shapeLog | Where-Object { "$_" -match '^PASS BlockHighlightMesh:' } | Select-Object -Last 1)
if (-not $shapeSummary) { throw "Block highlight shape check exited without its PASS summary. Log: $(Join-Path $outputPath 'shape.log')" }
$depthLog = @(Invoke-QaProcess -Executable (Join-Path $resolvedJdk 'bin\java.exe') -Arguments @(
    '-Djava.awt.headless=true', "-Dceleste.test.tile=$Tile", '-classpath', "$classes;$classpath",
    'BlockHighlightDepthCheck', $resources, $minecraftJar, $outputPath
) -LogPath (Join-Path $outputPath 'depth.log') -Label 'Block highlight GPU depth checks')
$depthSummary = ($depthLog | Where-Object { "$_" -match '^PASS BlockHighlightDepth:' } | Select-Object -Last 1)
if (-not $depthSummary) { throw "Block highlight depth check exited without its PASS summary. Log: $(Join-Path $outputPath 'depth.log')" }
Write-Output $shapeSummary
Write-Output $depthSummary
Write-Output "Block highlight pure/GPU regression only, not in-game verification; logs/artifacts=$outputPath"
