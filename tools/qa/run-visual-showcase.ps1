param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$StageRoot = $ProjectRoot,
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
& (Join-Path $JdkHome 'bin\javac.exe') --release 21 -classpath $classpath -d $classes (Join-Path $ProjectRoot 'tools\qa\ImpactCheck.java') (Join-Path $ProjectRoot 'tools\qa\ChamsAuroraCheck.java') (Join-Path $ProjectRoot 'tools\qa\PrismMaterialCheck.java') (Join-Path $PSScriptRoot 'VisualShowcase.java')
if ($LASTEXITCODE -ne 0) { throw 'Showcase compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -classpath "$classes;$classpath" VisualShowcase (Join-Path $ProjectRoot 'src\main\resources') $minecraftJar $OutputRoot (Join-Path $StageRoot 'src\main\resources')
if ($LASTEXITCODE -ne 0) { throw 'Showcase render failed.' }
