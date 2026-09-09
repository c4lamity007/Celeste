param(
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$runtimeRoot = $PSScriptRoot
$classesDirectory = Join-Path $runtimeRoot 'build\test-classes'
$javacCommand = if ($JdkHome) { Join-Path $JdkHome 'bin\javac.exe' } else { 'javac' }
$javaCommand = if ($JdkHome) { Join-Path $JdkHome 'bin\java.exe' } else { 'java' }

& $javaCommand -version
if ($LASTEXITCODE -ne 0) { throw 'Java is unavailable.' }
New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $runtimeRoot 'src') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& $javacCommand --release 21 -Xlint:all -Werror -d $classesDirectory @sources
if ($LASTEXITCODE -ne 0) { throw 'Memory runtime compilation failed.' }
& $javaCommand -ea -cp $classesDirectory dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoaderTest
if ($LASTEXITCODE -ne 0) { throw 'Memory runtime checks failed.' }
