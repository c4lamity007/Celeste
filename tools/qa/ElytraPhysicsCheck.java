package dev.c4lam.celeste.rotation;

import dev.c4lam.celeste.action.VanillaInputGuard;
import dev.c4lam.celeste.module.impl.expansion.SeeRotationsFeature;
import java.time.Duration;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.util.math.Vec3d;

/** Production task, manager and adapter; only game/input/presentation boundaries are stubbed. */
public final class ElytraPhysicsCheck {
    private static final MinecraftClient CLIENT = MinecraftClient.getInstance();
    private static final RotationManager MANAGER = RotationManager.getInstance();
    private static int assertions;

    public static void main(String[] args) {
        taskContract();
        aimWindow();
        twoThrowTicks();
        exactReportedVectors();
        ownershipAndPreemption();
        cleanupLifecycle();
        physicsGuards();
        fireworkGuard();
        rejectedSubmitCleanup();
        System.out.println("Elytra flight physics: PASS (" + assertions
            + " assertions; production RotationTask/RotationManager/ElytraSilentAim, isolated game boundaries)");
    }

    private static ClientPlayerEntity reset() {
        CLIENT.player = new ClientPlayerEntity();
        CLIENT.world = new ClientWorld();
        CLIENT.onThread = true;
        VanillaInputGuard.conflict = false;
        SeeRotationsFeature.enabled = true;
        MANAGER.clear();
        MANAGER.prepare(CLIENT);
        return CLIENT.player;
    }

    private static RotationTask task(Object owner, boolean physics, int priority) {
        return RotationTask.builder(owner, new Rotation(-151, -69)).priority(priority)
            .lifetimeTicks(1).lifetime(Duration.ofSeconds(30)).smoothing(180, 180)
            .sensitivityQuantized(false).moveFix(MoveFixMode.OFF).glidingPhysics(physics).build();
    }

    private static Object activate() {
        reset();
        Object owner = new Object();
        check(MANAGER.submit(task(owner, true, 235)), "flight request accepted");
        MANAGER.tick(CLIENT);
        check(MANAGER.isOwnedBy(owner) && MANAGER.glidingPhysics(), "selected owner enables flight physics");
        return owner;
    }

    private static void taskContract() {
        reset();
        Object owner = new Object();
        RotationTask.Builder builder = RotationTask.builder(owner, new Rotation(0, 0));
        RotationTask ordinary = builder.build();
        check(!ordinary.glidingPhysics(), "existing tasks default to ordinary physics");
        RotationTask flight = builder.glidingPhysics(true).build();
        check(flight.glidingPhysics() && !ordinary.glidingPhysics(), "task flag is immutable");
        check(!builder.glidingPhysics(false).build().glidingPhysics(), "builder can explicitly disable flight flag");
        MANAGER.submit(task(owner, true, 235));
        check(!MANAGER.glidingPhysics(), "pending request cannot change physics before arbitration");
        MANAGER.tick(CLIENT);
        check(MANAGER.glidingPhysics() && MANAGER.moveFixMode() == MoveFixMode.OFF,
            "flight physics does not enable keyboard movefix");
    }

    private static void aimWindow() {
        check(ElytraSilentAim.withinAimWindow(100, 100), "aim window allows starting tick");
        check(ElytraSilentAim.withinAimWindow(100, 101), "aim window allows following tick");
        check(!ElytraSilentAim.withinAimWindow(100, 102), "aim window rejects a second waiting tick");
        check(!ElytraSilentAim.withinAimWindow(100, 99), "aim window rejects reversed tick order");
    }

    private static void twoThrowTicks() {
        ClientPlayerEntity player = reset();
        player.yaw = 539;
        player.pitch = 0;
        MANAGER.clear();
        MANAGER.outgoingYawOr(player.yaw); // Seed the previously emitted camera phase before the silent action.
        Object owner = new Object();
        Vec3d velocity = player.velocity;
        check(ElytraSilentAim.submit(owner, new Rotation(-179, -90), 235), "first throw submits");
        MANAGER.tick(CLIENT);
        check(MANAGER.glidingPhysics() && MANAGER.isOwnedBy(owner), "first throw owns flight physics");
        check(MANAGER.outgoingYawOr(player.yaw) == 541 && MANAGER.serverPitchOr(player.pitch) == -90,
            "first throw preserves continuous packet yaw and upward pitch");
        verifyVector(player, 541, -90, "pearl throw");
        check(ElytraSilentAim.physicsPitch(player) == -90, "first throw uses packet pitch for glide lift");
        check(player.yaw == 539 && player.pitch == 0 && player.velocity == velocity,
            "first throw does not mutate camera or motion");

        check(ElytraSilentAim.submit(owner, new Rotation(-147, 0), 235), "second throw submits");
        MANAGER.tick(CLIENT);
        check(MANAGER.outgoingYawOr(player.yaw) == 573 && MANAGER.serverPitchOr(player.pitch) == 0,
            "second throw selects newly reported look");
        verifyVector(player, 573, 0, "wind charge throw");
        check(ElytraSilentAim.physicsPitch(player) == 0, "second throw updates glide pitch");
        check(player.yaw == 539 && player.pitch == 0 && player.velocity == velocity,
            "second throw leaves the local camera and existing motion untouched");

        MANAGER.release(owner);
        check(!MANAGER.glidingPhysics(), "sequence completion releases flight override immediately");
        verifyFallback(player, "completed sequence");
    }

    private static void exactReportedVectors() {
        float[] yaws = {-179, -91.125F, -0.001F, 0, 73.125F, 179};
        float[] pitches = {-90, -89.999F, -37.25F, 0, 37.25F, 89.999F, 90};
        for (float yaw : yaws) {
            for (float pitch : pitches) {
                ClientPlayerEntity player = reset();
                player.yaw = 539;
                player.pitch = 0;
                MANAGER.clear();
                MANAGER.outgoingYawOr(player.yaw);
                Object owner = new Object();
                MANAGER.submit(RotationTask.builder(owner, new Rotation(yaw, pitch))
                    .smoothing(180, 180).sensitivityQuantized(false).moveFix(MoveFixMode.OFF)
                    .glidingPhysics(true).build());
                MANAGER.tick(CLIENT);
                float packetYaw = MANAGER.outgoingYawOr(player.yaw);
                float packetPitch = MANAGER.serverPitchOr(player.pitch);
                verifyVector(player, packetYaw, packetPitch, "reported angle " + yaw + "/" + pitch);
                check(ElytraSilentAim.physicsPitch(player) == packetPitch, "pitch matches selected packet angle");
                check(player.yaw == 539 && player.pitch == 0, "vector calculation is camera-read-only");
                SeeRotationsFeature.enabled = false;
                verifyVector(player, packetYaw, packetPitch, "hidden server rotation");
            }
        }
    }

    private static void ownershipAndPreemption() {
        Object owner = activate(), stranger = new Object();
        Rotation selected = MANAGER.serverRotation();
        MANAGER.release(stranger);
        MANAGER.hold(stranger, new Rotation(20, 80), MoveFixMode.STRICT);
        check(MANAGER.glidingPhysics() && MANAGER.serverRotation().equals(selected),
            "non-owner release/hold cannot alter selected flight state");
        check(!MANAGER.submit(task(stranger, false, 230)), "lower-priority ordinary request loses");
        check(!MANAGER.alignForAction(stranger) && MANAGER.glidingPhysics(),
            "losing owner's action cannot clear selected physics");
        MANAGER.submit(task(owner, true, 235));
        MANAGER.tick(CLIENT);
        check(MANAGER.isOwnedBy(owner) && MANAGER.glidingPhysics(), "losing task flag does not leak");

        MANAGER.submit(task(owner, true, 235));
        check(MANAGER.submit(task(stranger, false, 240)), "higher-priority ordinary request accepted");
        MANAGER.tick(CLIENT);
        check(MANAGER.isOwnedBy(stranger) && !MANAGER.glidingPhysics(), "unflagged winning task clears prior flag");
        verifyFallback(CLIENT.player, "ordinary winner");

        owner = activate();
        MANAGER.hold(owner, new Rotation(15, 23), MoveFixMode.OFF);
        check(MANAGER.glidingPhysics() && MANAGER.serverRotation().equals(new Rotation(15, 23)),
            "owning refinement preserves flight policy");
        verifyVector(CLIENT.player, MANAGER.outgoingYawOr(CLIENT.player.yaw), 23, "owning refinement");
        check(MANAGER.alignForAction(new Object()) && !MANAGER.glidingPhysics(),
            "unrequested action restores camera policy instead of borrowing flight rotation");
    }

    private static void cleanupLifecycle() {
        for (int scenario = 0; scenario < 9; scenario++) {
            Object owner = activate();
            switch (scenario) {
                case 0 -> MANAGER.release(owner); // Module disable/completion path.
                case 1 -> MANAGER.clear();
                case 2 -> MANAGER.tick(CLIENT); // One-tick lease expires without resubmission.
                case 3 -> { CLIENT.world = new ClientWorld(); MANAGER.prepare(CLIENT); }
                case 4 -> { CLIENT.player = new ClientPlayerEntity(); MANAGER.prepare(CLIENT); }
                case 5 -> { CLIENT.world = null; MANAGER.prepare(CLIENT); }
                case 6 -> { CLIENT.player = null; MANAGER.prepare(CLIENT); }
                case 7 -> { CLIENT.player.vehicle = true; MANAGER.tick(CLIENT); }
                case 8 -> { VanillaInputGuard.conflict = true; MANAGER.tick(CLIENT); }
                default -> throw new AssertionError();
            }
            check(!MANAGER.isActive() && !MANAGER.glidingPhysics(), "cleanup clears active flag " + scenario);
            check(MANAGER.activeOwner() == null && MANAGER.moveFixMode() == MoveFixMode.OFF,
                "cleanup releases ownership and movefix " + scenario);
            if (CLIENT.player != null) verifyFallback(CLIENT.player, "cleanup " + scenario);
        }
        Object owner = activate();
        CLIENT.player.vehicle = true;
        check(!MANAGER.submit(task(owner, true, 235)) && !MANAGER.glidingPhysics(),
            "mounted resubmission rejects and immediately deactivates existing owner");
    }

    private static void physicsGuards() {
        for (int scenario = 0; scenario < 7; scenario++) {
            Object owner = activate();
            LivingEntity entity = CLIENT.player;
            switch (scenario) {
                case 0 -> entity = new LivingEntity();
                case 1 -> CLIENT.player.gliding = false;
                case 2 -> CLIENT.player.vehicle = true;
                case 3 -> CLIENT.player.alive = false;
                case 4 -> CLIENT.world = null;
                case 5 -> CLIENT.player = null;
                case 6 -> MANAGER.release(owner);
                default -> throw new AssertionError();
            }
            verifyFallback(entity, "physics guard " + scenario);
        }
    }

    private static void fireworkGuard() {
        for (int scenario = 0; scenario < 7; scenario++) {
            ClientPlayerEntity player = reset();
            Object owner = new Object();
            FireworkRocketEntity rocket = new FireworkRocketEntity(player, scenario != 1);
            CLIENT.world.entities.add(new LivingEntity()); // Non-projectile entities must be ignored.
            switch (scenario) {
                case 0, 1 -> CLIENT.world.entities.add(rocket); // Attached and free owned live rockets.
                case 2 -> { rocket.alive = false; CLIENT.world.entities.add(rocket); }
                case 3 -> CLIENT.world.entities.add(new FireworkRocketEntity(new ClientPlayerEntity(), true));
                case 4 -> CLIENT.world.entities.add(new FireworkRocketEntity(null, false));
                case 5 -> { } // Only the unrelated living entity remains.
                case 6 -> CLIENT.world.entities.clear();
                default -> throw new AssertionError();
            }
            boolean expected = scenario >= 2;
            check(ElytraSilentAim.canAim(CLIENT) == expected, "rocket ownership/liveness guard " + scenario);
            check(ElytraSilentAim.submit(owner, new Rotation(10, -20), 235) == expected,
                "submission obeys rocket guard " + scenario);
            MANAGER.tick(CLIENT);
            check(MANAGER.glidingPhysics() == expected && MANAGER.isOwnedBy(owner) == expected,
                "blocked rocket cannot acquire a flight lease " + scenario);
            check(player.yaw == 27 && player.pitch == 13, "rocket guard does not mutate camera " + scenario);
        }

        Object owner = activate();
        CLIENT.world.entities.add(new FireworkRocketEntity(CLIENT.player, false));
        check(!ElytraSilentAim.canAim(CLIENT) && MANAGER.glidingPhysics(),
            "eligibility query is read-only even with a free owned rocket");
        check(!ElytraSilentAim.submit(owner, new Rotation(0, 0), 235), "owned rocket rejects active owner's renewal");
        check(!MANAGER.glidingPhysics() && !MANAGER.isActive(), "rejected renewal immediately releases own flight lease");
        verifyFallback(CLIENT.player, "firework rejection");

        owner = activate();
        Object rejectedOwner = new Object();
        Rotation selected = MANAGER.serverRotation();
        CLIENT.world.entities.add(new FireworkRocketEntity(CLIENT.player, true));
        check(!ElytraSilentAim.submit(rejectedOwner, new Rotation(80, 80), 240), "rocket rejects another requesting owner");
        check(MANAGER.isOwnedBy(owner) && MANAGER.glidingPhysics() && MANAGER.serverRotation().equals(selected),
            "rejection cannot clear or change another owner's selected flight lease");
    }

    private static void rejectedSubmitCleanup() {
        for (int scenario = 0; scenario < 5; scenario++) {
            Object owner = activate();
            switch (scenario) {
                case 0 -> CLIENT.player = null;
                case 1 -> CLIENT.world = null;
                case 2 -> CLIENT.player.alive = false;
                case 3 -> CLIENT.player.gliding = false;
                case 4 -> CLIENT.player.vehicle = true;
                default -> throw new AssertionError();
            }
            check(!ElytraSilentAim.canAim(CLIENT), "invalid player cannot aim " + scenario);
            check(!ElytraSilentAim.submit(owner, new Rotation(0, 0), 235), "invalid player cannot renew " + scenario);
            check(!MANAGER.glidingPhysics() && !MANAGER.isActive(), "invalid renewal clears own selected flag " + scenario);
        }
    }

    private static void verifyFallback(LivingEntity entity, String label) {
        Vec3d actual = ElytraSilentAim.physicsVector(entity);
        Vec3d expected = entity.getRotationVector();
        vectorEquals(actual, expected, label + " restores vanilla vector");
        check(ElytraSilentAim.physicsPitch(entity) == entity.getPitch(), label + " restores vanilla pitch");
    }

    private static void verifyVector(LivingEntity entity, float yaw, float pitch, String label) {
        // Independent vanilla lookup-table oracle: deliberately not Rotation.direction() or Vec3d.fromPolar().
        float pitchRadians = pitch * .017453292F, yawRadians = -yaw * .017453292F;
        float horizontal = table(pitchRadians, true);
        Vec3d expected = new Vec3d(table(yawRadians, false) * horizontal,
            -table(pitchRadians, false), table(yawRadians, true) * horizontal);
        vectorEquals(ElytraSilentAim.physicsVector(entity), expected, label + " exact entity float-vector formula");
    }

    private static float table(float radians, boolean cosine) {
        long index = (long) (radians * 10430.378350470453 + (cosine ? 16384 : 0));
        return (float) Math.sin((index & 65535L) / 10430.378350470453);
    }

    private static void vectorEquals(Vec3d actual, Vec3d expected, String label) {
        check(Double.doubleToLongBits(actual.x) == Double.doubleToLongBits(expected.x)
            && Double.doubleToLongBits(actual.y) == Double.doubleToLongBits(expected.y)
            && Double.doubleToLongBits(actual.z) == Double.doubleToLongBits(expected.z), label);
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
