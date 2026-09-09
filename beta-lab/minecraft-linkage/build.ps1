param(
    [string]$JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [switch]$RunFixture
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$taskBuild = Join-Path $taskRoot 'build\module-qa\minecraft-linkage'
$taskCompile = Join-Path $taskBuild ('compile-' + [Guid]::NewGuid().ToString('N'))
$taskClasses = Join-Path $taskCompile 'classes'
$taskBridgeClasses = Join-Path $taskCompile 'bridge-classes'
$taskFixtureClasses = Join-Path $taskCompile 'fixture-classes'
$taskTestClasses = Join-Path $taskCompile 'test-classes'
foreach ($taskDir in @($taskClasses, $taskBridgeClasses, $taskFixtureClasses, $taskTestClasses)) {
    New-Item -ItemType Directory -Path $taskDir -Force | Out-Null
}
$taskJavac = Join-Path $JdkHome 'bin\javac.exe'
$taskJar = Join-Path $JdkHome 'bin\jar.exe'
$taskJava = Join-Path $JdkHome 'bin\java.exe'
function Invoke-TaskCompiler([string[]]$CompilerArguments) {
    & $taskJavac @CompilerArguments
    if ($LASTEXITCODE -ne 0) { throw "Java compilation failed ($LASTEXITCODE)" }
}
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
Invoke-TaskCompiler (@('--release', '21', '-Xlint:all', '-Werror', '--add-modules', 'jdk.attach', '-d', $taskClasses) + $taskSources)
# These bridge sources use only the JDK. Payload/dependencies never enter this JAR.
$taskBridgeSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'src\main\java\dev\celestebeta\host') -Filter '*.java' | ForEach-Object FullName)
Invoke-TaskCompiler (@('--release', '21', '-Xlint:all', '-Werror', '-d', $taskBridgeClasses) + $taskBridgeSources)
$taskAgentJar = Join-Path $taskBuild 'celeste-linkage-probe.jar'
$taskBridgeJar = Join-Path $taskBuild 'celeste-linkage-bridges.jar'
& $taskJar --create --file $taskAgentJar --manifest (Join-Path $PSScriptRoot 'agent-manifest.mf') -C $taskClasses .
if ($LASTEXITCODE -ne 0) { throw 'Agent packaging failed' }
& $taskJar --create --file $taskBridgeJar --manifest (Join-Path $PSScriptRoot 'bridge-manifest.mf') -C $taskBridgeClasses .
if ($LASTEXITCODE -ne 0) { throw 'Bridge packaging failed' }
Write-Output "Diagnostic agent: $taskAgentJar"
Write-Output "Canonical bridges: $taskBridgeJar"
if ($RunFixture) {
    $taskFixtureSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'test\game') -Filter '*.java' -Recurse | ForEach-Object FullName)
    Invoke-TaskCompiler (@('--release', '21', '-Xlint:all', '-Werror', '-d', $taskFixtureClasses) + $taskFixtureSources)
    $taskTestSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'test\driver') -Filter '*.java' -Recurse | ForEach-Object FullName)
    Invoke-TaskCompiler (@('--release', '21', '-Xlint:all', '-Werror', '--add-modules', 'jdk.attach', '-cp', $taskAgentJar, '-d', $taskTestClasses) + $taskTestSources)
    & $taskJava --add-modules jdk.attach -cp "$taskAgentJar;$taskTestClasses" dev.celestebeta.linkage.fixture.FixtureCheck $taskJava $taskTestClasses $taskFixtureClasses $taskAgentJar $taskBridgeJar $taskRoot $taskBuild
    if ($LASTEXITCODE -ne 0) { throw 'Self-created JVM fixture checks failed' }
}
