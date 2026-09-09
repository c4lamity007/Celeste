# Sky Lyrics online provider

Sky Lyrics explicitly enables a Java-only LRCLIB lookup. Ordinary media HUD polling does not enable network lookups. Disabling the module cancels scheduled/in-flight requests and clears its in-memory metadata/lyric cache. No PowerShell, shell, commands, helper processes, credentials, cookies, Spotify account access, or persistent listening cache are used at runtime.

The native Windows media bridge remains unchanged. Its released six-argument `MediaInfo` ABI is sufficient. An optional native synchronized-lyrics getter is discovered reflectively; a missing getter means no native lyrics, not a media-service failure. Native timed payloads take precedence when available.

## Matching and limits

The official API describes search at <https://lrclib.net/docs>; its first-party route implementation is <https://github.com/tranxuanthang/lrclib/blob/main/server/src/routes/search_lyrics.rs>.

The native bridge supplies no album, so the provider calls `GET https://lrclib.net/api/search` with URL-encoded `track_name` and `artist_name`. Duration is validated locally; it is not sent as an unsupported search parameter. Returned title and artist must match after Unicode NFKC, whitespace, and case normalization. Punctuation, accents, featured artists, and recording/version suffixes are not discarded. Duration must be within two seconds of the native session. The closest qualifying duration wins; instrumental, mismatched, untimed, and unusable timelines remain empty. No timestamps are fabricated from plain text.

There is one dedicated lookup worker, at most one in-flight request, a 650 ms metadata-settle delay, and a two-second minimum request interval. The fixed HTTPS endpoint never follows redirects. Connection timeout is four seconds; a twelve-second wall deadline includes the entire response body. Bodies are capped at 1 MiB while streaming, JSON candidates at 64, unknown nested JSON at 32 levels, and existing LRC parser bounds remain in force. Metadata queries are limited to 256 characters per title/artist. The 32-entry memory-only LRU cache uses six-hour successful results, ten-minute misses, and two-minute failures. Cache expiry is monotonic.

Track identity includes source, normalized title/artist, and native duration. A generation check rejects stale completions after track change, disable, reset, or close. Position and playing state are not part of identity: pause freezes the existing snapshot clock, seek selects from the same server timeline, and a finished song hides lyrics.

## Single-line world presentation

Each current cue captures the local camera's position and rotation once, at the configured Distance (2–64 blocks, default 18) along its look direction. Moving or turning afterward does not drag or rotate the existing text. Distance changes take effect with the next phrase, not halfway through the current one. Only a new cue (including repeated identical text at a different timestamp), track, world or explicit disable/reset captures a new anchor. A backward seek within the same phrase, duration/album metadata refresh, transient media outage or blank cue does not relocate it. Only one current line is rendered: no artist label, next-line preview, or progress bar. Empty cues and completed tracks hide it.

At phrase creation, one client-world raycast checks block outline shapes along that same direction, without entity targeting or fluid collisions. A hit shortens the distance to 0.2 blocks before the first surface; it never moves the text through that surface to satisfy the slider minimum. If the camera starts inside a block, or less than 0.5 blocks of clear placement remains, that phrase stays hidden until the next cue. The chosen pose (including a hidden result) is latched, so camera motion or changing blocks does not cause per-frame raycasts or repositioning. Depth testing still hides any letters/vapor occluded by foreground geometry.

Sky Lyrics uses its own monotonic per-frame clock. The native bridge reports whole seconds every roughly 350 ms; restarting interpolation from every poll made time rewind and could falsely respawn the current phrase. Repeated native positions now preserve continuous animation. Changed observations are reconciled against their one-second quantization interval with bounded rate correction; large actual seeks still select the correct phrase. Pause freezes the clock. The shared media HUD, DLL and network provider are unchanged.

The world renderer uses the existing SF Pro semibold MSDF atlas, with depth testing and no depth writes. Word dissolution and upward vapor follow media playback time, so pausing freezes the effect. Enhanced LRC word timestamps are used when available. Standard LRCLIB line timestamps do not identify individual vocal timings: the per-word animation within those lines is an explicitly estimated, bounded distribution, not audio transcription. Neither source enables untimed/plain-text lyrics.

The fog-free lyric shader draws once at `END_MAIN` in a phase explicitly ordered after the default phase, where Celeste's shader fog composites. This prevents far terrain's fog depth from washing out nearby lettering without disabling world fog or world-depth occlusion. The GPU fixture uses the real fog/lyric GLSL to check all five fog presets, evaporation, disabled-fog identity, untouched scene depth and foreground occlusion. This does not guarantee ordering against arbitrary third-party shader-pack postprocessing.

Controls are Color, Gradient (Solid/Horizontal/Vertical), End Color, Distance, Size and Opacity. Direction, Elevation, Timing Offset and Next Line no longer affect rendering. Existing unknown saved settings are left to the normal config loader; no user profiles are rewritten by this change.

Run `module_qa.py check --module 'Sky Lyrics' --suite sky-lyrics --java-home '<JDK 21>'` for the incremental build, timeline/anchor behavior and actual shader/atlas offscreen fixtures. These fixtures do not establish in-game appearance or loaded-build identity.

Both lookup and HTTP worker threads are daemon threads with the platform context classloader. `SystemMediaService.close()` cancels them; `stopped()` also waits for HTTP client/worker termination so the existing Plus lifecycle guard can verify unload readiness.

## Dependencies and verification

Runtime dependencies are Java 21 `java.net.http` and Minecraft's existing Gson (tested with 2.11.0). No new library, native DLL, or `MediaInfo` change is required. New payload class families are `LrclibLyricsSource` and `LrclibHttpTransport`, including their nested classes. Integration changes are `SystemMediaService`, `NativeMediaLyrics`, and `SkyLyricsFeature`. Existing `TimedLyrics`, `MediaPlaybackSnapshot`, renderer/registration and rendering compatibility families must be present in the release overlay.

`LrclibLyricsSourceCheck` uses only synthetic local responses and fake transport; it exercises metadata matching, pause/seek/end, cache bounds/expiry, outage recovery, stale results, cancellation, worker context classloader, and streaming response limits. `NativeMediaLyricsLegacyCheck` must be compiled/run with the released host-support `MediaInfo` first on its classpath. `LrclibLyricsLiveSmoke` is optional and sends only one fixed synthetic metadata query to the official endpoint, with no access to media sessions. It prints status and cue count, not response text.

The staged source checks do not establish in-game build identity or visual verification. Root coordinates the single incremental build, protected release and authorized local-world test.
