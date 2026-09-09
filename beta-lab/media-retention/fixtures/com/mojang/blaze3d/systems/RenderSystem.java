package com.mojang.blaze3d.systems;
public final class RenderSystem {
    private static final Thread owner = Thread.currentThread();
    public static boolean isOnRenderThread() { return Thread.currentThread() == owner; }
    public static void assertOnRenderThread() { if (!isOnRenderThread()) throw new AssertionError("wrong render thread"); }
}
