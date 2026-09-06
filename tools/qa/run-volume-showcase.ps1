param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$JdkHome = 'C:\Users\c4lam\.cache\codex-jdks\temurin-21',
    [string]$DependencyCache = 'C:\Users\c4lam\.gradle\caches\modules-2\files-2.1'
)
$ErrorActionPreference = 'Stop'
$libraries = @(rg --files (Join-Path $DependencyCache 'org.lwjgl') -g '*.jar' | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
})
if ($libraries.Count -ne 8) { throw 'Expected eight cached LWJGL3.3.3 Windows jars.' }
$minecraftJar = Join-Path $ProjectRoot '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
$classes = Join-Path $OutputRoot ('classes-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$classpath = $libraries -join ';'
$mesh = Join-Path $ProjectRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/SolidVisualMesh.java'
$surface = Join-Path $ProjectRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/VolumeSurfaceMesh.java'
$targetShape = Join-Path $ProjectRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/TargetAuraShape.java'
& (Join-Path $JdkHome 'bin/javac.exe') --release 21 -classpath $classpath -d $classes $mesh $surface $targetShape (Join-Path $PSScriptRoot 'ImpactCheck.java') (Join-Path $PSScriptRoot 'ChamsAuroraCheck.java') (Join-Path $PSScriptRoot 'PrismMaterialCheck.java') (Join-Path $PSScriptRoot 'VisualShowcase.java') (Join-Path $PSScriptRoot 'VolumeShowcase.java')
if ($LASTEXITCODE -ne 0) { throw 'Volume showcase compilation failed.' }
& (Join-Path $JdkHome 'bin/java.exe') -classpath "$classes;$classpath" VolumeShowcase (Join-Path $ProjectRoot 'src/main/resources') $minecraftJar $OutputRoot
if ($LASTEXITCODE -ne 0) { throw 'Volume showcase render failed.' }
