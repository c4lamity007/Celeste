package dev.c4lam.celeste.media;

import java.time.Duration;

/** Optional manual connectivity check. Uses a fixed synthetic query, never the current media session. */
final class LrclibLyricsLiveSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("This smoke check accepts no listening metadata");
        var transport = new LrclibHttpTransport();
        try {
            String title = "Celeste Synthetic Probe 20260908", artist = "Celeste QA Fixture";
            String body = transport.fetch(LrclibLyricsSource.searchUri(title, artist));
            TimedLyrics lyrics = LrclibLyricsSource.parseResponse(body,
                LrclibLyricsSource.Track.of("synthetic", title, artist, 120000));
            System.out.println("LRCLIB HTTPS 200; validated synthetic cue count=" + lyrics.lines().size());
        } finally {
            transport.close();
            if (!transport.awaitStopped(Duration.ofSeconds(15))) throw new IllegalStateException("HTTPS shutdown incomplete");
        }
    }
}
