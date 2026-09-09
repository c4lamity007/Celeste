param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$JdkHome='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference='Stop'
$taskProject=(Resolve-Path -LiteralPath $ProjectRoot).Path
$taskOutput=[IO.Path]::GetFullPath($OutputRoot)
$taskClasses=Join-Path $taskOutput 'classes'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskQa=Join-Path $taskProject 'tools\qa'
$taskSource=Join-Path $taskProject 'src\main\java\dev\c4lam\celeste'
$taskGson=@(rg --files (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson') -g 'gson-2.11.0.jar')
if($taskGson.Count -ne 1){throw 'Expected one cached Gson 2.11.0; no download attempted'}
# Gson's optional InlineMe annotations are not a runtime dependency; the existing
# provider fixture deliberately closes a try-resource early to test cancellation.
& (Join-Path $JdkHome 'bin\javac.exe') --release 21 '-Xlint:all,-classfile,-try' -Werror -cp $taskGson[0] -d $taskClasses `
    (Join-Path $taskSource 'media\TimedLyrics.java') `
    (Join-Path $taskSource 'media\MediaPlaybackSnapshot.java') `
    (Join-Path $taskSource 'media\NativeMediaLyrics.java') `
    (Join-Path $taskProject 'src\main\java\dev\redstones\mediaplayerinfo\MediaInfo.java') `
    (Join-Path $taskSource 'media\LrclibLyricsSource.java') `
    (Join-Path $taskSource 'media\LrclibHttpTransport.java') `
    (Join-Path $taskSource 'module\impl\visual\expansion\SkyLyricAnchor.java') `
    (Join-Path $taskSource 'module\impl\visual\expansion\SkyLyricClock.java') `
    (Join-Path $taskQa 'NativeMediaLyricsCheck.java') (Join-Path $taskQa 'LrclibLyricsSourceCheck.java') `
    (Join-Path $taskQa 'SkyLyricsCheck.java') (Join-Path $taskQa 'SkyLyricAnchorCheck.java') `
    (Join-Path $taskQa 'SkyLyricClockCheck.java')
if($LASTEXITCODE -ne 0){throw 'Sky Lyrics focused fixture compile failed'}
& (Join-Path $JdkHome 'bin\java.exe') -cp $taskClasses dev.c4lam.celeste.media.SkyLyricsCheck
if($LASTEXITCODE -ne 0){throw 'Sky Lyrics timeline regression failed'}
& (Join-Path $JdkHome 'bin\java.exe') -cp $taskClasses dev.c4lam.celeste.module.impl.visual.expansion.SkyLyricAnchorCheck
if($LASTEXITCODE -ne 0){throw 'Sky Lyrics world-anchor regression failed'}
& (Join-Path $JdkHome 'bin\java.exe') -cp $taskClasses dev.c4lam.celeste.module.impl.visual.expansion.SkyLyricClockCheck
if($LASTEXITCODE -ne 0){throw 'Sky Lyrics frame-clock regression failed'}
& (Join-Path $JdkHome 'bin\java.exe') -cp $taskClasses dev.c4lam.celeste.media.NativeMediaLyricsCheck
if($LASTEXITCODE -ne 0){throw 'Sky Lyrics native capability regression failed'}
& (Join-Path $JdkHome 'bin\java.exe') -cp "$taskClasses;$($taskGson[0])" dev.c4lam.celeste.media.LrclibLyricsSourceCheck
if($LASTEXITCODE -ne 0){throw 'Sky Lyrics offline provider regression failed'}
