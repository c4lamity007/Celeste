param(
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$adapterRoot = $PSScriptRoot
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $adapterRoot '..\..')).Path
$classesDirectory = Join-Path $adapterRoot 'build\test-classes'
$fixturesDirectory = Join-Path $adapterRoot 'build\fixtures'
$javacCommand = if ($JdkHome) { Join-Path $JdkHome 'bin\javac.exe' } else { 'javac' }
$javaCommand = if ($JdkHome) { Join-Path $JdkHome 'bin\java.exe' } else { 'java' }

& $javaCommand -version
if ($LASTEXITCODE -ne 0) { throw 'Java is unavailable.' }
New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $adapterRoot 'src\test\java') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
$sources += Join-Path $repositoryRoot 'src\main\java\dev\c4lam\celeste\auth\ArtifactIntegrity.java'
$sources += Join-Path $repositoryRoot 'src\main\java\dev\c4lam\celeste\auth\Crypto.java'
& $javacCommand --release 21 -encoding UTF-8 -Xlint:all -Werror -d $classesDirectory @sources
if ($LASTEXITCODE -ne 0) { throw 'Integrity adapter compilation failed.' }
$testModes = @('full', 'disk-first', 'disk-failure', 'memory-failure', 'source-race')
foreach ($testMode in $testModes) {
    # Source selection is deliberately irreversible. Every mode gets a fresh JVM.
    $modeFixtures = Join-Path $fixturesDirectory $testMode
    & $javaCommand -ea -Xmx256m -cp $classesDirectory dev.c4lam.celeste.auth.ArtifactIntegrityTest $modeFixtures $testMode
    if ($LASTEXITCODE -ne 0) { throw "Integrity adapter checks failed: $testMode" }
}
