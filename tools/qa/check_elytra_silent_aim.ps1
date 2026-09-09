param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [Parameter(Mandatory = $true)][string]$JdkHome
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$qaProject = (Resolve-Path -LiteralPath $ProjectRoot).Path
$qaJdk = (Resolve-Path -LiteralPath $JdkHome).Path
$qaClasses = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ('elytra-silent-aim-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $qaClasses -Force | Out-Null
$qaSources = @(Get-ChildItem -LiteralPath (Join-Path $qaProject 'tools/qa/elytra-silent-aim-stubs') -Filter '*.java' -File -Recurse | ForEach-Object FullName)
foreach ($qaName in @('ElytraSilentAim', 'ElytraRotationMath', 'Rotation', 'RotationTask', 'MoveFixMode')) {
    $qaSources += Join-Path $qaProject ('src/main/java/dev/c4lam/celeste/rotation/' + $qaName + '.java')
}
$qaSources += Join-Path $qaProject 'tools/qa/ElytraSilentAimCheck.java'
& (Join-Path $qaJdk 'bin/javac.exe') --release 21 -d $qaClasses @qaSources
if ($LASTEXITCODE -ne 0) { throw "Elytra silent-aim adapter compilation failed (exit $LASTEXITCODE)." }
& (Join-Path $qaJdk 'bin/java.exe') -cp $qaClasses dev.c4lam.celeste.rotation.ElytraSilentAimCheck
if ($LASTEXITCODE -ne 0) { throw "Elytra silent-aim adapter regression check failed (exit $LASTEXITCODE)." }
