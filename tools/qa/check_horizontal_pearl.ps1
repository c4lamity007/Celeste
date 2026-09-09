param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [Parameter(Mandatory = $true)][string]$JdkHome
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$qaProject = (Resolve-Path -LiteralPath $ProjectRoot).Path
$qaJdk = (Resolve-Path -LiteralPath $JdkHome).Path
$qaClasses = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ('horizontal-pearl-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $qaClasses -Force | Out-Null
& (Join-Path $qaJdk 'bin/javac.exe') --release 21 -d $qaClasses `
    (Join-Path $qaProject 'src/main/java/dev/c4lam/celeste/module/impl/combat/HorizontalPearlTrajectory.java') `
    (Join-Path $qaProject 'tools/qa/HorizontalPearlCheck.java')
if ($LASTEXITCODE -ne 0) { throw "Horizontal pearl compilation failed (exit $LASTEXITCODE)." }
& (Join-Path $qaJdk 'bin/java.exe') -cp $qaClasses dev.c4lam.celeste.module.impl.combat.HorizontalPearlCheck
if ($LASTEXITCODE -ne 0) { throw "Horizontal pearl regression check failed (exit $LASTEXITCODE)." }
