package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.silent.SilentSlotManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlocksAttacksComponent;
import net.minecraft.component.type.WeaponComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

/** Actual production policy/support, with explicit external authority adapters, not an engine simulation. */
public final class ShieldBreakCheck {
    private static int checks;

    public static void main(String[] args) {
        percentages();
        policyTiming();
        boundedClickLoop();
        supportEndpointsAndScope();
        observedBlockingRules();
        supportTimingAndLifecycle();
        actionGuards();
        secondClickRevalidation();
        System.out.println("PASS ShieldBreakCheck: " + checks + " behavioral assertions against production policy/support");
    }

    private static void percentages() {
        DoubleSupplier forbidden = () -> { throw new AssertionError("Endpoint must not sample RNG"); };
        equal(1, ShieldBreakPolicy.clicks(0, 0, forbidden), "stun 0 / miss 0 is one click");
        equal(2, ShieldBreakPolicy.clicks(100, 0, forbidden), "stun 100 / miss 0 is two clicks");
        equal(0, ShieldBreakPolicy.clicks(0, 100, forbidden), "miss 100 beats normal click");
        equal(0, ShieldBreakPolicy.clicks(100, 100, forbidden), "miss 100 beats two-click stun");
        equal(2, ShieldBreakPolicy.clicks(150, -10, forbidden), "finite percentages clamp to endpoints");
        equal(1, ShieldBreakPolicy.clicks(Double.NaN, Double.POSITIVE_INFINITY, forbidden), "non-finite settings fail to zero");
        Samples below = new Samples(0.249999);
        equal(2, ShieldBreakPolicy.clicks(25, 0, below), "intermediate stun below threshold");
        equal(1, below.used, "stun uses one sample");
        equal(1, ShieldBreakPolicy.clicks(25, 0, new Samples(0.25)), "stun threshold itself is excluded");
        equal(0, ShieldBreakPolicy.clicks(100, 25, new Samples(0.249999)), "intermediate miss below threshold");
        equal(2, ShieldBreakPolicy.clicks(100, 25, new Samples(0.25)), "miss threshold itself is excluded");
        Samples missed = new Samples(0.49);
        equal(0, ShieldBreakPolicy.clicks(50, 50, missed), "miss samples first and short-circuits stun");
        equal(1, missed.used, "missed attempt never draws a stun roll");
        Samples stunned = new Samples(0.50, 0.20);
        equal(2, ShieldBreakPolicy.clicks(50, 50, stunned), "one miss draw then one stun draw");
        equal(2, stunned.used, "two independent draws on intermediate non-miss");
        equal(1, ShieldBreakPolicy.clicks(50, 50, new Samples(0.99, 0.99)), "non-miss non-stun still performs normal hit");
        for (double invalid : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.001, 1, 2}) {
            equal(1, ShieldBreakPolicy.clicks(50, 50, new Samples(invalid, invalid)), "invalid samples never win a probability roll");
        }
        for (int percent : new int[] {1, 25, 50, 75, 99}) {
            int doubles = 0;
            for (int sample = 0; sample < 100; sample++) {
                if (ShieldBreakPolicy.clicks(percent, 0, new Samples(sample / 100.0)) == 2) doubles++;
            }
            equal(percent, doubles, "deterministic stratified stun percentage " + percent);
        }
    }

    private static void policyTiming() {
        for (double delay : new double[] {0, 0.001, 49.999, 50, 50.001, 75, 1000, 5000}) {
            int ticks = delay == 0 ? 0 : delay <= 50 ? 1 : delay <= 75 ? 2 : 20;
            ShieldBreakPolicy policy = new ShieldBreakPolicy();
            check(policy.ready(7, 100, delay) == (ticks == 0), "delay first observation " + delay);
            if (ticks > 0) check(!policy.ready(7, 100 + ticks - 1, delay), "ceil delay does not fire early " + delay);
            check(policy.ready(7, 100 + ticks, delay), "ceil delay fires on boundary " + delay);
            policy.attempted(100 + ticks);
            check(!policy.ready(7, 107 + ticks, delay), "retry blocked for first seven ticks");
            check(policy.ready(7, 108 + ticks, delay), "retry allowed at eight ticks");
        }
        ShieldBreakPolicy policy = new ShieldBreakPolicy();
        check(!policy.readyFor(7, 100), "unarmed policy cannot act");
        check(!policy.ready(7, 100, 75), "initial target starts delay");
        check(!policy.ready(8, 102, 75), "target replacement restarts delay");
        check(!policy.readyFor(7, 104), "old target cannot act after replacement");
        check(policy.ready(8, 104, 75), "new target reaches its own boundary");
        check(!policy.readyFor(8, 103), "stale callback tick rejected");
        check(!policy.ready(8, 10, 75), "clock rollback restarts delay");
        check(policy.ready(8, 12, 75), "clock rollback recovers without old timer");
        policy.reset();
        check(!policy.readyFor(8, 10000), "reset invalidates pending callback");
        check(!policy.ready(8, 13, 75), "reset restarts observation delay");
        check(policy.ready(8, 15, 75), "reset recovers cleanly");
        for (double delay : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            check(new ShieldBreakPolicy().ready(7, 1, delay), "invalid delay defaults to zero");
        }
        ShieldBreakPolicy overflow = new ShieldBreakPolicy();
        check(!overflow.ready(7, Long.MAX_VALUE - 1, 1000), "overflow delay does not wrap ready time");
        check(overflow.ready(7, Long.MAX_VALUE, 1000), "overflow ready time saturates");
    }

    private static void boundedClickLoop() {
        int[] hits = {0};
        equal(0, ShieldBreakPolicy.runClicks(-1, () -> true, () -> hits[0]++), "negative burst bounded to zero");
        equal(2, ShieldBreakPolicy.runClicks(99, () -> true, () -> hits[0]++), "oversized burst bounded to two");
        equal(2, hits[0], "bounded loop sends only two calls");
        int[] valid = {0};
        equal(1, ShieldBreakPolicy.runClicks(2, () -> valid[0]++ == 0, () -> {}), "second click rechecks guard");
        equal(0, ShieldBreakPolicy.runClicks(2, () -> false, () -> { throw new AssertionError("invalid click"); }), "invalid first guard sends nothing");
    }

    private static void supportEndpointsAndScope() {
        for (boolean silent : new boolean[] {false, true}) {
            for (int stun : new int[] {0, 100}) {
                Fixture f = new Fixture();
                f.context.sliders.put("Stun %", (double) stun);
                f.arm();
                check(f.attempt(silent), "eligible endpoint attempt submitted");
                int expected = stun == 0 ? 1 : 2;
                equal(expected, f.hits.size(), "full support endpoint click count");
                equal(expected, f.client.player.swings, "every actual click swings main hand");
                equal(1, CombatLoadout.scopeCalls, "one scope encloses whole burst");
                check(CombatLoadout.lastSilent == silent, "visible/silent flag passed through unchanged");
                check(CombatLoadout.lastOwner == f.owner && CombatLoadout.lastPriority == 70, "owner and priority retained");
                List<String> events = expected == 1 ? List.of("enter:2", "attack:2", "restore:0")
                    : List.of("enter:2", "attack:2", "attack:2", "restore:0");
                check(CombatLoadout.events.equals(events), "same slot retained with no restore between clicks");
                equal(0, CombatLoadout.selectedSlot, "original slot restored after burst");
                equal(0, CombatLoadout.scopeDepth, "slot scope always closes");
                check(!f.attempt(silent), "duplicate same-tick attempt cannot reroll or repeat");
            }
        }
        Fixture miss = new Fixture();
        miss.context.sliders.put("Miss %", 100.0);
        miss.arm();
        check(!miss.attempt(true), "miss returns no local submission");
        equal(0, miss.hits.size(), "miss 100 sends zero attacks");
        equal(0, miss.client.player.swings, "miss 100 sends zero swings");
        equal(0, CombatLoadout.scopeCalls, "miss does not touch selected slot");
        miss.context.sliders.put("Miss %", 0.0);
        check(!miss.attempt(true), "miss consumes opportunity rather than rerolling immediately");
        miss.tick(107);
        check(!miss.support.ready(miss.context, miss.target), "miss observes seven retry ticks");
        miss.tick(108);
        check(miss.support.ready(miss.context, miss.target) && miss.attempt(true), "miss recovers at eighth tick");
    }

    private static void observedBlockingRules() {
        Fixture f = new Fixture();
        f.target.blockingItem = null;
        check(!ShieldBreakSupport.blockingFrom(f.client, f.target), "held but not raised shield rejected");
        check(f.target.heldShield != null && f.target.blockingQueries == 1, "getBlockingItem owns actual raise-delay decision");
        f.tick(500);
        check(!f.support.ready(f.context, f.target), "elapsed client time cannot override engine null blocking query");
        f.target.blockingItem = f.target.heldShield;
        check(ShieldBreakSupport.blockingFrom(f.client, f.target), "engine-raised shield accepted");
        check(f.block.queriedDamage == 1 && f.block.queriedSource == f.client.player.damageSources.source,
            "pure component receives positive probe and actual player-attack source");
        check(f.target.queriedPitch == 0 && f.target.queriedYaw == f.target.headYaw, "horizontal head yaw supplied to engine vector query");
        close(0, f.block.queriedAngle, "front hit angle");
        f.client.player.position = new Vec3d(0, 900, 2);
        check(ShieldBreakSupport.blockingFrom(f.client, f.target), "vertical separation does not change blocking angle");
        close(0, f.block.queriedAngle, "height omitted from normalized incoming direction");
        f.client.player.position = new Vec3d(0, 0, -2);
        check(!ShieldBreakSupport.blockingFrom(f.client, f.target), "behind shield rejected by component");
        close(Math.PI, f.block.queriedAngle, "behind angle is pi radians");
        f.block.accepts = angle -> true;
        check(ShieldBreakSupport.blockingFrom(f.client, f.target), "custom component angle remains authoritative");
        f.block.accepts = angle -> false;
        check(!ShieldBreakSupport.blockingFrom(f.client, f.target), "component reduction veto respected");
        f.block.accepts = angle -> true;
        f.block.scale = 0;
        check(!ShieldBreakSupport.blockingFrom(f.client, f.target), "zero disable scale rejected");
        f.block.scale = 1;
        f.block.bypass = Optional.of("fixture:bypass");
        f.client.player.damageSources.source.tags.add("fixture:bypass");
        check(!ShieldBreakSupport.blockingFrom(f.client, f.target), "configured bypass damage tag rejected");
        f.block.bypass = Optional.empty();
        f.client.player.usingItem = true;
        check(!ShieldBreakSupport.blockingFrom(f.client, f.target), "attacker actively using own item rejected");
        check(!ShieldBreakSupport.blockingFrom(null, f.target), "null client rejected");
        check(!ShieldBreakSupport.disablingAxe(new ItemStack()), "non-axe rejected");
        ItemStack axe = Fixture.axe(0);
        check(!ShieldBreakSupport.disablingAxe(axe), "axe with zero disabling component rejected");
        axe.set(DataComponentTypes.WEAPON, null);
        check(!ShieldBreakSupport.disablingAxe(axe), "axe missing weapon component rejected");
        check(ShieldBreakSupport.disablingAxe(Fixture.axe(5)), "positive disabling axe accepted");
    }

    private static void supportTimingAndLifecycle() {
        Fixture f = new Fixture();
        f.context.sliders.put("Stun delay", 75.0);
        check(!f.support.ready(f.context, f.target), "support reaction delay begins on eligibility");
        f.tick(101);
        check(!f.support.ready(f.context, f.target), "75 ms does not round down to one tick");
        f.tick(102);
        check(f.support.ready(f.context, f.target), "75 ms rounds up to two ticks");
        f.support.reset();
        check(!f.attempt(true), "module reset invalidates already-armed callback");
        check(!f.support.ready(f.context, f.target), "module reset starts fresh delay");
        f.tick(104);
        check(f.support.ready(f.context, f.target), "module reset delay completes");
        LivingEntity other = f.newTarget(8);
        check(!f.support.ready(f.context, other), "target change starts fresh delay");
        check(!f.attempt(true), "old target callback rejected after new target observation");
        f.tick(106);
        check(f.support.ready(f.context, other), "new target delay completes");
        f.target = other;
        f.client.world = new ClientWorld();
        f.target.world = f.client.world;
        f.client.player.world = f.client.world;
        check(!f.attempt(true), "world identity change rejects pending callback");
        check(!f.support.ready(f.context, f.target), "new world starts fresh delay");
        f.tick(108);
        check(f.support.ready(f.context, f.target), "new world delay completes");
        f.replacePlayer();
        check(!f.attempt(true), "player identity change rejects pending callback");
        check(!f.support.ready(f.context, f.target), "new player starts fresh delay");
        f.tick(110);
        check(f.support.ready(f.context, f.target), "new player delay completes");
        f.tick(10);
        check(!f.support.ready(f.context, f.target), "support handles clock rollback");
        f.tick(12);
        check(f.support.ready(f.context, f.target), "rollback recovers after new reaction delay");
        f.target.blockingItem = null;
        check(!f.support.ready(f.context, f.target), "lost blocking eligibility clears pending readiness");
        f.target.blockingItem = f.target.heldShield;
        check(!f.support.ready(f.context, f.target), "re-raised shield gets fresh delay");
    }

    private static void actionGuards() {
        blocked("missing axe", f -> CombatLoadout.axeSlot = -1);
        blocked("axe lost disabling component", f -> f.client.player.inventory.stacks[2] = Fixture.axe(0));
        blocked("contended silent slot", f -> { SilentSlotManager.getInstance().active = true; SilentSlotManager.getInstance().owner = new Object(); });
        blocked("rotation owned elsewhere", f -> RotationManager.getInstance().owner = new Object());
        blocked("screen opened", f -> f.client.currentScreen = new Object());
        blocked("target eligibility veto", f -> Targeting.allow = false);
        blocked("vanilla range/ray veto", f -> VanillaCombatReach.allow = false);
        blocked("own item started", f -> f.client.player.usingItem = true);
        blocked("target died", f -> f.target.alive = false);
        Fixture noOwner = new Fixture();
        noOwner.arm();
        check(!noOwner.support.attempt(null, noOwner.context, noOwner.target, 70, true), "no owner cannot submit");
        equal(0, noOwner.hits.size(), "no-owner attack count");
        Fixture denied = new Fixture();
        denied.arm();
        CombatLoadout.allowScope = false;
        check(!denied.attempt(true), "slot acquisition denied sends no attack");
        equal(0, denied.hits.size(), "denied-scope count");
        CombatLoadout.allowScope = true;
        check(denied.attempt(true), "failed acquisition does not consume successful-attempt retry");
        Fixture owned = new Fixture();
        owned.arm();
        SilentSlotManager.getInstance().active = true;
        SilentSlotManager.getInstance().owner = owned.owner;
        check(owned.attempt(true), "existing slot scope owned by this module is allowed");
        Fixture changedAtScope = new Fixture();
        changedAtScope.arm();
        CombatLoadout.beforeAction = () -> changedAtScope.client.currentScreen = new Object();
        check(!changedAtScope.attempt(true), "guard rechecked after scope acquisition before first click");
        equal(0, changedAtScope.hits.size(), "invalid pre-click context sends no hit");
        equal(0, CombatLoadout.scopeDepth, "invalid pre-click context still closes scope");
    }

    private static void blocked(String reason, Consumer<Fixture> mutation) {
        Fixture f = new Fixture();
        f.arm();
        mutation.accept(f);
        check(!f.attempt(true), reason + " rejected");
        equal(0, f.hits.size(), reason + " sends nothing");
        equal(0, CombatLoadout.scopeCalls, reason + " never opens scope");
    }

    private static void secondClickRevalidation() {
        stopsSecond("death", f -> f.target.alive = false);
        stopsSecond("removal", f -> f.target.removed = true);
        stopsSecond("player change", Fixture::replacePlayer);
        stopsSecond("world change", f -> f.client.world = new ClientWorld());
        stopsSecond("screen change", f -> f.client.currentScreen = new Object());
        stopsSecond("own item use", f -> f.client.player.usingItem = true);
        stopsSecond("target eligibility change", f -> Targeting.allow = false);
        stopsSecond("vanilla ray/range change", f -> VanillaCombatReach.allow = false);
        stopsSecond("weapon component change", f -> f.client.player.inventory.stacks[2] = Fixture.axe(0));
        stopsSecond("axe object replacement", f -> f.client.player.inventory.stacks[2] = Fixture.axe(5));
        stopsSecond("main hand slot change", f -> f.client.player.inventory.selectedSlot = 0);
        stopsSecond("scheduler tick change", f -> f.tick(101));
        stopsSecond("rotation ownership change", f -> RotationManager.getInstance().owner = new Object());
        Fixture lowered = new Fixture();
        lowered.arm();
        lowered.afterFirst = () -> lowered.target.blockingItem = null;
        check(lowered.attempt(true), "first axe click still submits");
        equal(2, lowered.hits.size(), "shield lowering alone does not cancel requested immediate second click");
    }

    private static void stopsSecond(String reason, Consumer<Fixture> mutation) {
        Fixture f = new Fixture();
        f.arm();
        f.afterFirst = () -> mutation.accept(f);
        check(f.attempt(true), reason + " still reports the one submitted click");
        equal(1, f.hits.size(), reason + " prevents second click");
        check(CombatLoadout.events.equals(List.of("enter:2", "attack:2", "restore:0")), reason + " exits scope safely");
    }

    private static final class Fixture {
        final MinecraftClient client = new MinecraftClient();
        final ModuleContext context = new ModuleContext(client);
        final ShieldBreakSupport support = new ShieldBreakSupport();
        final Object owner = new Object();
        final BlocksAttacksComponent block = new BlocksAttacksComponent(angle -> angle <= Math.PI / 2);
        final List<LivingEntity> hits = new ArrayList<>();
        LivingEntity target;
        Runnable afterFirst = () -> {};
        long expectedAttackTick;

        Fixture() {
            CombatLoadout.reset();
            Targeting.allow = true;
            VanillaCombatReach.allow = true;
            VanillaCombatReach.queries = 0;
            SilentSlotManager.getInstance().active = false;
            SilentSlotManager.getInstance().owner = null;
            RotationManager.getInstance().owner = owner;
            client.world = new ClientWorld();
            replacePlayer();
            target = newTarget(7);
            tick(100);
            client.interactionManager.attack = (attacker, victim) -> {
                check(attacker == client.player && victim == target, "every click uses same current player and exact selected target");
                check(expectedAttackTick == ActionScheduler.getInstance().tickNumber(), "burst remains on same scheduler tick");
                equal(1, CombatLoadout.scopeDepth, "attack is inside exactly one slot scope");
                equal(2, CombatLoadout.selectedSlot, "attack uses selected disabling axe slot");
                check(VanillaCombatReach.queriedTarget == target && VanillaCombatReach.queriedSlot == 2,
                    "fresh reach guard used exact target and chosen axe slot");
                hits.add(victim);
                CombatLoadout.events.add("attack:" + CombatLoadout.selectedSlot);
                if (hits.size() == 1) afterFirst.run();
            };
        }

        LivingEntity newTarget(int id) {
            LivingEntity entity = new LivingEntity();
            entity.id = id; entity.world = client.world; entity.headYaw = 137;
            entity.heldShield = new ItemStack();
            entity.heldShield.set(DataComponentTypes.BLOCKS_ATTACKS, block);
            entity.blockingItem = entity.heldShield;
            return entity;
        }

        void replacePlayer() {
            client.player = new ClientPlayerEntity();
            client.player.world = client.world;
            client.player.position = new Vec3d(0, 0, 2);
            client.player.inventory.stacks[2] = axe(5);
        }

        static ItemStack axe(float seconds) {
            ItemStack stack = new ItemStack();
            stack.axe = true;
            stack.set(DataComponentTypes.WEAPON, new WeaponComponent(seconds));
            return stack;
        }

        void tick(long tick) { ActionScheduler.getInstance().tick = tick; }
        void arm() { check(support.ready(context, target), "fixture arms eligible target"); }
        boolean attempt(boolean silent) {
            expectedAttackTick = ActionScheduler.getInstance().tickNumber();
            return support.attempt(owner, context, target, 70, silent);
        }
    }

    private static final class Samples implements DoubleSupplier {
        private final double[] values;
        int used;
        Samples(double... values) { this.values = values; }
        @Override public double getAsDouble() {
            if (used >= values.length) throw new AssertionError("Unexpected extra RNG sample");
            return values[used++];
        }
    }

    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
    private static void equal(int expected, int actual, String reason) {
        check(expected == actual, reason + ": expected " + expected + ", got " + actual);
    }
    private static void close(double expected, double actual, String reason) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) < 0.000001, reason + ": " + actual);
    }
}
