package dev.c4lam.celeste.media;

import dev.redstones.mediaplayerinfo.MediaInfo;

/** Run against the released six-argument host-support MediaInfo, without loading the native DLL. */
public final class NativeMediaLyricsLegacyCheck {
    public static void main(String[] args) {
        var media = new MediaInfo("Synthetic Track", "Fixture Artist", new byte[0], 1L, 120L, true);
        var nativeLyrics = new NativeMediaLyrics();
        if (!nativeLyrics.update("spotify", media).lines().isEmpty()) throw new AssertionError("Missing native payload must stay empty");
        if (!nativeLyrics.update("spotify", media).lines().isEmpty()) throw new AssertionError("Legacy ABI cache must remain safe");
        nativeLyrics.reset();
        if (!nativeLyrics.update("spotify", null).lines().isEmpty()) throw new AssertionError("Session loss must stay empty");
        System.out.println("NativeMediaLyricsLegacyCheck PASS: 3 six-argument ABI assertions; no native DLL invocation");
    }
}
