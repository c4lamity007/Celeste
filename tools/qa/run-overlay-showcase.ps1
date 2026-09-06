param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [Parameter(Mandatory=$true)][string]$Encoder,
    [string]$JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [string]$DependencyCache = (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1')
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$Encoder = (Resolve-Path -LiteralPath $Encoder).Path
$libraries = @(rg --files (Join-Path $DependencyCache 'org.lwjgl') -g '*.jar' | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
})
if($libraries.Count -ne 8){throw 'Expected eight cached LWJGL jars'}
$minecraftJar = Join-Path $ProjectRoot '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
$classes = Join-Path $OutputRoot ('classes-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$classpath = $libraries -join ';'
$package = Join-Path $ProjectRoot 'src\main\java\dev\c4lam\celeste\module\impl\visual\expansion'
$sources = @('SolidVisualMesh.java','VolumeSurfaceMesh.java','TargetAuraShape.java','BlockHighlightMesh.java') | ForEach-Object { Join-Path $package $_ }
$sources += @('ImpactCheck.java','ChamsAuroraCheck.java','PrismMaterialCheck.java','VisualShowcase.java','VolumeShowcase.java','OverlayShowcase.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
& (Join-Path $JdkHome 'bin\javac.exe') --release 21 -classpath $classpath -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Overlay preview compilation failed'}
& (Join-Path $JdkHome 'bin\java.exe') -Xmx768m -XX:MaxDirectMemorySize=256m -classpath "$classes;$classpath" OverlayShowcase (Join-Path $ProjectRoot 'src\main\resources') $minecraftJar $OutputRoot $Encoder
if($LASTEXITCODE -ne 0){throw 'Overlay preview render failed'}
