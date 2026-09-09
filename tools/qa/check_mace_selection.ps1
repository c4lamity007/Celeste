param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [Parameter(Mandatory = $true)][string]$JdkHome
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$qaProject = (Resolve-Path -LiteralPath $ProjectRoot).Path
$qaJdk = (Resolve-Path -LiteralPath $JdkHome).Path
$qaClasses = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ('mace-selection-' + [Guid]::NewGuid().ToString('N'))
$qaInputs = @(
    (Join-Path $qaProject 'src/main/java/dev/c4lam/celeste/hud/LocalDamageEstimator.java'),
    (Join-Path $qaProject 'src/main/java/dev/c4lam/celeste/module/impl/combat/MaceSelection.java'),
    (Join-Path $qaProject 'tools/qa/MaceSelectionCheck.java')
)
foreach ($qaInput in $qaInputs) {
    if (-not (Test-Path -LiteralPath $qaInput -PathType Leaf)) { throw "Missing mace check input: $qaInput" }
}
New-Item -ItemType Directory -Path $qaClasses -Force | Out-Null
& (Join-Path $qaJdk 'bin/javac.exe') --release 21 -Xlint:all -Werror -d $qaClasses @qaInputs
if ($LASTEXITCODE -ne 0) { throw "Mace selection compilation failed (exit $LASTEXITCODE)." }
& (Join-Path $qaJdk 'bin/java.exe') -cp $qaClasses dev.c4lam.celeste.module.impl.combat.MaceSelectionCheck
if ($LASTEXITCODE -ne 0) { throw "Mace selection regression failed (exit $LASTEXITCODE)." }
