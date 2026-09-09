package dev.c4lam.celeste.media;

import dev.redstones.mediaplayerinfo.MediaInfo;
import java.time.Instant;

/** Synthetic offline capability checks. Does not load or invoke the native DLL. */
public final class NativeMediaLyricsCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var original = MediaInfo.class.getConstructor(String.class, String.class, byte[].class, long.class, long.class, boolean.class);
        check(original.getParameterCount() == 6, "bundled JNI constructor remains available");
        MediaInfo legacy = original.newInstance("Test Orbit", "Fixture Artist", new byte[0], 1L, 120L, true);
        check(legacy.getSyncedLyrics().isEmpty(), "legacy DLL metadata never invents lyric support");
        NativeMediaLyrics cache = new NativeMediaLyrics();
        check(cache.update("Spotify", legacy) == TimedLyrics.EMPTY, "unsupported native payload produces empty lyrics");
        String first = "[00:01]A synthetic orbit\n[00:03]A second test spark";
        var supported = info("Test Orbit", 1, first);
        TimedLyrics parsed = cache.update("Spotify", supported);
        check(parsed.lines().size() == 2, "actual optional native payload is parsed");
        check(parsed.cue(1000, 120000).current().equals("A synthetic orbit"), "native lyrics expose synchronized cue");
        check(cache.update("Spotify", info("Test Orbit", 2, first)) == parsed, "position-only poll reuses immutable parsed timeline");
        check(cache.update("Spotify", info("Test Orbit", 2, new String(first))) == parsed, "equal payload text does not reparse");
        TimedLyrics updated = cache.update("Spotify", info("Test Orbit", 2, "[00:01]A changed synthetic orbit"));
        check(updated != parsed && updated.cue(1000, 120000).current().equals("A changed synthetic orbit"), "payload update replaces same-track lyrics");
        check(cache.update("Spotify", info("Next Track", 0, "")) == TimedLyrics.EMPTY, "song change without support clears old lyrics");
        TimedLyrics restored = cache.update("Spotify", supported);
        check(restored != parsed, "one-track cache does not carry old track object across song changes");
        check(cache.update("Browser", supported) != restored, "session source change invalidates parsed cache");
        check(cache.update("Browser", null) == TimedLyrics.EMPTY, "session loss clears lyrics");
        check(cache.update("Spotify", info("Plain Only", 0, "Synthetic words without timestamps")) == TimedLyrics.EMPTY,
            "plain unsupported lyrics never receive fabricated timestamps");
        check(info("Oversized", 0, "x".repeat(512001)).getSyncedLyrics().isEmpty(), "native payload hard limit");
        cache.reset();
        check(cache.update("Spotify", legacy).lines().isEmpty(), "reset returns to unsupported state");
        var legacySnapshot = new MediaPlaybackSnapshot(true,true,false,true,true,true,true,"Spotify","spotify",
            "Test Orbit","Fixture Artist","","","",1000,120000,"",Instant.now());
        check(legacySnapshot.lyrics() == TimedLyrics.EMPTY, "old snapshot constructor remains source compatible and unsupported");
        var snapshot = new MediaPlaybackSnapshot(true,true,false,true,true,true,true,"Spotify","spotify",
            "Test Orbit","Fixture Artist","","","",1000,120000,"",Instant.now(),parsed);
        check(snapshot.lyrics() == parsed, "worker publishes immutable native timeline directly");
        check(MediaPlaybackSnapshot.empty("Nothing playing").lyrics() == TimedLyrics.EMPTY, "empty media snapshots hide lyrics");
        var nullSnapshot = new MediaPlaybackSnapshot(false,false,false,false,false,false,false,"","","","","","","",0,0,"",Instant.now(),null);
        check(nullSnapshot.lyrics() == TimedLyrics.EMPTY, "null payload normalizes to unsupported");
        System.out.println("NativeMediaLyricsCheck PASS: " + checks + " offline ABI/capability/cache assertions; no DLL invocation, disk lyrics, or network");
    }
    private static MediaInfo info(String title, long position, String lyrics) {
        return new MediaInfo(title, "Fixture Artist", new byte[0], position, 120, true, lyrics);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
