param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$JdkHome='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference='Stop'
$taskProject=(Resolve-Path -LiteralPath $ProjectRoot).Path
$taskOutput=[IO.Path]::GetFullPath($OutputRoot)
$taskClasses=Join-Path $taskOutput 'adapter-classes'
$taskMappedClasses=Join-Path $taskOutput 'mapped-classes'
$taskEmptySource=Join-Path $taskOutput 'empty-source'
New-Item -ItemType Directory -Force -Path $taskClasses,$taskMappedClasses,$taskEmptySource | Out-Null
$taskJavac=Join-Path $JdkHome 'bin\javac.exe'
$taskJava=Join-Path $JdkHome 'bin\java.exe'
$taskJavap=Join-Path $JdkHome 'bin\javap.exe'
$taskHelper=Join-Path $taskProject 'src\main\java\dev\c4lam\celeste\module\impl\combat\SwordAuraTargeting.java'
$taskFixture=Join-Path $taskProject 'tools\qa\SwordAuraRangeCheck.java'
$taskAdapters=@(rg --files (Join-Path $taskProject 'tools\qa\sword-aura-stubs') -g '*.java')
if($taskAdapters.Count -ne 10){throw 'Expected exactly ten value/query-authority adapters'}
& $taskJavac --release 21 -Xlint:all -Werror -implicit:none -sourcepath $taskEmptySource -d $taskClasses $taskHelper $taskFixture @taskAdapters
if($LASTEXITCODE -ne 0){throw 'Sword Aura focused fixture compile failed'}
$taskBehavior=& $taskJava -cp $taskClasses dev.c4lam.celeste.module.impl.combat.SwordAuraRangeCheck
if($LASTEXITCODE -ne 0){throw 'Sword Aura focused fixture failed'}
$taskBehavior | Set-Content -LiteralPath (Join-Path $taskOutput 'behavior.log')
Write-Output $taskBehavior

# Independently compile the exact production helper against the real named1.21.11 API.
# No adapter classes are on this second compilation's classpath.
$taskLoomRoot=Join-Path $env:USERPROFILE '.gradle\caches\fabric-loom'
$taskMinecraft=Join-Path $taskLoomRoot 'minecraftMaven\net\minecraft\minecraft-merged\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
if(!(Test-Path -LiteralPath $taskMinecraft)){throw 'Cached mapped Minecraft1.21.11 missing; no download attempted'}
$taskDependencyRoot=Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$taskFabric=@(rg --files (Join-Path $taskDependencyRoot 'net.fabricmc\fabric-loader\0.19.3') -g 'fabric-loader-0.19.3.jar')
$taskDfu=@(rg --files (Join-Path $taskDependencyRoot 'com.mojang\datafixerupper\9.0.19') -g 'datafixerupper-9.0.19.jar')
if($taskFabric.Count -ne 1 -or $taskDfu.Count -ne 1){throw 'Expected cached FabricLoader0.19.3 and DataFixerUpper9.0.19'}
$taskMappedCp=@($taskMinecraft,$taskFabric[0],$taskDfu[0]) -join ';'
& $taskJavac --release 21 '-Xlint:all,-classfile' -Werror -implicit:none -sourcepath $taskEmptySource -cp $taskMappedCp -d $taskMappedClasses $taskHelper
if($LASTEXITCODE -ne 0){throw 'Production helper failed actual mapped Minecraft1.21.11 compilation'}

$taskPlayerBytecode=(& $taskJavap -c -p -classpath $taskMinecraft net.minecraft.client.network.ClientPlayerEntity) -join "`n"
if($LASTEXITCODE -ne 0){throw 'Could not inspect actual Minecraft player class'}
$taskQuery=[regex]::Match($taskPlayerBytecode,'(?s)public net\.minecraft\.util\.hit\.HitResult getCrosshairTarget\(float, net\.minecraft\.entity\.Entity\);.*?(?=\n  (?:public|private|protected))').Value
$taskFallback=[regex]::Match($taskPlayerBytecode,'(?s)private static net\.minecraft\.util\.hit\.HitResult getCrosshairTarget\(net\.minecraft\.entity\.Entity, double, double, float\);.*?(?=\n  (?:public|private|protected))').Value
$taskRange=[regex]::Match($taskPlayerBytecode,'(?s)private static net\.minecraft\.util\.hit\.HitResult checkCrosshairTargetRange\(net\.minecraft\.util\.hit\.HitResult, net\.minecraft\.util\.math\.Vec3d, double\);.*?(?=\n  (?:public|private|protected))').Value
if(!$taskQuery.Contains('DataComponentTypes.ATTACK_RANGE') -or !$taskQuery.Contains('AttackRangeComponent.getHitResult') -or !$taskQuery.Contains('getEntityInteractionRange')){
    throw 'Actual Minecraft vanilla query no longer has expected component/entity-range routes'
}
if(!$taskFallback.Contains('Entity.raycast') -or !$taskFallback.Contains('EntityPredicates.CAN_HIT') -or !$taskFallback.Contains('checkCrosshairTargetRange') -or !$taskRange.Contains('Vec3d.isInRange')){
    throw 'Actual vanilla blocker/entity-range filtering does not match reviewed behavior'
}
$taskProjectileBytecode=(& $taskJavap -c -p -classpath $taskMinecraft net.minecraft.entity.projectile.ProjectileUtil) -join "`n"
if($LASTEXITCODE -ne 0){throw 'Could not inspect actual Minecraft projectile class'}
$taskComponent=[regex]::Match($taskProjectileBytecode,'(?s)public static [^\n]+ collectPiercingCollisions\(net\.minecraft\.entity\.Entity, net\.minecraft\.component\.type\.AttackRangeComponent, [^\n]+\);.*?(?=\n  (?:public|private|protected))').Value
if(!$taskComponent.Contains('Entity.getHeadRotationVector')){throw 'Actual component aim route no longer matches scoped headYaw handling'}
@($taskQuery,$taskFallback,$taskRange,$taskComponent) | Set-Content -LiteralPath (Join-Path $taskOutput 'minecraft-1.21.11-query-bytecode.log')
$taskHelperBytecode=(& $taskJavap -c -p -classpath $taskMappedClasses dev.c4lam.celeste.module.impl.combat.SwordAuraTargeting) -join "`n"
if($LASTEXITCODE -ne 0 -or !$taskHelperBytecode.Contains('ClientPlayerEntity.getCrosshairTarget') -or $taskHelperBytecode.Contains('ProjectileUtil') -or $taskHelperBytecode.Contains('3.35d')){
    throw 'Production helper does not exclusively delegate hit acquisition to vanilla'
}
$taskHelperBytecode | Set-Content -LiteralPath (Join-Path $taskOutput 'production-helper-bytecode.log')
$taskReport=[ordered]@{
    status='pass'; behavior=$taskBehavior; mappedMinecraft='1.21.11/yarn build.6';
    minecraftSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $taskMinecraft).Hash.ToLowerInvariant();
    helperSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $taskHelper).Hash.ToLowerInvariant();
    actualMappedApiCompile=$true; actualVanillaBytecodeRoutes=$true;
    runtime='not tested'; scope='Production helper behavior with explicit authority adapters plus actual mapped Minecraft API/bytecode; no engine or game simulation'
}
$taskReport | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $taskOutput 'result.json')
Write-Output 'Sword Aura actual mapped1.21.11 API + component/entity-range/occlusion/head-vector bytecode checks PASS; in-game behavior not tested'
