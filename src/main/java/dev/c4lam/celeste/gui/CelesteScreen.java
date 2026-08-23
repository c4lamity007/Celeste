package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.config.ConfigProfileManager;
import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.gui.model.CelestePage;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.gui.ColorPickerControl;
import dev.c4lam.celeste.hud.HudEditorScreen;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.GuiTextures;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Font;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Celeste 1.0 Click GUI. Runtime behavior is attached through ModuleManager;
 * this screen owns presentation and edits the shared module/settings model.
 */
public final class CelesteScreen extends Screen {
    private static final Identifier LOGO = Identifier.of("celeste", "textures/gui/celeste-logo.png");
    private static final Identifier SEARCH_ICON = Identifier.of("celeste", "textures/gui/icons/search.png");
    private static final Identifier HELP_ICON = Identifier.of("celeste", "textures/gui/icons/help.png");
    private static final long TOGGLE_ANIMATION_NANOS = 125_000_000L;
    private static final long EXPANSION_ANIMATION_NANOS = 180_000_000L;
    private static final long NAVIGATION_ANIMATION_NANOS = 210_000_000L;
    private static final long HOVER_ANIMATION_NANOS = 135_000_000L;
    private static final long PAGE_TRANSITION_NANOS = 225_000_000L;
    private static final long PAGE_STAGGER_NANOS = 18_000_000L;
    private static final float SLIDER_SMOOTH_TIME_SECONDS = 0.10f;
    private static final long SEARCH_REVEAL_DELAY_NANOS = 38_000_000L;
    private static final long SEARCH_FADE_NANOS = 105_000_000L;
    private static final float COMPACT_SCALE = 2.0f / 3.0f;
    private static final float REFERENCE_MINECRAFT_GUI_SCALE = 4.0f;

    private static final Color TEXT = color(250, 243, 250, 255);
    private static final Color MUTED = color(184, 169, 184, 255);
    private static final Color DIM = color(135, 119, 135, 255);
    private static final Color ACCENT = color(255, 43, 143, 255);
    private static final Color ACCENT_SOFT = color(191, 33, 112, 175);
    private static final Color PANEL_TOP = color(31, 18, 31, 244);
    private static final Color PANEL_BOTTOM = color(14, 10, 17, 249);
    private static final Color SIDEBAR = color(9, 6, 12, 222);
    private static final Color CARD = color(41, 27, 40, 170);
    private static final Color CARD_HOVER = color(53, 34, 50, 192);
    private static final Color CARD_OPEN = color(48, 29, 46, 218);
    private static final Color CONTROL = color(19, 13, 22, 205);
    private static final Object SEARCH_HOVER_KEY = new Object();
    private static final Object SETTINGS_BUTTON_HOVER_KEY = new Object();

    private CelestePage selectedPage = CelestePage.SWORD;
    private GuiModule expandedModule;
    private GuiSetting.Slider draggingSlider;
    private ConfigHit draggingConfig;
    private GuiSetting.Keybind listeningKeybind;
    private final ColorPickerControl colorPicker = new ColorPickerControl();

    private final List<NavHit> navHits = new ArrayList<>();
    private final List<ModuleHit> moduleHits = new ArrayList<>();
    private final List<SettingHit> settingHits = new ArrayList<>();
    private final List<ConfigHit> configHits = new ArrayList<>();
    private final List<FriendHit> friendHits = new ArrayList<>();
    private final List<ProfileHit> profileHits = new ArrayList<>();
    private final List<ActionHit> actionHits = new ArrayList<>();
    private String configInput = "";
    private boolean configFieldFocused;
    private String configStatus = "";
    private long configStatusUntil;
    private FieldHit configFieldHit;
    private String pendingTooltip = "";
    private float pendingTooltipX;
    private float pendingTooltipY;
    private final Map<Object, TimedAnimation> toggleAnimations = new IdentityHashMap<>();
    private final Map<Object, SpringAnimation> sliderAnimations = new IdentityHashMap<>();
    private final Map<GuiModule, TimedAnimation> expansionAnimations = new IdentityHashMap<>();
    private final Map<CelestePage, TimedAnimation> navigationAnimations = new IdentityHashMap<>();
    private final Map<Object, TimedAnimation> hoverAnimations = new IdentityHashMap<>();
    private final List<Long> searchRevealTimes = new ArrayList<>();

    private float contentScroll;
    private float targetScroll;
    private String search = "";
    private boolean searchFocused;
    private long openedAt;
    private long pageChangedAt;
    private float renderedInterfaceScale = InterfaceConfig.DEFAULT_SCALE;
    private float interfaceScaleVelocity;
    private long lastScaleUpdateAt;

    private float panelX;
    private float panelY;
    private float panelWidth;
    private float panelHeight;
    private float sidebarWidth;
    private float contentX;
    private float contentY;
    private float contentWidth;
    private float contentHeight;
    private float uiScale = 1.0f;

    public CelesteScreen() {
        super(Text.literal("Celeste Client"));
    }

    @Override
    protected void init() {
        openedAt = System.nanoTime();
        pageChangedAt = openedAt;
        renderedInterfaceScale = InterfaceConfig.scale();
        interfaceScaleVelocity = 0.0f;
        lastScaleUpdateAt = openedAt;
        layout();
    }
