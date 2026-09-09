package dev.c4lam.celeste.module.impl.combat;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

/** Production query helper + explicit engine-authority adapters; no server or simulated vanilla raycaster. */
public final class SwordAuraRangeCheck {
    private static int checks;
    public static void main(String[] args) {
        ClientPlayerEntity self = new ClientPlayerEntity();
        PlayerEntity target = new PlayerEntity(), friend = new PlayerEntity();
        Entity camera = new Entity();
        camera.setYaw(137.25F); camera.setPitch(-42.75F); camera.setHeadYaw(93.5F);
        EntityHitResult hit = new EntityHitResult(target, new Vec3d(0, 0, 2.99));
        self.query = (tick, actualCamera) -> {
            check(tick == 1F && actualCamera == camera, "same fresh tickDelta/camera path as TBOT");
            check(camera.getYaw() == -75F && camera.getPitch() == 18F, "silent look applied for normal vanilla query");
            check(camera.getHeadYaw() == -75F, "component head-vector path sees silent yaw too");
            return hit;
        };
        check(SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> p == target) == hit, "vanilla eligible result retained without arbitrary aura reach math");
        restored(camera);

        self.query = (tick, actualCamera) -> null;
        check(SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> true) == null, "vanilla out-of-range miss never becomes an aura swing");
        self.query = (tick, actualCamera) -> new HitResult(new Vec3d(0, 0, 1));
        check(SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> true) == null, "vanilla block obstruction is authoritative");
        self.query = (tick, actualCamera) -> new EntityHitResult(new Entity(), new Vec3d(0, 0, 1));
        check(SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> true) == null, "intervening non-player is not skipped for a player behind it");
        self.query = (tick, actualCamera) -> new EntityHitResult(friend, new Vec3d(0, 0, 1));
        check(SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> p != friend) == null, "friend first hit blocks target behind rather than filtering raycast geometry");

        self.query = (tick, actualCamera) -> hit;
        AtomicInteger checksWithComponent = new AtomicInteger();
        self.getMainHandStack().set(DataComponentTypes.ATTACK_RANGE, new AttackRangeComponent((player, pos) -> {
            checksWithComponent.incrementAndGet();
            check(player == self && pos == hit.getPos(), "same self and exact vanilla contact point reach the component");
            return false;
        }));
        check(SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> true) == null && checksWithComponent.get() == 1,
            "TBOT component-specific minimum/maximum veto is obeyed");
        self.getMainHandStack().set(DataComponentTypes.ATTACK_RANGE, new AttackRangeComponent((player, pos) -> true));
        check(SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> true) == hit, "component-permitted vanilla result accepted");
        self.setMainHandStack(new ItemStack());
        check(SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> true) == hit, "axe/item lacking component still depends on vanilla query");

        int before = self.queries;
        check(SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> true) == hit, "initial queued target was valid");
        self.query = (tick, actualCamera) -> null;
        check(SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> true) == null && self.queries == before + 2,
            "fresh subsequent query sees target leaving reach; no stale same-tick cache");
        self.query = (tick, actualCamera) -> hit;
        self.setMainHandStack(new ItemStack());
        self.getMainHandStack().set(DataComponentTypes.ATTACK_RANGE, new AttackRangeComponent((player, pos) -> false));
        check(SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> true) == null, "changed slot/component revalidates immediately");
        restored(camera);

        self.query = (tick, actualCamera) -> { throw new IllegalStateException("synthetic query failure"); };
        try { SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> true); throw new AssertionError("query failure swallowed"); }
        catch (IllegalStateException expected) { checks++; }
        restored(camera);
        self.query = (tick, actualCamera) -> hit;
        try { SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> { throw new IllegalStateException("synthetic filter failure"); }); throw new AssertionError("filter failure swallowed"); }
        catch (IllegalStateException expected) { checks++; }
        restored(camera);
        self.getMainHandStack().set(DataComponentTypes.ATTACK_RANGE, new AttackRangeComponent((player, pos) -> { throw new IllegalStateException("synthetic range failure"); }));
        try { SwordAuraTargeting.playerHit(self, camera, -75, 18, p -> true); throw new AssertionError("range failure swallowed"); }
        catch (IllegalStateException expected) { checks++; }
        restored(camera);

        self.setMainHandStack(new ItemStack());
        self.setYaw(-26); self.setPitch(8); self.setHeadYaw(12);
        self.query = (tick, actualCamera) -> {
            check(actualCamera == self && self.getYaw() == 41 && self.getHeadYaw() == 41 && self.getPitch() == -13,
                "normal self-camera path uses scoped silent values");
            return hit;
        };
        check(SwordAuraTargeting.playerHit(self, self, 41, -13, p -> true) == hit, "self-camera path accepted");
        check(self.getYaw() == -26 && self.getPitch() == 8 && self.getHeadYaw() == 12, "self camera restores all three independent values");
        before = self.queries;
        check(SwordAuraTargeting.playerHit(null, camera, 0, 0, p -> true) == null, "no player fails closed");
        check(SwordAuraTargeting.playerHit(self, null, 0, 0, p -> true) == null, "no camera fails closed");
        check(SwordAuraTargeting.playerHit(self, camera, Float.NaN, 0, p -> true) == null, "invalid yaw fails closed");
        check(SwordAuraTargeting.playerHit(self, camera, 0, Float.POSITIVE_INFINITY, p -> true) == null, "invalid pitch fails closed");
        check(self.queries == before, "invalid input never queries or touches the camera");
        restored(camera);

        self.query = (tick, actualCamera) -> actualCamera.getYaw() == 41 && actualCamera.getPitch() == -13 ? hit : null;
        check(SwordAuraTargeting.playerHit(self, camera, 41, -13, p -> true) == hit,
            "prospective off-crosshair flick query can succeed before rotation ownership");
        restored(camera);
        check(SwordAuraTargeting.playerHit(self, camera, 137.25F, -42.75F, p -> true) == null,
            "actual execution rotation still revalidates instead of reusing prospective flick hit");
        self.query = (tick, actualCamera) -> null;
        check(SwordAuraTargeting.playerHit(self, camera, 41, -13, p -> true) == null,
            "prospective flick cannot authorize an out-of-range vanilla miss");
        restored(camera);

        Random random = new Random(0x5A0DA);
        for (int i = 0; i < 1000; i++) {
            float yaw = random.nextFloat() * 72000 - 36000, pitch = random.nextFloat() * 180 - 90;
            self.query = (tick, actualCamera) -> {
                check(actualCamera.getYaw() == yaw && actualCamera.getPitch() == pitch && actualCamera.getHeadYaw() == yaw,
                    "yaw/pitch/head are applied exactly, including multi-turn yaw");
                return hit;
            };
            check(SwordAuraTargeting.playerHit(self, camera, yaw, pitch, p -> true) == hit, "fresh allowed hit remains allowed");
            restored(camera);
        }
        System.out.println("SwordAuraRangeCheck PASS: " + checks + " scoped-rotation/vanilla-authority assertions; adapter checks, not in-game verification");
    }
    private static void restored(Entity camera) {
        check(camera.getYaw() == 137.25F && camera.getPitch() == -42.75F && camera.getHeadYaw() == 93.5F,
            "camera yaw/pitch/head restored with no persistent client aim change");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
