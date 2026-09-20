param(
    [Parameter(Mandatory = $true)][string]$PlusBootstrapA,
    [Parameter(Mandatory = $true)][string]$NormalClient,
    [Parameter(Mandatory = $true)][string]$NormalBootstrap,
    [Parameter(Mandatory = $true)][string]$NormalBootstrapMapping,
    [string]$NormalClientMapping = '-'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $projectRoot 'build/module-qa/dpop/protected-artifact-check/classes'
$gson = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0') `
    -Filter 'gson-2.11.0.jar' -File -Recurse | Select-Object -First 1 -ExpandProperty FullName
if (-not $gson) { throw 'Cached Gson 2.11.0 JAR is required for the isolated artifact check' }

$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot' }
$javac = Join-Path $javaHome 'bin/javac.exe'
$java = Join-Path $javaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) {
    throw 'Java 21 compiler/runtime are required'
}
New-Item -ItemType Directory -Path $output -Force | Out-Null
& $javac -cp $gson -d $output (Join-Path $PSScriptRoot 'ProtectedDpopArtifactCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Protected DPoP artifact probe failed to compile' }

$classpath = "$output;$gson"
function Invoke-ArtifactCheck([string]$artifact, [string]$mapping, [string]$bridge) {
    $resolvedArtifact = (Resolve-Path -LiteralPath $artifact).Path
    $resolvedMapping = if ($mapping -eq '-') { '-' } else { (Resolve-Path -LiteralPath $mapping).Path }
    & $java -cp $classpath ProtectedDpopArtifactCheck $resolvedArtifact $gson $resolvedMapping $bridge
    if ($LASTEXITCODE -ne 0) { throw "Protected DPoP artifact check failed: $resolvedArtifact" }
}

Invoke-ArtifactCheck $PlusBootstrapA '-' 'no-bridge'
Invoke-ArtifactCheck $NormalClient $NormalClientMapping 'no-bridge'
Invoke-ArtifactCheck $NormalBootstrap $NormalBootstrapMapping 'bridge'
