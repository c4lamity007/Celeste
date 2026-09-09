package dev.c4lam.celeste.media;

import dev.redstones.mediaplayerinfo.MediaInfo;
import java.util.Objects;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** One-track in-memory parse cache, fed only by actual native media payloads on the media worker. */
final class NativeMediaLyrics {
    // Released host-support MediaInfo has only the original six-argument ABI.
    // Optional future payload support must not link to a getter absent from that resident class.
    private static final Method SYNCED_LYRICS = optionalLyricsGetter();
    private String owner, title, artist, payload;
    private long duration;
    private TimedLyrics parsed = TimedLyrics.EMPTY;

    synchronized TimedLyrics update(String source, MediaInfo media) {
        if (media == null) { reset(); return TimedLyrics.EMPTY; }
        String input = syncedLyrics(media);
        if (Objects.equals(owner, source) && Objects.equals(title, media.getTitle())
            && Objects.equals(artist, media.getArtist()) && duration == media.getDuration()
            && Objects.equals(payload, input)) return parsed;
        owner = source; title = media.getTitle(); artist = media.getArtist();
        duration = media.getDuration(); payload = input;
        parsed = input.isBlank() ? TimedLyrics.EMPTY : TimedLyrics.parse(input);
        return parsed;
    }
    private static Method optionalLyricsGetter() {
        try { return MediaInfo.class.getMethod("getSyncedLyrics"); }
        catch (NoSuchMethodException | SecurityException unavailable) { return null; }
    }
    private static String syncedLyrics(MediaInfo media) {
        if (SYNCED_LYRICS == null) return "";
        try {
            Object payload = SYNCED_LYRICS.invoke(media);
            return payload instanceof String text && text.length() <= 512_000 ? text : "";
        } catch (IllegalAccessException | InvocationTargetException | LinkageError unavailable) {
            return "";
        }
    }
    synchronized void reset() {
        owner = title = artist = payload = null; duration = 0; parsed = TimedLyrics.EMPTY;
    }
}

