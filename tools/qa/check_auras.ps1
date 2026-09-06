param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [Parameter(Mandatory = $true)][string]$JdkHome
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$qaProject = (Resolve-Path -LiteralPath $ProjectRoot).Path
$qaJdk = (Resolve-Path -LiteralPath $JdkHome).Path
$qaJavac = Join-Path $qaJdk 'bin/javac.exe'
$qaJava = Join-Path $qaJdk 'bin/java.exe'
$qaSource = Join-Path $qaProject 'src/main/java/dev/c4lam/celeste/module/impl/combat/AuraRules.java'
$qaTest = Join-Path $qaProject 'tools/qa/AuraRulesCheck.java'
foreach ($qaRequired in @($qaJavac, $qaJava, $qaSource, $qaTest)) {
    if (-not (Test-Path -LiteralPath $qaRequired -PathType Leaf)) { throw "Missing aura check input: $qaRequired" }
}
$qaClasses = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ('aura-rules-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $qaClasses -Force | Out-Null
& $qaJavac --release 21 -d $qaClasses $qaSource $qaTest
if ($LASTEXITCODE -ne 0) { throw "Aura Java compilation failed (exit $LASTEXITCODE)." }
& $qaJava -cp $qaClasses dev.c4lam.celeste.module.impl.combat.AuraRulesCheck
if ($LASTEXITCODE -ne 0) { throw "Aura regression check failed (exit $LASTEXITCODE)." }
