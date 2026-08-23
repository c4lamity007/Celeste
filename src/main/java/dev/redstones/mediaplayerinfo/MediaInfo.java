package dev.redstones.mediaplayerinfo;

/** JNI data shape required by the bundled Windows MediaPlayerInfo bridge. */
public final class MediaInfo {
    private final String title;
    private final String artist;
    private final byte[] artworkPng;
    private final long position;
    private final long duration;
    private final boolean playing;

    public MediaInfo(String title, String artist, byte[] artworkPng,
                     long position, long duration, boolean playing) {
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.artworkPng = artworkPng == null ? new byte[0] : artworkPng.clone();
        this.position = Math.max(0L, position);
        this.duration = Math.max(0L, duration);
        this.playing = playing;
    }

    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public byte[] getArtworkPng() { return artworkPng.clone(); }
    public long getPosition() { return position; }
    public long getDuration() { return duration; }
    public boolean isPlaying() { return playing; }
}
