param(
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference = 'Stop'
$probeRoot = $PSScriptRoot
$probeBuild = Join-Path $probeRoot 'build'
$probeClasses = Join-Path $probeBuild 'classes'
$probeJarContent = Join-Path $probeBuild 'agent-content'
$probeFixtureClasspath = Join-Path $probeBuild 'fixture-classes'
New-Item -ItemType Directory -Force -Path $probeClasses, $probeJarContent | Out-Null
$probeSources = @(Get-ChildItem -LiteralPath (Join-Path $probeRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin\javac.exe') --release 21 --add-modules jdk.attach -d $probeClasses @probeSources
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
$probeFixturePackage = Join-Path $probeFixtureClasspath 'dev\c4lam\celeste\beta\probe'
New-Item -ItemType Directory -Force -Path $probeFixturePackage | Out-Null
foreach ($fixtureClass in @('Marker.class', 'ProbeTarget.class')) {
    Copy-Item -LiteralPath (Join-Path $probeClasses ('dev\c4lam\celeste\beta\probe\' + $fixtureClass)) -Destination $probeFixturePackage
}
$probeFixtureClasses = @{}
foreach ($variant in @('updated', 'structural')) {
    $variantSource = Join-Path $probeRoot ('fixtures\' + $variant + '\dev\c4lam\celeste\beta\probe\Marker.java')
    $variantClasses = Join-Path $probeBuild $variant
    New-Item -ItemType Directory -Force -Path $variantClasses | Out-Null
    & (Join-Path $JavaHome 'bin\javac.exe') --release 21 -d $variantClasses $variantSource
    if ($LASTEXITCODE -ne 0) { throw "Fixture compilation failed: $variant" }
    $probeFixtureClasses[$variant] = Join-Path $variantClasses 'dev\c4lam\celeste\beta\probe\Marker.class'
}
$probePackage = Join-Path $probeJarContent 'dev\c4lam\celeste\beta\probe'
$probeFixtureDirectory = Join-Path $probeJarContent 'fixtures'
New-Item -ItemType Directory -Force -Path $probePackage, $probeFixtureDirectory | Out-Null
Copy-Item -LiteralPath (Join-Path $probeClasses 'dev\c4lam\celeste\beta\probe\ProbeAgent.class') -Destination $probePackage
Copy-Item -LiteralPath (Join-Path $probeClasses 'dev\c4lam\celeste\beta\probe\Marker.class') -Destination (Join-Path $probeFixtureDirectory 'original.class')
foreach ($variant in @('updated', 'structural')) {
    Copy-Item -LiteralPath $probeFixtureClasses[$variant] -Destination (Join-Path $probeFixtureDirectory ($variant + '.class'))
}
$probeAgentJar = Join-Path $probeBuild 'celeste-beta-fixture-agent.jar'
& (Join-Path $JavaHome 'bin\jar.exe') --create --file $probeAgentJar --manifest (Join-Path $probeRoot 'agent-manifest.mf') -C $probeJarContent .
if ($LASTEXITCODE -ne 0) { throw 'Probe agent packaging failed' }
& (Join-Path $JavaHome 'bin\java.exe') --add-modules jdk.attach -cp $probeClasses dev.c4lam.celeste.beta.probe.ProbeDriver $probeFixtureClasspath $probeAgentJar
if ($LASTEXITCODE -ne 0) { throw 'Probe execution failed' }
