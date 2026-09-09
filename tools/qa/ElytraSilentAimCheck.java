package dev.c4lam.celeste.rotation;

import dev.c4lam.celeste.module.impl.expansion.SeeRotationsFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.util.math.Vec3d;

/** Executes the production adapter against isolated game/arbiter boundaries, not a cloned adapter. */
public final class ElytraSilentAimCheck {
    private static int assertions;
    private static final MinecraftClient CLIENT = MinecraftClient.getInstance();
    private static final RotationManager MANAGER = RotationManager.getInstance();

    public static void main(String[] args) {
        submitContract();
        renderGuards();
        coherentRender();
        System.out.println("Elytra silent-aim adapter: PASS (" + assertions + " assertions; isolated game/manager stubs)");
    }

    private static void reset() {
        CLIENT.player = new MinecraftClient.Player();
        MANAGER.active = MANAGER.accepted = SeeRotationsFeature.enabled = true;
        MANAGER.submits = MANAGER.renderCalls = 0;
        MANAGER.lastTask = null;
        MANAGER.lastRenderDelta = -1;
        MANAGER.display = new Rotation(90, -30);
        MANAGER.packet = new Rotation(-45, 22);
    }

    private static void submitContract() {
        reset();
        Object owner = new Object();
        Rotation target = new Rotation(173, -79);
        check(ElytraSilentAim.submit(owner, target, 235), "arbiter acceptance returned");
        RotationTask task = MANAGER.lastTask;
        check(task.owner() == owner && task.target() == target, "exact owner and solved target forwarded");
        check(task.priority() == 235 && task.lifetimeTicks() == 1, "priority and bounded lease retained");
        check(task.moveFixMode() == MoveFixMode.OFF && !task.sensitivityQuantized(), "no flight correction or aim quantization");
        check(task.maximumYawStep() == 180 && task.maximumPitchStep() == 90, "existing tick rotation step preserved");
        MANAGER.accepted = false;
        check(!ElytraSilentAim.submit(owner, target, 230), "arbiter contention returned");
        int before = MANAGER.submits;
        CLIENT.player.gliding = false;
        check(!ElytraSilentAim.submit(owner, target, 230) && MANAGER.submits == before, "landed player submits nothing");
        CLIENT.player = null;
        check(!ElytraSilentAim.submit(owner, target, 230) && MANAGER.submits == before, "missing player submits nothing");
    }

    private static void renderGuards() {
        for (int scenario = 0; scenario < 6; scenario++) {
            reset();
            PlayerEntityRenderState pose = new PlayerEntityRenderState();
            switch (scenario) {
                case 0 -> CLIENT.player = null;
                case 1 -> pose.id++;
                case 2 -> pose.isGliding = false;
                case 3 -> CLIENT.player.gliding = false;
                case 4 -> SeeRotationsFeature.enabled = false;
                case 5 -> MANAGER.active = false;
                default -> throw new AssertionError();
            }
            ElytraSilentAim.finishRender(pose);
            check(pose.bodyYaw == 71 && pose.relativeHeadYaw == 19 && pose.pitch == 8, "guard preserves look " + scenario);
            check(!pose.applyFlyingRotation && pose.flyingRotation == .314F, "guard preserves bank " + scenario);
            check(MANAGER.renderCalls == 0, "guard skips arbiter rendering " + scenario);
        }
        reset();
        LivingEntityRenderState mob = new LivingEntityRenderState();
        ElytraSilentAim.finishRender(mob);
        check(mob.bodyYaw == 71 && mob.pitch == 8 && MANAGER.renderCalls == 0, "non-player state unchanged");
    }

    private static void coherentRender() {
        reset();
        var player = CLIENT.player;
        Vec3d velocity = player.velocity, previousVelocity = player.state.velocity;
        Rotation packet = MANAGER.packet;
        PlayerEntityRenderState pose = new PlayerEntityRenderState();
        ElytraSilentAim.finishRender(pose);
        check(pose.bodyYaw == 90 && pose.pitch == -30 && pose.relativeHeadYaw == 0, "look comes from render interpolation");
        check(MANAGER.lastRenderDelta == .5F, "vanilla state age supplies tick interpolation");
        check(pose.applyFlyingRotation && Math.abs(pose.flyingRotation - Math.PI / 2) < .0002, "bank aligns projected look with flight velocity");
        check(pose.glidingTicks == 12.5F && pose.leaningPitch == .2F && pose.leftWingPitch == .4F
            && pose.leftWingYaw == .5F && pose.leftWingRoll == .6F && !pose.usingRiptide, "other vanilla pose fields untouched");
        check(player.velocity == velocity && player.state.velocity == previousVelocity && player.yaw == 27
            && player.pitch == 13 && player.gliding && player.age == 100, "camera, motion and flight state unchanged");
        check(MANAGER.packet == packet && MANAGER.lastTask == null && MANAGER.submits == 0, "render does not submit or alter packet aim");
        float bank = pose.flyingRotation;
        ElytraSilentAim.finishRender(pose);
        check(pose.flyingRotation == bank && pose.bodyYaw == 90, "repeated render pass is idempotent");

        player.state.velocity = new Vec3d(1, -.1, 0);
        player.velocity = new Vec3d(0, -.2, 1);
        pose.age = 100;
        ElytraSilentAim.finishRender(pose);
        check(Math.abs(pose.flyingRotation) < .0002, "start-of-tick bank uses previous velocity");
        pose.age = 100.5F;
        ElytraSilentAim.finishRender(pose);
        check(Math.abs(pose.flyingRotation - Math.PI / 4) < .0002, "mid-tick bank uses lerped velocity");
        pose.age = 101;
        ElytraSilentAim.finishRender(pose);
        check(Math.abs(pose.flyingRotation - Math.PI / 2) < .0002, "end-of-tick bank uses current velocity");
        for (float age : new float[] {99, 102}) {
            pose.age = age;
            ElytraSilentAim.finishRender(pose);
            check(MANAGER.lastRenderDelta == (age < 100 ? 0 : 1), "state delta clamped");
        }
        MANAGER.display = new Rotation(90, -90);
        pose.age = 100.5F;
        ElytraSilentAim.finishRender(pose);
        check(!pose.applyFlyingRotation && pose.flyingRotation == 0, "vertical look clears inherited bank");
        MANAGER.display = new Rotation(90, -30);
        player.state.velocity = player.velocity = new Vec3d(0, -.2, 0);
        ElytraSilentAim.finishRender(pose);
        check(!pose.applyFlyingRotation && pose.flyingRotation == 0, "vertical movement clears inherited bank");
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
