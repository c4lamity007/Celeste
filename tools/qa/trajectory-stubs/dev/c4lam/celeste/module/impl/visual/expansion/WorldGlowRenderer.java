package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.util.math.Vec3d;

/** Render submission values only; does not start pipelines, Fabric or a game. */
public final class WorldGlowRenderer {
    public enum Texture { RIBBON, TRAJECTORY, SOFT }
    public record Quad(Texture texture, Vec3d a, Vec3d b, Vec3d c, Vec3d d,
                       int color, int endColor, float uStart, float uEnd) { }
}
