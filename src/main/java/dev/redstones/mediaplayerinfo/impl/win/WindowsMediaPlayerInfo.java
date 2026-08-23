package dev.redstones.mediaplayerinfo.impl.win;

import dev.redstones.mediaplayerinfo.IMediaSession;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Loads the user-supplied Windows JNI helper from Celeste's own resources. */
public final class WindowsMediaPlayerInfo {
    private static final String NATIVE_RESOURCE = "/mediaplayerinfo/natives/win/MediaPlayerInfo.dll";
    private static final String CLEANUP_RESOURCE = "/mediaplayerinfo/natives/win/CelesteMediaCleanup.dll";
    private static boolean cleanupAvailable;

    static {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            throw new UnsupportedOperationException("MediaPlayerInfo is Windows-only");
        }
        try {
            Path directory = Files.createTempDirectory("celeste-mediaplayerinfo-");
            Path nativeFile = extract(directory, NATIVE_RESOURCE, "MediaPlayerInfo.dll");
            System.load(nativeFile.toAbsolutePath().toString());
            try {
                Path cleanupFile = extract(directory, CLEANUP_RESOURCE, "CelesteMediaCleanup.dll");
                System.load(cleanupFile.toAbsolutePath().toString());
                cleanupFile.toFile().deleteOnExit();
                cleanupAvailable = true;
            } catch (IOException | UnsatisfiedLinkError ignored) {
                // The media bridge remains usable without the optional cleanup
                // shim. This only affects shutdown of older packaged builds.
                cleanupAvailable = false;
            }
            nativeFile.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load MediaPlayerInfo.dll", exception);
        }
    }

    private static Path extract(Path directory, String resource, String filename) throws IOException {
        Path nativeFile = directory.resolve(filename);
        try (InputStream input = WindowsMediaPlayerInfo.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException(filename + " is missing from Celeste");
            Files.copy(input, nativeFile);
        }
        return nativeFile;
    }

    public native List<IMediaSession> getMediaSessions();

    /** Invokes the bridge's global COM/session cleanup through Celeste's JNI shim. */
    private static native void cleanup();

    public static void cleanupNativeResources() {
        if (!cleanupAvailable) return;
        try {
            cleanup();
        } catch (LinkageError ignored) {
            cleanupAvailable = false;
        }
    }
}
