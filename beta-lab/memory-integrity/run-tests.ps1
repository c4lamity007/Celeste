param([string]$JdkHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$integrationRoot = $PSScriptRoot
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $integrationRoot '..\..')).Path
$classesDirectory = Join-Path $integrationRoot 'build\test-classes'
$fixturesDirectory = Join-Path $integrationRoot 'build\fixtures'
$javacCommand = if ($JdkHome) { Join-Path $JdkHome 'bin\javac.exe' } else { 'javac' }
$javaCommand = if ($JdkHome) { Join-Path $JdkHome 'bin\java.exe' } else { 'java' }
& $javaCommand -version
if ($LASTEXITCODE -ne 0) { throw 'Java is unavailable.' }
New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $integrationRoot 'src\test\java') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'beta-lab\memory-runtime\src') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
$sources += Join-Path $repositoryRoot 'beta-lab\integrity-adapter\src\test\java\dev\c4lam\celeste\CelesteClient.java'
$sources += Join-Path $repositoryRoot 'src\main\java\dev\c4lam\celeste\auth\ArtifactIntegrity.java'
$sources += Join-Path $repositoryRoot 'src\main\java\dev\c4lam\celeste\auth\Crypto.java'
& $javacCommand --release 21 -encoding UTF-8 -Xlint:all -Werror -d $classesDirectory @sources
if ($LASTEXITCODE -ne 0) { throw 'Memory/integrity compilation failed.' }
& $javaCommand -ea -Xmx256m -cp $classesDirectory dev.c4lam.celeste.auth.MemoryIntegrityIntegrationTest $fixturesDirectory
if ($LASTEXITCODE -ne 0) { throw 'Memory/integrity checks failed.' }
