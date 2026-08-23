package dev.c4lam.celeste.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;

/**
 * Presentation-only target state for the HUD. Entity ids are resolved against
 * the current client world every frame, so no entity survives a world change.
 */
public final class HudTargetTracker {
    private static final long ATTACK_HOLD_NANOS = 1_800_000_000L;
    private static final long EXIT_CACHE_NANOS = 420_000_000L;
    private static final HudTargetTracker INSTANCE = new HudTargetTracker();

    private Object worldIdentity;
    private int displayedId = -1;
    private int attackedId = -1;
    private long attackedUntil;
    private long cachedUntil;
    private long lastUpdate;
    private float visibility;
    private float contentProgress;
    private float renderedHealth;
    private float renderedAbsorption;
    private float previousRawHealth;
    private float hurtPulse;
    private boolean valuesInitialized;

    private HudTargetTracker() {
    }

    public static HudTargetTracker getInstance() {
        return INSTANCE;
    }

    /** Records an actual vanilla entity attack without retaining the entity. */
    public void markAttacked(MinecraftClient client, Entity entity) {
        if (!(entity instanceof LivingEntity living) || !valid(client, living)) {
            return;
        }
        resetForWorld(client);
        long now = System.nanoTime();
        attackedId = entity.getId();
        attackedUntil = now + ATTACK_HOLD_NANOS;
        cachedUntil = now + EXIT_CACHE_NANOS;
    }

    /** Returns only the active attacked/crosshair target; never invents a nearest target. */
    public LivingEntity activeTarget(MinecraftClient client) {
        resetForWorld(client);
        return directTarget(client, System.nanoTime());
    }

    /** Advances frame-rate-independent motion and returns this frame's target snapshot. */
    public Presentation presentation(MinecraftClient client, boolean editor) {
        resetForWorld(client);
        float seconds = frameSeconds();
        long now = System.nanoTime();
        LivingEntity target = directTarget(client, now);
        boolean direct = target != null;

        if (direct) {
            int nextId = target.getId();
            if (displayedId != nextId) {
                displayedId = nextId;
                valuesInitialized = false;
                contentProgress = 0.0f;
                hurtPulse = 0.0f;
            }
            cachedUntil = now + EXIT_CACHE_NANOS;
        } else if (displayedId >= 0 && now <= cachedUntil) {
            target = resolve(client, displayedId);
        }

        visibility = damp(visibility, direct ? 1.0f : 0.0f, direct ? 17.0f : 22.0f, seconds);
        contentProgress = damp(contentProgress, target == null ? 0.0f : 1.0f, 19.0f, seconds);
        hurtPulse = damp(hurtPulse, 0.0f, 10.0f, seconds);

        if (target == null) {
            if (now > cachedUntil && visibility < 0.01f) {
                displayedId = -1;
                valuesInitialized = false;
            }
            return editor ? Presentation.editorPreview() : null;
        }

        float rawHealth = Math.max(0.0f, target.getHealth());
        float rawAbsorption = Math.max(0.0f, target.getAbsorptionAmount());
        if (!valuesInitialized) {
            renderedHealth = rawHealth;
            renderedAbsorption = rawAbsorption;
            previousRawHealth = rawHealth;
            valuesInitialized = true;
        } else {
            if (rawHealth + 0.01f < previousRawHealth) {
                hurtPulse = 1.0f;
            }
            renderedHealth = damp(renderedHealth, rawHealth, 11.0f, seconds);
            renderedAbsorption = damp(renderedAbsorption, rawAbsorption, 13.0f, seconds);
            previousRawHealth = rawHealth;
        }

        float distance = client.player == null ? 0.0f : client.player.distanceTo(target);
        return new Presentation(
            target,
            target.getName().getString(),
            renderedHealth,
            Math.max(1.0f, target.getMaxHealth()),
            renderedAbsorption,
            distance,
            editor ? 1.0f : visibility,
            contentProgress,
            hurtPulse,
            false
        );
    }

    private LivingEntity directTarget(MinecraftClient client, long now) {
        if (now <= attackedUntil) {
            LivingEntity attacked = resolve(client, attackedId);
            if (attacked != null) {
                return attacked;
            }
        } else {
            attackedId = -1;
        }

        if (client != null && client.crosshairTarget instanceof EntityHitResult hit
            && hit.getEntity() instanceof LivingEntity living && valid(client, living)) {
            return living;
        }
        return null;
    }

    private LivingEntity resolve(MinecraftClient client, int entityId) {
        if (client == null || client.world == null || entityId < 0) {
            return null;
        }
        Entity entity = client.world.getEntityById(entityId);
        return entity instanceof LivingEntity living && valid(client, living) ? living : null;
    }

    private static boolean valid(MinecraftClient client, LivingEntity living) {
        return client != null && client.player != null && client.world != null
            && living != client.player && !living.isRemoved() && living.isAlive();
    }

    private void resetForWorld(MinecraftClient client) {
        Object currentWorld = client == null ? null : client.world;
        if (currentWorld == worldIdentity) {
            return;
        }
        worldIdentity = currentWorld;
        displayedId = -1;
        attackedId = -1;
        attackedUntil = 0L;
        cachedUntil = 0L;
        lastUpdate = 0L;
        visibility = 0.0f;
        contentProgress = 0.0f;
        renderedHealth = 0.0f;
        renderedAbsorption = 0.0f;
        previousRawHealth = 0.0f;
        hurtPulse = 0.0f;
        valuesInitialized = false;
    }

    private float frameSeconds() {
        long now = System.nanoTime();
        float seconds = lastUpdate == 0L
            ? 1.0f / 60.0f
            : Math.min(0.05f, Math.max(0.0f, (now - lastUpdate) / 1_000_000_000.0f));
        lastUpdate = now;
        return seconds;
    }

    private static float damp(float current, float target, float speed, float seconds) {
        return current + (target - current) * (1.0f - (float) Math.exp(-speed * seconds));
    }

    public record Presentation(
        LivingEntity entity,
        String name,
        float health,
        float maxHealth,
        float absorption,
        float distance,
        float visibility,
        float contentProgress,
        float hurtPulse,
        boolean preview
    ) {
        private static Presentation editorPreview() {
            return new Presentation(null, "Celeste Target", 20.0f, 20.0f, 0.0f,
                3.2f, 1.0f, 1.0f, 0.0f, true);
        }
    }
}
