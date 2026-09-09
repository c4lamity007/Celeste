param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [Parameter(Mandatory = $true)][string]$JdkHome
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$qaProject = (Resolve-Path -LiteralPath $ProjectRoot).Path
$qaJdk = (Resolve-Path -LiteralPath $JdkHome).Path
$qaOutput = [IO.Path]::GetFullPath($OutputRoot)
$qaClasses = Join-Path $qaOutput ('adapter-classes-' + [Guid]::NewGuid().ToString('N'))
$qaEmptySource = Join-Path $qaOutput 'empty-source'
$qaProduction = @(
    (Join-Path $qaProject 'src/main/java/dev/c4lam/celeste/module/impl/combat/ShieldBreakPolicy.java'),
    (Join-Path $qaProject 'src/main/java/dev/c4lam/celeste/module/impl/combat/ShieldBreakSupport.java')
)
$qaFixture = Join-Path $qaProject 'tools/qa/ShieldBreakCheck.java'
$qaAdapters = @(rg --files (Join-Path $qaProject 'tools/qa/shield-break-stubs') -g '*.java' | Sort-Object)
if ($qaAdapters.Count -eq 0) { throw 'Missing Shield Break explicit authority adapters' }
foreach ($qaInput in @($qaProduction) + @($qaFixture) + @($qaAdapters)) {
    if (-not (Test-Path -LiteralPath $qaInput -PathType Leaf)) { throw "Missing Shield Break check input: $qaInput" }
}
New-Item -ItemType Directory -Path $qaClasses, $qaEmptySource -Force | Out-Null
& (Join-Path $qaJdk 'bin/javac.exe') --release 21 -Xlint:all -Werror -implicit:none -sourcepath $qaEmptySource -d $qaClasses @qaProduction $qaFixture @qaAdapters 2>&1 |
    Tee-Object -FilePath (Join-Path $qaOutput 'compile.log')
if ($LASTEXITCODE -ne 0) { throw "Shield Break fixture compilation failed (exit $LASTEXITCODE)." }
$qaBehavior = & (Join-Path $qaJdk 'bin/java.exe') -cp $qaClasses dev.c4lam.celeste.module.impl.combat.ShieldBreakCheck 2>&1
$qaExit = $LASTEXITCODE
$qaBehavior | Set-Content -LiteralPath (Join-Path $qaOutput 'behavior.log')
if ($qaExit -ne 0) { throw "Shield Break behavioral regression failed (exit $qaExit); see behavior.log." }
$qaHashes = @($qaProduction | ForEach-Object {
    [ordered]@{path = $_; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash.ToLowerInvariant()}
})
[ordered]@{
    status = 'pass'; behavior = $qaBehavior; production = $qaHashes;
    scope = 'Actual ShieldBreakPolicy and ShieldBreakSupport with explicit value/query/scheduler/slot authority adapters; no copied policy or engine simulation';
    runtime = 'not tested'; serverAcceptance = 'not tested'
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $qaOutput 'result.json')
Write-Output $qaBehavior
Write-Output 'Shield Break behavior PASS; actual game/server acceptance not tested'
