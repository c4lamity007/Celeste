package dev.c4lam.celeste.hud;

import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.module.expansion.inventory.SilentInventoryScreen;
import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.license.LicenseIdentity;
import dev.c4lam.celeste.media.MediaPlaybackSnapshot;
import dev.c4lam.celeste.media.MediaArtwork;
import dev.c4lam.celeste.media.SystemMediaService;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.GuiTextures;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;

import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;
import org.joml.Vector4f;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Owns HUD widget state and supplies both editor previews and the live overlay. */
public final class HudManager {
    private static final Identifier LAYER_ID = Identifier.of(CelesteClient.MOD_ID, "hud_elements");
    private static final Identifier MEDIA_PREVIOUS_ICON = Identifier.of(CelesteClient.MOD_ID,
        "textures/gui/icons/media_previous.png");
    private static final Identifier MEDIA_PLAY_ICON = Identifier.of(CelesteClient.MOD_ID,
        "textures/gui/icons/media_play.png");
    private static final Identifier MEDIA_PAUSE_ICON = Identifier.of(CelesteClient.MOD_ID,
        "textures/gui/icons/media_pause.png");
    private static final Identifier MEDIA_NEXT_ICON = Identifier.of(CelesteClient.MOD_ID,
        "textures/gui/icons/media_next.png");
    private static final Identifier MEDIA_SHUFFLE_ICON = Identifier.of(CelesteClient.MOD_ID,
        "textures/gui/icons/media_shuffle.png");
    private static final float[] MEDIA_BUTTON_CENTERS = {58.0f, 95.0f, 132.0f, 164.0f};
    private static final HudManager INSTANCE = new HudManager();
    private static final Color TEXT = color(250, 244, 250, 255);
    private static final Color MUTED = color(173, 158, 174, 255);
    private static final Color ACCENT = color(255, 43, 143, 255);
    private static final Color BACKGROUND_TOP = color(31, 18, 31, 244);
    private static final Color BACKGROUND_BOTTOM = color(14, 10, 17, 249);
    private static final Color BACKGROUND_HOVER_TOP = color(46, 27, 44, 246);
    private static final Color BACKGROUND_HOVER_BOTTOM = color(22, 15, 24, 250);

    private final List<HudElementState> elements = new ArrayList<>();
    private final HudTargetTracker targetTracker = HudTargetTracker.getInstance();
    private final Path layoutFile = FabricLoader.getInstance().getConfigDir().resolve("celeste").resolve("hud-layout.properties");
    private final float[] mediaButtonHover = new float[4];
    private float renderedMediaProgress;
    private String renderedMediaTimelineKey = "";
    private float renderedMediaArtworkOpacity;
    private float renderedMediaPlaying;
    private float renderedMediaShuffle;
    private long lastPresentationUpdate;
    private boolean initialized;
    private TargetPlacement targetPlacement = TargetPlacement.HUD;
    // HUD selection state
    private boolean isSelecting;
    private float selectionStartX;
    private float selectionStartY;
    private float selectionEndX;
    private float selectionEndY;
    private final List<HudElementState> selectedElements = new ArrayList<>();

      private HudManager() {
          elements.add(new HudElementState(HudElementType.WATERMARK, 8, 8, true, false));
          elements.add(new HudElementState(HudElementType.COORDINATES, 8, 44, true, false));
          elements.add(new HudElementState(HudElementType.FPS, 8, 80, false, false));
          elements.add(new HudElementState(HudElementType.TPS, 80, 80, false, false));
          elements.add(new HudElementState(HudElementType.ARRAYLIST, 8, 113, false, false));
          elements.add(new HudElementState(HudElementType.TARGET_HUD, 218, 134, false, false));
          elements.add(new HudElementState(HudElementType.EFFECTS, 8, 182, false, false));
          elements.add(new HudElementState(HudElementType.ARMOR, 218, 188, false, false));
          elements.add(new HudElementState(HudElementType.KEYBINDS, 8, 238, false, false));
          elements.add(new HudElementState(HudElementType.COOLDOWNS, 218, 232, false, false));
          elements.add(new HudElementState(HudElementType.SPOTIFY, 218, 278, false, false));
         isSelecting = false;
     }

    public static HudManager getInstance() {
        return INSTANCE;
    }

    /** Call during client initialization. Repeated calls are harmless. */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        loadLayout();
        initialized = true;
        HudElementRegistry.addLast(LAYER_ID, (context, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen == null || client.currentScreen instanceof SilentInventoryScreen) {
                render(context, -1, -1, false, null, false);
            } else if (client.currentScreen instanceof ChatScreen) {
                int mouseX = Math.round((float) (client.mouse.getX()
                    * client.getWindow().getScaledWidth() / Math.max(1, client.getWindow().getWidth())));
                int mouseY = Math.round((float) (client.mouse.getY()
                    * client.getWindow().getScaledHeight() / Math.max(1, client.getWindow().getHeight())));
                render(context, mouseX, mouseY, false, null, true);
            }
        });
    }

    public List<HudElementState> elements() {
        return List.copyOf(elements);
    }

    public HudElementState state(HudElementType type) {
        return elements.stream().filter(element -> element.type() == type).findFirst().orElseThrow();
    }

    public TargetPlacement targetPlacement() {
        return targetPlacement;
    }

    public void setTargetPlacement(TargetPlacement placement) {
        TargetPlacement safePlacement = placement == null ? TargetPlacement.HUD : placement;
        if (targetPlacement == safePlacement) {
            return;
        }
        targetPlacement = safePlacement;
        saveLayout();
    }

    public void toggle(HudElementType type) {
        HudElementState state = state(type);
        boolean enabled = !state.enabled();
        state.setEnabled(enabled);
        if (!enabled) {
            state.setSelected(false);
        }
        GuiModule module = moduleFor(type);
        if (module != null) {
            module.setEnabled(enabled);
        }
        saveLayout();
    }

    /** Makes the module switch authoritative for the three module-backed widgets. */
    public void synchronizeModuleBackedElements() {
        for (HudElementType type : List.of(
            HudElementType.FPS, HudElementType.WATERMARK, HudElementType.ARRAYLIST)) {
            GuiModule module = moduleFor(type);
            if (module != null) {
                state(type).setEnabled(module.enabled());
            }
        }
        saveLayout();
    }

    private static GuiModule moduleFor(HudElementType type) {
        String name = switch (type) {
            case FPS -> "FPS Counter";
            case WATERMARK -> "Watermark";
            case ARRAYLIST -> "Array List";
            default -> null;
        };
        if (name == null) {
            return null;
        }
        return GuiCatalog.modules().stream()
            .filter(module -> module.name().equals(name))
            .findFirst().orElse(null);
    }

    /** Restarts the restrained entrance used when opening the editor. */
    public void beginEditorSession() {
        for (HudElementState state : elements) {
            state.restartEntrance();
        }
        Arrays.fill(mediaButtonHover, 0.0f);
        renderedMediaArtworkOpacity = 0.0f;
        renderedMediaPlaying = 0.0f;
        renderedMediaShuffle = 0.0f;
        lastPresentationUpdate = 0L;
    }

    public void releaseBlurCapture() {
        RenderUtil.releaseBlur();
    }

    public synchronized Properties exportLayout() {
        Properties values = new Properties();
        for (HudElementState state : elements) {
            String prefix = "hud." + state.type().name().toLowerCase(Locale.ROOT) + ".";
            values.setProperty(prefix + "enabled", Boolean.toString(state.enabled()));
            values.setProperty(prefix + "x", Float.toString(state.x()));
            values.setProperty(prefix + "y", Float.toString(state.y()));
            values.setProperty(prefix + "scale", Float.toString(state.scale()));
        }
        values.setProperty("hud.target_hud.placement", targetPlacement.name());
        return values;
    }

    public synchronized void importLayout(Properties values) {
        if (values == null) {
            return;
        }
        applyLayout(values);
        saveLayout();
    }

    public synchronized void saveLayout() {
        try {
            Files.createDirectories(layoutFile.getParent());
            try (OutputStream output = Files.newOutputStream(layoutFile)) {
                Properties stored = new Properties();
                for (HudElementState state : elements) {
                    String prefix = state.type().name().toLowerCase(Locale.ROOT) + ".";
                    stored.setProperty(prefix + "enabled", Boolean.toString(state.enabled()));
                    stored.setProperty(prefix + "x", Float.toString(state.x()));
                    stored.setProperty(prefix + "y", Float.toString(state.y()));
                    stored.setProperty(prefix + "scale", Float.toString(state.scale()));
                }
                stored.setProperty("target_hud.placement", targetPlacement.name());
                stored.store(output, "Celeste HUD layout");
            }
        } catch (IOException ignored) {
        }
    }

    private void loadLayout() {
        if (!Files.exists(layoutFile)) {
            return;
        }
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(layoutFile)) {
            values.load(input);
            applyLayout(values);
        } catch (IOException ignored) {
        }
    }

    private void applyLayout(Properties values) {
        targetPlacement = TargetPlacement.parse(
            values.getProperty("hud.target_hud.placement", values.getProperty("target_hud.placement")),
            TargetPlacement.HUD);
        for (HudElementState state : elements) {
            String prefixed = "hud." + state.type().name().toLowerCase(Locale.ROOT) + ".";
            String legacy = state.type().name().toLowerCase(Locale.ROOT) + ".";
            String prefix = values.getProperty(prefixed + "enabled") != null ? prefixed : legacy;
            state.setEnabled(Boolean.parseBoolean(values.getProperty(prefix + "enabled", Boolean.toString(state.enabled()))));
            float x = parseFloat(values.getProperty(prefix + "x"), state.x());
            float y = parseFloat(values.getProperty(prefix + "y"), state.y());
            state.setScale(parseFloat(values.getProperty(prefix + "scale"), 1.0f));
            state.moveImmediately(x, y);
        }
    }

    public HudElementState hit(double mouseX, double mouseY) {
        for (int index = elements.size() - 1; index >= 0; index--) {
            HudElementState state = elements.get(index);
            if (state.enabled() && state.contains(mouseX, mouseY)) {
                return state;
            }
        }
        return null;
    }

    /** Runs a media transport action when a visible control is clicked. */
    public boolean handleMediaClick(double mouseX, double mouseY) {
        HudElementState media = state(HudElementType.SPOTIFY);
        if (!media.enabled() || !media.contains(mouseX, mouseY)) {
            return false;
        }
        float localX = ((float) mouseX - media.displayX()) / media.scale();
        float localY = ((float) mouseY - media.displayY()) / media.scale();
        if (localY < 57.0f || localY > 77.0f) {
            return false;
        }

        int button = -1;
        for (int index = 0; index < MEDIA_BUTTON_CENTERS.length; index++) {
            if (Math.abs(localX - MEDIA_BUTTON_CENTERS[index]) <= 14.0f) {
                button = index;
                break;
            }
        }
        if (button < 0) {
            return false;
        }

        SystemMediaService service = SystemMediaService.getInstance();
        MediaPlaybackSnapshot snapshot = service.snapshot();
        switch (button) {
            case 0 -> {
                if (snapshot.canPrevious()) service.previous();
            }
            case 1 -> {
                if (snapshot.canToggle()) service.togglePlayback();
            }
            case 2 -> {
                if (snapshot.canNext()) service.next();
            }
            case 3 -> {
                if (snapshot.canShuffle()) service.toggleShuffle();
            }
            default -> {
            }
        }
        return true;
    }

    /** Keeps persisted absolute positions reachable after GUI-scale or window-size changes. */
    public boolean clampToViewport(int viewportWidth, int viewportHeight) {
        boolean changed = false;
        for (HudElementState state : elements) {
            float fittingScale = Math.min(
                viewportWidth / Math.max(1.0f, state.type().width()),
                viewportHeight / Math.max(1.0f, state.type().height())
            );
            float safeScale = Math.max(HudElementState.MIN_SCALE,
                Math.min(state.scale(), fittingScale));
            if (safeScale != state.scale()) {
                state.setScale(safeScale);
                changed = true;
            }
            float maximumX = Math.max(0.0f, viewportWidth - state.width());
            float maximumY = Math.max(0.0f, viewportHeight - state.height());
            float safeX = Float.isFinite(state.x()) ? Math.max(0.0f, Math.min(maximumX, state.x())) : 0.0f;
            float safeY = Float.isFinite(state.y()) ? Math.max(0.0f, Math.min(maximumY, state.y())) : 0.0f;
            if (safeX != state.x() || safeY != state.y()) {
                state.moveTo(safeX, safeY);
                changed = true;
            }
        }
        return changed;
    }

    public void renderEditor(DrawContext context, int mouseX, int mouseY, HudElementState dragging) {
        render(context, mouseX, mouseY, true, dragging, false);
    }

    private void render(DrawContext context, int mouseX, int mouseY, boolean editor, HudElementState dragging,
                        boolean chatMediaMode) {
        if (clampToViewport(context.getScaledWindowWidth(), context.getScaledWindowHeight())) {
            // A GUI-scale/window change may otherwise leave the persisted copy
            // offscreen again on the next launch. This branch runs only when a
            // position actually changes, never once per frame.
            saveLayout();
        }
        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        GuiRenderer.getInstance().clearScissors();

        for (HudElementState state : elements) {
            boolean presentationVisible = !chatMediaMode || state.type() == HudElementType.SPOTIFY;
            boolean interactive = editor || (chatMediaMode && state.type() == HudElementType.SPOTIFY);
            boolean hovered = interactive && state.enabled() && state.contains(mouseX, mouseY);
            state.updateVisuals(hovered, state == dragging, presentationVisible);
        }

        HudElementState targetState = state(HudElementType.TARGET_HUD);
        boolean targetPresentationVisible = !chatMediaMode;
        HudTargetTracker.Presentation targetPresentation = targetState.visuallyPresent(targetPresentationVisible)
            ? targetTracker.presentation(MinecraftClient.getInstance(), editor)
            : null;
        boolean targetAboveEntity = !editor && !chatMediaMode
            && targetPlacement == TargetPlacement.ABOVE_TARGET;
        ProjectedTarget projectedTarget = targetAboveEntity
            ? projectTarget(context, targetPresentation)
            : null;

        // One explicit world capture is shared by every HUD surface this frame.
        // Do not run the full-screen blur chain for an enabled Target HUD that
        // currently has no target and therefore draws no panel.
        boolean hasVisibleSurface = editor || elements.stream().anyMatch(state -> {
            boolean presentationVisible = !chatMediaMode || state.type() == HudElementType.SPOTIFY;
            return state.visuallyPresent(presentationVisible)
                && (state.type() != HudElementType.TARGET_HUD
                    || (targetAboveEntity ? projectedTarget != null : targetPresentation != null));
        });
        if (hasVisibleSurface) {
            RenderUtil.captureBlur();
        }
        for (HudElementState state : elements) {
            boolean presentationVisible = !chatMediaMode || state.type() == HudElementType.SPOTIFY;
            boolean interactive = editor || (chatMediaMode && state.type() == HudElementType.SPOTIFY);
            boolean hovered = interactive && state.enabled() && state.contains(mouseX, mouseY);
            if (!state.visuallyPresent(presentationVisible)) {
                continue;
            }
            if (targetAboveEntity && state.type() == HudElementType.TARGET_HUD) {
                continue;
            }
            drawWidget(context, matrices, state, hovered, state == dragging, mouseX, mouseY, editor,
                interactive, targetPresentation);
        }
        if (projectedTarget != null) {
            drawProjectedTarget(matrices, targetState, targetPresentation, projectedTarget);
        }
    }

    private ProjectedTarget projectTarget(DrawContext context, HudTargetTracker.Presentation presentation) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (presentation == null || presentation.entity() == null || client.world == null
            || client.player == null || !client.gameRenderer.getCamera().isReady()) {
            return null;
        }

        var camera = client.gameRenderer.getCamera();
        float tickProgress = camera.getLastTickProgress();
        Vec3d targetBase = presentation.entity().getLerpedPos(tickProgress);
        Vec3d headAnchor = targetBase.add(0.0D, presentation.entity().getHeight() + 0.42D, 0.0D);
        Vec3d cameraPos = camera.getCameraPos();
        Vec3d relative = headAnchor.subtract(cameraPos);
        var forward = camera.getHorizontalPlane();
        double cameraDepth = relative.x * forward.x() + relative.y * forward.y() + relative.z * forward.z();
        if (cameraDepth <= 0.05D || occluded(client, cameraPos, headAnchor)) {
            return null;
        }

        Vec3d projected = client.gameRenderer.project(headAnchor);
        if (!Double.isFinite(projected.x) || !Double.isFinite(projected.y) || !Double.isFinite(projected.z)
            || projected.z < -1.01D || projected.z > 1.01D) {
            return null;
        }

        float viewportWidth = context.getScaledWindowWidth();
        float viewportHeight = context.getScaledWindowHeight();
        float screenX = (float) ((projected.x + 1.0D) * 0.5D * viewportWidth);
        float screenY = (float) ((1.0D - projected.y) * 0.5D * viewportHeight);
        HudElementState state = state(HudElementType.TARGET_HUD);
        float cardWidth = state.width();
        float cardHeight = state.height();
        float cardX = screenX - cardWidth * 0.5F;
        float cardY = screenY - cardHeight - 4.0F * state.scale();
        if (cardX + cardWidth <= 0.0F || cardX >= viewportWidth
            || cardY + cardHeight <= 0.0F || cardY >= viewportHeight) {
            return null;
        }
        return new ProjectedTarget(cardX, cardY);
    }

    private static boolean occluded(MinecraftClient client, Vec3d cameraPos, Vec3d targetPos) {
        HitResult hit = client.world.raycast(new RaycastContext(
            cameraPos,
            targetPos,
            RaycastContext.ShapeType.VISUAL,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        return hit.getType() != HitResult.Type.MISS
            && hit.getPos().squaredDistanceTo(cameraPos) + 1.0E-4D
                < targetPos.squaredDistanceTo(cameraPos);
    }

    private void drawProjectedTarget(MatrixStack matrices, HudElementState state,
                                     HudTargetTracker.Presentation presentation,
                                     ProjectedTarget projected) {
        float visibility = state.visibility() * presentation.visibility();
        if (visibility <= 0.003F) {
            return;
        }

        float x = projected.x();
        float y = projected.y() + (1.0F - presentation.visibility()) * 3.0F;
        float elementScale = state.scale();
        float width = state.width();
        float height = state.height();
        float baseWidth = state.type().width();
        float baseHeight = state.type().height();
        float motionScale = (0.965F + state.visibility() * 0.035F)
            * (0.96F + presentation.visibility() * 0.04F);
        float centerX = x + width * 0.5F;
        float centerY = y + height * 0.5F;

        matrices.push();
        matrices.translate(centerX, centerY, 0.0F);
        matrices.scale(motionScale, motionScale, 1.0F);
        matrices.translate(-centerX, -centerY, 0.0F);
        matrices.translate(x, y, 0.0F);
        matrices.scale(elementScale, elementScale, 1.0F);
        matrices.translate(-x, -y, 0.0F);
        RenderUtil.BLUR_RECT.draw(matrices, x, y, baseWidth, baseHeight, new Vector4f(9),
            withAlpha(BACKGROUND_TOP, visibility), withAlpha(BACKGROUND_TOP, visibility),
            withAlpha(BACKGROUND_BOTTOM, visibility), withAlpha(BACKGROUND_BOTTOM, visibility),
            InterfaceConfig.glassTintMix());
        drawTarget(matrices, x, y, baseWidth, visibility, presentation);
        matrices.pop();
    }

    private void drawWidget(DrawContext context, MatrixStack matrices, HudElementState state, boolean hovered,
                            boolean dragging, int mouseX, int mouseY, boolean editor, boolean interactive,
                            HudTargetTracker.Presentation frameTarget) {
        float visibility = state.visibility();
        HudTargetTracker.Presentation targetPresentation = null;
        float targetVisibility = 1.0f;
        if (state.type() == HudElementType.TARGET_HUD) {
            targetPresentation = frameTarget;
            if (targetPresentation == null) {
                return;
            }
            targetVisibility = editor ? 1.0f : targetPresentation.visibility();
            visibility *= targetVisibility;
            if (visibility <= 0.003f) {
                return;
            }
        }
        float x = state.displayX();
        float y = state.displayY() + (1.0f - state.visibility()) * 4.0f
            + (state.type() == HudElementType.TARGET_HUD ? (1.0f - targetVisibility) * 3.0f : 0.0f);
        float elementScale = state.scale();
        float width = state.width();
        float height = state.height();
        float baseWidth = state.type().width();
        float baseHeight = state.type().height();
        float hover = state.hoverProgress();
        float drag = state.dragProgress();
        float selection = editor ? state.selectionProgress() : 0.0f;
        float motionScale = (0.965f + state.visibility() * 0.035f)
            * (state.type() == HudElementType.TARGET_HUD ? 0.96f + targetVisibility * 0.04f : 1.0f)
            * (1.0f + hover * 0.012f + drag * 0.012f);
        float centerX = x + width / 2.0f;
        float centerY = y + height / 2.0f;

        matrices.push();
        matrices.translate(centerX, centerY, 0.0f);
        matrices.scale(motionScale, motionScale, 1.0f);
        matrices.translate(-centerX, -centerY, 0.0f);
        matrices.translate(x, y, 0.0f);
        matrices.scale(elementScale, elementScale, 1.0f);
        matrices.translate(-x, -y, 0.0f);

        Color top = withAlpha(blend(BACKGROUND_TOP, BACKGROUND_HOVER_TOP, hover), visibility);
        Color bottom = withAlpha(blend(BACKGROUND_BOTTOM, BACKGROUND_HOVER_BOTTOM, hover), visibility);
        if (state.type() != HudElementType.ARRAYLIST) {
            RenderUtil.BLUR_RECT.draw(matrices, x, y, baseWidth, baseHeight, new Vector4f(9),
                top, top, bottom, bottom, InterfaceConfig.glassTintMix());
        }
        if (selection > 0.002f) {
            Color selectionTop = withAlpha(color(205, 205, 211, 30), visibility * selection);
            Color selectionBottom = withAlpha(color(92, 92, 100, 42), visibility * selection);
            RenderUtil.BLUR_RECT.draw(matrices, x, y, baseWidth, baseHeight, new Vector4f(9),
                selectionTop, selectionTop, selectionBottom, selectionBottom,
                InterfaceConfig.glassTintMix());
        }
        if (editor && (hover > 0.015f || dragging)) {
            float indicatorWidth = 18.0f + 10.0f * drag;
            RenderUtil.RECT.draw(matrices, centerX - indicatorWidth / 2.0f, y + 2.5f,
                indicatorWidth, 1.5f, 0.75f, withAlpha(ACCENT, visibility * Math.max(hover, drag) * 0.78f));
        }

         switch (state.type()) {
             case WATERMARK -> drawWatermark(matrices, x, y, baseWidth, visibility);
             case COORDINATES -> drawCoordinates(matrices, x, y, baseWidth, visibility);
             case TARGET_HUD -> drawTarget(matrices, x, y, baseWidth, visibility, targetPresentation);
             case EFFECTS -> drawEffects(matrices, x, y, baseWidth, visibility);
             case ARMOR -> {
                 var gui = context.getMatrices();
                 gui.pushMatrix();
                 gui.translate(centerX, centerY);
                 gui.scale(motionScale, motionScale);
                 gui.translate(-centerX, -centerY);
                 gui.translate(x, y);
                 gui.scale(elementScale, elementScale);
                 gui.translate(-x, -y);
                 drawArmor(context, matrices, x, y, visibility);
                 gui.popMatrix();
             }
             case KEYBINDS -> drawKeybinds(matrices, x, y, baseWidth, visibility);
             case COOLDOWNS -> drawCooldowns(matrices, x, y, baseWidth, visibility);
             case ARRAYLIST -> drawArraylist(matrices, x, y, baseWidth, visibility);
             case TPS -> drawMetric(matrices, x, y, baseWidth, "TPS",
                 ServerTickRateTracker.getInstance().formatted(MinecraftClient.getInstance().world), visibility);
             case FPS -> drawMetric(matrices, x, y, baseWidth, "FPS", String.valueOf(MinecraftClient.getInstance().getCurrentFps()), visibility);
             case SPOTIFY -> drawMedia(matrices, x, y, baseWidth, visibility,
                 Math.round(x + (mouseX - x) / elementScale),
                 Math.round(y + (mouseY - y) / elementScale), interactive);
         }
        if (selection > 0.002f) {
            RenderUtil.SELECTION_OUTLINE.draw(matrices, x, y, baseWidth, baseHeight,
                9.0f, 1.2f, selectionPhase(), visibility * selection);
        }
        matrices.pop();
    }

    private void drawWatermark(MatrixStack matrices, float x, float y, float width, float opacity) {
        Fonts.semibold().drawCenteredText(matrices, "Celeste Client", x + width / 2.0f, y + 5, 8.0f, withAlpha(TEXT, opacity));
        Fonts.regular().drawCenteredText(matrices, "UID " + LicenseIdentity.uid(),
            x + width / 2.0f, y + 18, 5.6f, withAlpha(MUTED, opacity));
    }

    private void drawCoordinates(MatrixStack matrices, float x, float y, float width, float opacity) {
        MinecraftClient client = MinecraftClient.getInstance();
        String value = client.player == null ? "0, 0, 0" : String.format(Locale.ROOT, "%d, %d, %d",
            (int) Math.floor(client.player.getX()), (int) Math.floor(client.player.getY()), (int) Math.floor(client.player.getZ()));
        Fonts.regular().drawCenteredText(matrices, "XYZ", x + width / 2.0f, y + 4, 5.6f, withAlpha(MUTED, opacity));
        Fonts.semibold().drawCenteredText(matrices, value, x + width / 2.0f, y + 15, 7.6f, withAlpha(TEXT, opacity));
    }

    private void drawTarget(MatrixStack matrices, float x, float y, float width, float opacity,
                            HudTargetTracker.Presentation presentation) {
        float content = presentation.contentProgress();
        float headScale = 0.90f + content * 0.10f + presentation.hurtPulse() * 0.025f;
        float headX = x + 7.0f;
        float headY = y + 9.0f;
        float headSize = 26.0f;
        float headCenterX = headX + headSize * 0.5f;
        float headCenterY = headY + headSize * 0.5f;
        Color contentText = withAlpha(TEXT, opacity * content);
        Color contentMuted = withAlpha(MUTED, opacity * content);

        matrices.push();
        matrices.translate(headCenterX, headCenterY, 0.0f);
        matrices.scale(headScale, headScale, 1.0f);
        matrices.translate(-headCenterX, -headCenterY, 0.0f);
        RenderUtil.RECT.draw(matrices, headX, headY, headSize, headSize, 6.0f,
            color(53, 30, 50, Math.round(224 * opacity * content)));
        if (presentation.entity() instanceof AbstractClientPlayerEntity player) {
            Identifier skin = player.getSkin().body().texturePath();
            RenderUtil.TEXTURE_RECT.draw(matrices, headX + 1, headY + 1, 24, 24, new Vector4f(5),
                withAlpha(Color.WHITE, opacity * content), 8.0f / 64.0f, 8.0f / 64.0f,
                8.0f / 64.0f, 8.0f / 64.0f, GuiTextures.view(skin), GuiTextures.nearest(), true);
            RenderUtil.TEXTURE_RECT.draw(matrices, headX + 1, headY + 1, 24, 24, new Vector4f(5),
                withAlpha(Color.WHITE, opacity * content), 40.0f / 64.0f, 8.0f / 64.0f,
                8.0f / 64.0f, 8.0f / 64.0f, GuiTextures.view(skin), GuiTextures.nearest(), true);
        } else {
            String initial = presentation.name().isEmpty()
                ? "?" : presentation.name().substring(0, 1).toUpperCase(Locale.ROOT);
            float initialY = headY + (headSize - Fonts.bold().height(9.0f)) * 0.5f;
            Fonts.bold().drawCenteredText(matrices, initial, headCenterX, initialY, 9.0f,
                withAlpha(ACCENT, opacity * content));
        }
        matrices.pop();

        float textX = x + 40.0f;
        float right = x + width - 8.0f;
        String healthText = formatHealth(presentation.health());
        float healthTextWidth = Fonts.bold().width(healthText, 6.4f);
        float availableNameWidth = Math.max(18.0f, right - healthTextWidth - 4.0f - textX);
        String name = trimToWidth(presentation.name(), availableNameWidth, 7.2f);
        Fonts.semibold().draw(matrices, name, textX, y + 7.0f, 7.2f, contentText);
        Fonts.bold().draw(matrices, healthText, right - healthTextWidth, y + 7.5f, 6.4f,
            withAlpha(ACCENT, opacity * content));

        String detail = presentation.preview()
            ? "Live combat preview"
            : String.format(Locale.ROOT, "%.1fm away", presentation.distance());
        Fonts.regular().draw(matrices, detail, textX, y + 20.0f, 5.6f, contentMuted);

        float trackY = y + 32.0f;
        float trackWidth = Math.max(1.0f, right - textX);
        RenderUtil.RECT.draw(matrices, textX, trackY, trackWidth, 3.0f, 1.5f,
            color(61, 48, 62, Math.round(205 * opacity * content)));
        float healthRatio = Math.max(0.0f, Math.min(1.0f, presentation.health() / presentation.maxHealth()));
        float healthWidth = trackWidth * healthRatio;
        Color healthColor = blend(ACCENT, Color.WHITE, presentation.hurtPulse() * 0.22f);
        if (healthWidth > 0.01f) {
            RenderUtil.RECT.draw(matrices, textX, trackY, healthWidth, 3.0f, 1.5f,
                withAlpha(healthColor, opacity * content));
        }
        float absorptionRatio = Math.max(0.0f,
            Math.min(1.0f - healthRatio, presentation.absorption() / presentation.maxHealth()));
        float absorptionWidth = trackWidth * absorptionRatio;
        if (absorptionWidth > 0.01f) {
            RenderUtil.RECT.draw(matrices, textX + healthWidth, trackY, absorptionWidth, 3.0f, 1.5f,
                color(255, 220, 81, Math.round(255 * opacity * content)));
        }
    }

    private static String trimToWidth(String value, float maximumWidth, float size) {
        String text = value == null ? "" : value;
        if (Fonts.semibold().width(text, size) <= maximumWidth) {
            return text;
        }
        String suffix = "...";
        while (!text.isEmpty() && Fonts.semibold().width(text + suffix, size) > maximumWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + suffix;
    }

    private static String formatHealth(float value) {
        return Math.abs(value - Math.round(value)) < 0.05f
            ? Integer.toString(Math.round(value))
            : String.format(Locale.ROOT, "%.1f", value);
    }

    private void drawEffects(MatrixStack matrices, float x, float y, float width, float opacity) {
        MinecraftClient client = MinecraftClient.getInstance();
        Fonts.semibold().drawCenteredText(matrices, "Effects", x + width / 2.0f, y + 6, 7.4f, withAlpha(TEXT, opacity));
        String value = "No active effects";
        if (client.player != null && !client.player.getStatusEffects().isEmpty()) {
            value = client.player.getStatusEffects().stream().limit(2).map(this::effectName).reduce((a, b) -> a + ", " + b).orElse(value);
        }
        Fonts.regular().drawCenteredText(matrices, trim(value, 29), x + width / 2.0f, y + 24, 6.2f, withAlpha(MUTED, opacity));
    }

    private String effectName(StatusEffectInstance effect) {
        return effect.getEffectType().value().getName().getString();
    }

    private void drawArmor(DrawContext context, MatrixStack matrices, float x, float y, float opacity) {
        MinecraftClient client = MinecraftClient.getInstance();
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        float itemSize = 18.0f;
        GuiRenderer.getInstance().flush();
        for (int index = 0; index < slots.length; index++) {
            float itemX = x + 10 + index * 34;
            float itemY = y + (34.0f - itemSize) * 0.5f;
            ItemStack stack = client.player == null ? ItemStack.EMPTY : client.player.getEquippedStack(slots[index]);
            if (stack.isEmpty()) {
                RenderUtil.RECT.draw(matrices, itemX, itemY, itemSize, itemSize, 4,
                    color(255, 255, 255, Math.round(18 * opacity)));
                continue;
            }
            float scale = itemSize / 16.0f;
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(itemX, itemY);
            context.getMatrices().scale(scale, scale);
            context.drawItem(stack, 0, 0);
            context.getMatrices().popMatrix();
            if (!stack.isDamageable()) {
                continue;
            }
            int remaining = Math.max(0, stack.getMaxDamage() - stack.getDamage());
            float ratio = stack.getMaxDamage() <= 0 ? 1.0f : remaining / (float) stack.getMaxDamage();
            Color value = ratio > 0.45f ? TEXT : ratio > 0.2f ? color(255, 196, 92, 255) : color(255, 92, 108, 255);
            float centerX = itemX + itemSize * 0.5f;
            float labelY = itemY + itemSize - 1.0f;
            Fonts.bold().drawCenteredText(matrices, Integer.toString(remaining), centerX + 0.6f, labelY + 0.6f, 6.2f,
                color(0, 0, 0, Math.round(170 * opacity)));
            Fonts.bold().drawCenteredText(matrices, Integer.toString(remaining), centerX, labelY, 6.2f,
                withAlpha(value, opacity));
        }
    }

    private void drawKeybinds(MatrixStack matrices, float x, float y, float width, float opacity) {
        Fonts.semibold().drawCenteredText(matrices, "Keybinds", x + width / 2.0f, y + 6, 7.4f, withAlpha(TEXT, opacity));
        List<String> bound = GuiCatalog.modules().stream()
            .flatMap(module -> module.settings().stream()
                .filter(GuiSetting.Keybind.class::isInstance)
                .map(GuiSetting.Keybind.class::cast)
                .filter(keybind -> keybind.keyCode() != GLFW.GLFW_KEY_UNKNOWN)
                .map(keybind -> module.name() + "  [" + keyName(keybind.keyCode()) + "]"))
            .limit(2)
            .toList();
        if (bound.isEmpty()) {
            Fonts.regular().drawCenteredText(matrices, "No configured binds", x + width / 2.0f, y + 25, 6.2f,
                withAlpha(MUTED, opacity));
            return;
        }
        for (int index = 0; index < bound.size(); index++) {
            Fonts.regular().drawCenteredText(matrices, trim(bound.get(index), 27), x + width / 2.0f,
                y + 21 + index * 11, 6.0f, withAlpha(MUTED, opacity));
        }
    }

    private void drawCooldowns(MatrixStack matrices, float x, float y, float width, float opacity) {
        MinecraftClient client = MinecraftClient.getInstance();
        int attack = client.player == null ? 100 : Math.round(client.player.getAttackCooldownProgress(0) * 100);
        Fonts.semibold().drawCenteredText(matrices, "Attack cooldown  " + attack + "%", x + width / 2.0f,
            y + 7, 6.8f, withAlpha(TEXT, opacity));
        float trackWidth = width - 24;
        RenderUtil.RECT.draw(matrices, x + 12, y + 23, trackWidth, 3, 1.5f,
            color(62, 47, 62, Math.round(230 * opacity)));
        RenderUtil.RECT.draw(matrices, x + 12, y + 23, trackWidth * attack / 100.0f, 3, 1.5f,
            withAlpha(ACCENT, opacity));
    }

    private void drawArraylist(MatrixStack matrices, float x, float y, float width, float opacity) {
        List<String> enabled = GuiCatalog.modules().stream()
            .filter(GuiModule::enabled)
            .map(GuiModule::name)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        float font = 7.2f;
        float padX = 7.0f;
        float rowHeight = 13.0f;
        float right = x + width;
        if (enabled.isEmpty()) {
            String empty = "No modules";
            float rowWidth = Fonts.semibold().width(empty, font) + padX * 2.0f;
            float rowX = right - rowWidth;
            RenderUtil.BLUR_RECT.draw(matrices, rowX, y, rowWidth, rowHeight, 4,
                withAlpha(BACKGROUND_TOP, opacity), InterfaceConfig.glassTintMix());
            Fonts.semibold().drawText(matrices, empty, rowX + padX, y + 2.4f, font, withAlpha(MUTED, opacity));
            return;
        }
        Color top = withAlpha(BACKGROUND_TOP, opacity);
        Color bottom = withAlpha(BACKGROUND_BOTTOM, opacity);
        for (int index = 0; index < enabled.size(); index++) {
            String name = enabled.get(index);
            float textWidth = Fonts.semibold().width(name, font);
            float rowWidth = textWidth + padX * 2.0f + 3.0f;
            float rowX = right - rowWidth;
            float rowY = y + index * rowHeight;
            RenderUtil.BLUR_RECT.draw(matrices, rowX, rowY, rowWidth, rowHeight, new Vector4f(4),
                top, top, bottom, bottom, InterfaceConfig.glassTintMix());
            RenderUtil.RECT.draw(matrices, right - 1.6f, rowY + 2.2f, 1.6f, rowHeight - 4.4f, 0.8f,
                withAlpha(ACCENT, opacity));
            Fonts.semibold().drawText(matrices, name, right - padX - 3.0f - textWidth, rowY + 2.4f, font,
                withAlpha(TEXT, opacity));
        }
    }

    private void drawMetric(MatrixStack matrices, float x, float y, float width, String label, String value, float opacity) {
        Fonts.semibold().drawCenteredText(matrices, label + "  " + value, x + width / 2.0f, y + 8,
            7.2f, withAlpha(TEXT, opacity));
    }

    private void drawMedia(MatrixStack matrices, float x, float y, float width, float opacity,
                           int mouseX, int mouseY, boolean interactive) {
        MediaPlaybackSnapshot snapshot = SystemMediaService.getInstance().snapshot();
        float seconds = presentationSeconds();
        renderedMediaPlaying = damp(renderedMediaPlaying, snapshot.playing() ? 1.0f : 0.0f, 18.0f, seconds);
        renderedMediaShuffle = damp(renderedMediaShuffle, snapshot.shuffle() ? 1.0f : 0.0f, 18.0f, seconds);
        String title = snapshot.available() ? snapshot.title() : "Media";
        String artist = snapshot.available() ? snapshot.artist() : snapshot.message();
        if (artist.isBlank()) {
            artist = snapshot.available() ? snapshot.source() : "Start playback in a media app";
        }

        Identifier artwork = MediaArtwork.getInstance().texture(snapshot);
        renderedMediaArtworkOpacity = artwork == null
            ? 0.0f
            : damp(renderedMediaArtworkOpacity, 1.0f, 11.0f, seconds);
        float fallbackOpacity = opacity * (1.0f - renderedMediaArtworkOpacity);
        RenderUtil.RECT.draw(matrices, x + 9, y + 9, 35, 35, 7,
            color(55, 30, 50, Math.round(235 * Math.max(fallbackOpacity, artwork == null ? opacity : 0.0f))));
        String sourceGlyph = snapshot.source().isBlank()
            ? "M"
            : snapshot.source().substring(0, 1).toUpperCase(Locale.ROOT);
        Fonts.bold().drawCenteredText(matrices, sourceGlyph, x + 26.5f, y + 18, 10,
            color(255, 90, 169, Math.round(255 * Math.max(fallbackOpacity, artwork == null ? opacity : 0.0f))));
        if (artwork != null) {
            RenderUtil.TEXTURE_RECT.draw(matrices, x + 9, y + 9, 35, 35, new Vector4f(7),
                color(255, 255, 255, Math.round(255 * opacity * renderedMediaArtworkOpacity)),
                0, 0, 1, 1, GuiTextures.view(artwork), GuiTextures.linear(), true);
        }
        float metadataCenter = x + 49 + (width - 56) / 2.0f;
        Fonts.semibold().drawCenteredText(matrices, trim(title, 22), metadataCenter, y + 10, 8.0f,
            withAlpha(TEXT, opacity));
        String subtitle = artist.isBlank() ? snapshot.source() : artist;
        Fonts.regular().drawCenteredText(matrices, trim(subtitle, 29), metadataCenter, y + 25, 6.1f,
            withAlpha(MUTED, opacity));

        float targetProgress = snapshot.durationMs() <= 0 ? 0
            : Math.min(1, snapshot.estimatedProgressMs() / (float) snapshot.durationMs());
        String timelineKey = snapshot.sourceId() + '\u0000' + snapshot.title() + '\u0000'
            + snapshot.artist() + '\u0000' + snapshot.durationMs();
        if (!timelineKey.equals(renderedMediaTimelineKey)) {
            renderedMediaTimelineKey = timelineKey;
            renderedMediaProgress = targetProgress;
        } else {
            renderedMediaProgress = damp(renderedMediaProgress, targetProgress, 12.0f, seconds);
        }
        float trackWidth = width - 22;
        RenderUtil.RECT.draw(matrices, x + 11, y + 51, trackWidth, 3, 1.5f,
            color(69, 70, 70, Math.round(220 * opacity)));
        RenderUtil.RECT.draw(matrices, x + 11, y + 51, trackWidth * renderedMediaProgress, 3, 1.5f,
            color(220, 226, 220, Math.round(255 * opacity)));
        if (renderedMediaProgress > 0.005f) {
            RenderUtil.RECT.draw(matrices, x + 9 + trackWidth * renderedMediaProgress, y + 50, 5, 5, 2.5f,
                color(244, 247, 244, Math.round(255 * opacity)));
        }

        boolean[] buttonEnabled = {
            snapshot.canPrevious(), snapshot.canToggle(), snapshot.canNext(), snapshot.canShuffle()
        };
        for (int index = 0; index < MEDIA_BUTTON_CENTERS.length; index++) {
            boolean buttonHovered = buttonEnabled[index] && interactive && inside(mouseX, mouseY,
                x + MEDIA_BUTTON_CENTERS[index] - 14, y + 57, 28, 20);
            mediaButtonHover[index] = damp(mediaButtonHover[index], buttonHovered ? 1.0f : 0.0f,
                buttonHovered ? 22.0f : 14.0f, seconds);
            float hover = mediaButtonHover[index];
            float selected = index == 3 ? renderedMediaShuffle : 0.0f;
            if (hover > 0.01f || selected > 0.01f) {
                Color buttonColor = blend(Color.WHITE, ACCENT, selected);
                float buttonOpacity = Math.max(hover * 0.078f, selected * 0.105f);
                RenderUtil.RECT.draw(matrices, x + MEDIA_BUTTON_CENTERS[index] - 13, y + 57.5f, 26, 18, 7,
                    withAlpha(buttonColor, opacity * buttonOpacity));
            }
        }

        float previousOpacity = opacity * controlOpacity(buttonEnabled[0], mediaButtonHover[0]);
        float toggleOpacity = opacity * controlOpacity(buttonEnabled[1], mediaButtonHover[1]);
        float nextOpacity = opacity * controlOpacity(buttonEnabled[2], mediaButtonHover[2]);
        float shuffleOpacity = opacity * controlOpacity(buttonEnabled[3], mediaButtonHover[3]);
        drawMediaIcon(matrices, MEDIA_PREVIOUS_ICON, x + MEDIA_BUTTON_CENTERS[0], y + 66.5f, 11.5f,
            withAlpha(TEXT, previousOpacity));
        drawMediaIcon(matrices, MEDIA_PLAY_ICON, x + MEDIA_BUTTON_CENTERS[1], y + 66.5f, 11.5f,
            withAlpha(TEXT, toggleOpacity * (1.0f - renderedMediaPlaying)));
        drawMediaIcon(matrices, MEDIA_PAUSE_ICON, x + MEDIA_BUTTON_CENTERS[1], y + 66.5f, 11.5f,
            withAlpha(TEXT, toggleOpacity * renderedMediaPlaying));
        drawMediaIcon(matrices, MEDIA_NEXT_ICON, x + MEDIA_BUTTON_CENTERS[2], y + 66.5f, 11.5f,
            withAlpha(TEXT, nextOpacity));
        drawMediaIcon(matrices, MEDIA_SHUFFLE_ICON, x + MEDIA_BUTTON_CENTERS[3], y + 66.5f, 11.0f,
            withAlpha(blend(MUTED, ACCENT, renderedMediaShuffle), shuffleOpacity));
     }

     private static void drawMediaIcon(MatrixStack matrices, Identifier texture, float centerX, float centerY,
                                       float size, Color tint) {
        RenderUtil.TEXTURE_RECT.draw(matrices, centerX - size / 2.0f, centerY - size / 2.0f,
            size, size, 0, tint, 0, 0, 1, 1, texture);
    }

    private static float controlOpacity(boolean enabled, float hover) {
        return enabled ? 0.82f + 0.18f * hover : 0.32f;
    }

    private static String trim(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maximum - 3)) + "...";
    }

    private float presentationSeconds() {
        long now = System.nanoTime();
        float seconds = lastPresentationUpdate == 0L
            ? 1.0f / 60.0f
            : Math.min(0.05f, Math.max(0.0f, (now - lastPresentationUpdate) / 1_000_000_000.0f));
        lastPresentationUpdate = now;
        return seconds;
    }

    private static float selectionPhase() {
        final long cycleNanos = 2_800_000_000L;
        return Math.floorMod(System.nanoTime(), cycleNanos) / (float) cycleNanos;
    }

    private static float damp(float current, float target, float speed, float seconds) {
        return current + (target - current) * (1.0f - (float) Math.exp(-speed * seconds));
    }

    private static boolean inside(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static Color blend(Color from, Color to, float amount) {
        float t = Math.max(0.0f, Math.min(1.0f, amount));
        return color(
            Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
            Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
            Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t),
            Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * t)
        );
    }

    private static Color withAlpha(Color source, float opacity) {
        return color(source.getRed(), source.getGreen(), source.getBlue(),
            Math.max(0, Math.min(255, Math.round(source.getAlpha() * opacity))));
    }

    private static Color color(int red, int green, int blue, int alpha) {
        return new Color(red, green, blue, alpha);
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return value == null ? fallback : Float.parseFloat(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String keyName(int keyCode) {
        return InputUtil.Type.KEYSYM.createFromCode(keyCode).getLocalizedText().getString();
    }

    public enum TargetPlacement {
        HUD("HUD"),
        ABOVE_TARGET("Above target");

        private final String label;

        TargetPlacement(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        private static TargetPlacement parse(String value, TargetPlacement fallback) {
            if (value == null) {
                return fallback;
            }
            try {
                return TargetPlacement.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    private record ProjectedTarget(float x, float y) {
    }
}
