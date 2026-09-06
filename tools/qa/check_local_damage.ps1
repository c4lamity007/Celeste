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
$qaSource = Join-Path $qaProject 'src/main/java/dev/c4lam/celeste/hud/LocalDamageEstimator.java'
$qaTest = Join-Path $qaProject 'tools/qa/LocalDamageEstimatorCheck.java'
foreach ($qaRequired in @($qaJavac, $qaJava, $qaSource, $qaTest)) {
    if (-not (Test-Path -LiteralPath $qaRequired -PathType Leaf)) {
        throw "Missing local damage check input: $qaRequired"
    }
}

# Unique output avoids treating old class files as evidence after a failed compile.
$qaOutput = [IO.Path]::GetFullPath($OutputRoot)
$qaClasses = Join-Path $qaOutput ('local-damage-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $qaClasses -Force | Out-Null
& $qaJavac --release 21 -d $qaClasses $qaSource $qaTest
if ($LASTEXITCODE -ne 0) {
    throw "Local damage Java compilation failed (exit $LASTEXITCODE)."
}
& $qaJava -cp $qaClasses dev.c4lam.celeste.hud.LocalDamageEstimatorCheck
if ($LASTEXITCODE -ne 0) {
    throw "Local damage regression check failed (exit $LASTEXITCODE)."
}
