param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [string]$DependencyCache = 'C:\Users\c4lam\.gradle\caches\modules-2\files-2.1',
    [string]$MinecraftJar = ''
)
$ErrorActionPreference = 'Stop'
$qaProject = (Resolve-Path -LiteralPath $ProjectRoot).Path
$qaOutput = [IO.Path]::GetFullPath($OutputRoot)
$qaAllowedRoot = [IO.Path]::GetFullPath((Join-Path $qaProject 'build\module-qa'))
if (-not $qaOutput.StartsWith($qaAllowedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputRoot must be a fresh child of this project build/module-qa.'
}
if (Test-Path -LiteralPath $qaOutput) { throw 'Use a fresh OutputRoot; this runner never overwrites prior QA.' }
for ($qaAncestor = [IO.DirectoryInfo]::new($qaOutput); $null -ne $qaAncestor; $qaAncestor = $qaAncestor.Parent) {
    if ($qaAncestor.Exists -and ($qaAncestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'QA output cannot pass through a link or junction.'
    }
}
$qaLibraries = @(rg --files (Join-Path $DependencyCache 'org.lwjgl') -g '*.jar' | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
})
if ($qaLibraries.Count -ne 8) { throw 'Expected eight cached LWJGL3.3.3 Windows jars; no downloads are performed.' }
if (-not $MinecraftJar) {
    $MinecraftJar = Join-Path $qaProject '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
}
if (-not (Test-Path -LiteralPath $MinecraftJar -PathType Leaf)) { throw 'Cached Minecraft1.21.11 include JAR is missing.' }
$qaClasses = Join-Path $qaOutput 'classes'
New-Item -ItemType Directory -Path $qaClasses | Out-Null
$qaClasspath = $qaLibraries -join ';'
& (Join-Path $JdkHome 'bin\javac.exe') --release 21 -classpath $qaClasspath -d $qaClasses `
    (Join-Path $qaProject 'tools\qa\ImpactCheck.java') (Join-Path $qaProject 'tools\qa\PlayerModelShaderCheck.java') `
    2>&1 | Tee-Object -FilePath (Join-Path $qaOutput 'javac.log')
if ($LASTEXITCODE -ne 0) { throw 'Player-model harness compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -classpath "$qaClasses;$qaClasspath" PlayerModelShaderCheck `
    (Join-Path $qaProject 'src\main\resources') $MinecraftJar $qaOutput `
    2>&1 | Tee-Object -FilePath (Join-Path $qaOutput 'player-model-shader.log')
if ($LASTEXITCODE -ne 0) { throw 'Player-model offscreen GPU checks failed; inspect the report/log.' }
