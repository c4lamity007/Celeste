param(
    [Parameter(Mandatory=$true)][string]$PackageDirectory,
    [string]$JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [string]$ParentClasspathFile
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$taskPackage = (Resolve-Path -LiteralPath $PackageDirectory).Path
$taskManifest = Get-Content -LiteralPath (Join-Path $taskPackage 'package.json') -Raw | ConvertFrom-Json
$taskClasses = Join-Path $taskRoot ('build\module-qa\release-package\validator-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
$taskSources = @(
    (Join-Path $PSScriptRoot 'src\dev\c4lam\celeste\auth\ActualPayloadCheck.java'),
    (Join-Path $taskRoot 'beta-lab\memory-runtime\src\main\java\dev\c4lam\celeste\beta\memory\TwoPartMemoryClassLoader.java'),
    (Join-Path $taskRoot 'beta-lab\integrity-adapter\src\test\java\dev\c4lam\celeste\CelesteClient.java'),
    (Join-Path $taskRoot 'src\main\java\dev\c4lam\celeste\auth\ArtifactIntegrity.java'),
    (Join-Path $taskRoot 'src\main\java\dev\c4lam\celeste\auth\Crypto.java')
)
& (Join-Path $JdkHome 'bin\javac.exe') --release 21 -encoding UTF-8 -Xlint:all -Werror -d $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Actual payload validator compilation failed' }
$taskArguments = @('-ea', '-Xmx384m', '-cp', $taskClasses, 'dev.c4lam.celeste.auth.ActualPayloadCheck', $taskPackage,
    $taskManifest.payloads[0].sha256, $taskManifest.payloads[1].sha256, $taskManifest.runtime_sha256)
if ($ParentClasspathFile) { $taskArguments += (Resolve-Path -LiteralPath $ParentClasspathFile).Path }
& (Join-Path $JdkHome 'bin\java.exe') @taskArguments
if ($LASTEXITCODE -ne 0) { throw 'Actual packaged payload validation failed' }
