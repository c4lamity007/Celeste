package net.minecraft.client.texture;
public final class NativeImage implements AutoCloseable {
    public static final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    public static final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    public static int closed;
    public static NativeImage read(byte[] png) throws java.io.IOException {
        entered.countDown();
        boolean interrupted = false;
        for (;;) try { release.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
        return new NativeImage();
    }
    public int getWidth() { return 1; }
    public int getHeight() { return 1; }
    public void close() { closed++; }
}
