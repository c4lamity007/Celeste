param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [string]$DependencyCache = (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1')
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$outputPath = [IO.Path]::GetFullPath($OutputRoot)
$javac = Join-Path $JdkHome 'bin\javac.exe'
$java = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) {
    throw 'JDK 21 is required; pass -JdkHome with an existing installation.'
}
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|\s|$)') { throw "Expected JDK 21; got $version" }
$lwjglCache = Join-Path $DependencyCache 'org.lwjgl'
if (-not (Test-Path -LiteralPath $lwjglCache)) { throw "LWJGL cache missing: $lwjglCache" }
if (Get-Command rg -ErrorAction SilentlyContinue) {
    $candidates = @(rg --files $lwjglCache -g '*.jar')
} else {
    $candidates = @(Get-ChildItem -LiteralPath $lwjglCache -Recurse -File -Filter '*.jar' | ForEach-Object { $_.FullName })
}
$libraries = @($candidates | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
} | Sort-Object)
if ($libraries.Count -ne 8) { throw "Expected eight cached LWJGL 3.3.3 Windows jars; found $($libraries.Count). No downloads attempted." }
$minecraftJar = Join-Path $projectPath '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
if (-not (Test-Path -LiteralPath $minecraftJar)) { throw 'The selected project has no exact merged Minecraft 1.21.11 dependency cache.' }
$classes = Join-Path $outputPath 'classes'
$geometryClasses = Join-Path $outputPath 'geometry-classes'
New-Item -ItemType Directory -Force -Path $classes, $geometryClasses | Out-Null
$classpath = $libraries -join ';'

function Invoke-TrajectoryQaProcess {
    param([string]$Executable, [string[]]$Arguments, [string]$LogPath, [string]$Label)
    $processOutput = @(& $Executable @Arguments 2>&1)
    $processExit = $LASTEXITCODE
    $processOutput | Set-Content -LiteralPath $LogPath -Encoding utf8
    if ($processExit -ne 0) {
        $tail = ($processOutput | Select-Object -Last 8 | Out-String).Trim()
        throw "$Label failed (exit $processExit). Log: $LogPath`n$tail"
    }
    return $processOutput
}

$package = Join-Path $projectPath 'src\main\java\dev\c4lam\celeste\module\impl\visual\expansion'
$stubRoot = Join-Path $PSScriptRoot 'trajectory-stubs'
if (-not (Test-Path -LiteralPath $stubRoot)) { throw "Trajectory geometry stubs missing: $stubRoot" }
$stubs = @(Get-ChildItem -LiteralPath $stubRoot -Recurse -File -Filter '*.java' | ForEach-Object { $_.FullName })
if ($stubs.Count -eq 0) { throw 'No value-only trajectory geometry stubs found.' }
$geometryArguments = @(
    '--release', '21', '-d', $geometryClasses,
    (Join-Path $package 'TrajectoryAppearance.java'), (Join-Path $package 'ConnectedRibbon.java'),
    (Join-Path $package 'GlowTrail.java'),
    (Join-Path $PSScriptRoot 'TrajectoryGeometryCheck.java')
) + $stubs
$null = Invoke-TrajectoryQaProcess -Executable $javac -Arguments $geometryArguments `
    -LogPath (Join-Path $outputPath 'geometry-compile.log') -Label 'Trajectory geometry harness compilation'
$geometryResults = @(Invoke-TrajectoryQaProcess -Executable $java -Arguments @(
    '-classpath', $geometryClasses, 'dev.c4lam.celeste.module.impl.visual.expansion.TrajectoryGeometryCheck'
) -LogPath (Join-Path $outputPath 'geometry-run.log') -Label 'Trajectory geometry checks')
$geometryResults | Where-Object { "$_" -match '^PASS' } | Write-Output

$null = Invoke-TrajectoryQaProcess -Executable $javac -Arguments @(
    '--release', '21', '-classpath', "$geometryClasses;$classpath", '-d', $classes,
    (Join-Path $projectPath 'tools\qa\ImpactCheck.java'),
    (Join-Path $PSScriptRoot 'TrajectoryMaterialCheck.java')
) -LogPath (Join-Path $outputPath 'compile.log') -Label 'Trajectory GPU harness compilation'
$results = @(Invoke-TrajectoryQaProcess -Executable $java -Arguments @(
    '-Djava.awt.headless=true', '-classpath', "$classes;$geometryClasses;$classpath", 'TrajectoryMaterialCheck',
    (Join-Path $projectPath 'src\main\resources'), $minecraftJar, $outputPath
) -LogPath (Join-Path $outputPath 'run.log') -Label 'Trajectory GPU checks')
$results | Where-Object { "$_" -match '^(PASS|GPU PREVIEW)' } | Write-Output
