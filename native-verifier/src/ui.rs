use crate::launchers::{self, DetectedLauncher, GameInstance};
use eframe::egui::{
    self, Align, Align2, Color32, FontId, Frame, Id, LayerId, Layout, Margin, RichText, Stroke,
    TextEdit, Vec2, Visuals,
};
use std::sync::mpsc::{self, Receiver};
use std::thread;
use std::time::Duration;

pub const BG: Color32 = Color32::from_rgb(18, 18, 22);
pub const CARD: Color32 = Color32::from_rgb(31, 31, 36);
pub const INPUT_BG: Color32 = Color32::from_rgb(24, 24, 28);
pub const ROW: Color32 = Color32::from_rgb(38, 38, 44);
pub const ROW_HOVER: Color32 = Color32::from_rgb(45, 45, 52);
pub const TEXT: Color32 = Color32::from_rgb(230, 230, 236);
pub const MUTED: Color32 = Color32::from_rgb(154, 158, 172);
pub const ACCENT: Color32 = Color32::from_rgb(255, 79, 163);
pub const SUCCESS: Color32 = Color32::from_rgb(110, 200, 140);
pub const FAILURE: Color32 = Color32::from_rgb(255, 110, 120);

const BASE_WIDTH: f32 = 520.0;
const BASE_HEIGHT: f32 = 380.0;
const BASE_CARD_WIDTH: f32 = 340.0;

pub enum UiEvent {
    Status(String),
    Success,
    Failure(String),
}

enum Stage {
    Main,
    Launchers,
    Instances {
        launcher: DetectedLauncher,
        instances: Vec<GameInstance>,
    },
}

#[derive(Clone, Copy)]
struct UiMetrics {
    factor: f32,
    card_width: f32,
    title_size: f32,
    heading_size: f32,
    body_size: f32,
    status_size: f32,
    button_h: f32,
    row_h: f32,
}

impl UiMetrics {
    fn from_screen(rect: egui::Rect) -> Self {
        let width = rect.width().max(1.0);
        let height = rect.height().max(1.0);
        // 1.0 at the default 520x380 window. Title uses factor^1.4 so "Celeste"
        // grows faster than the card when the window is maximized.
        let factor = (width / BASE_WIDTH)
            .min(height / BASE_HEIGHT)
            .clamp(0.9, 4.5);
        let card_width = (BASE_CARD_WIDTH * factor).min(width * 0.56).max(280.0);
        Self {
            factor,
            card_width,
            title_size: 28.0 * factor.powf(1.4),
            heading_size: 22.0 * factor,
            body_size: 14.0 * factor,
            status_size: 12.5 * factor,
            button_h: 38.0 * factor,
            row_h: 34.0 * factor,
        }
    }

    fn i8(self, base: f32) -> i8 {
        (base * self.factor).round().clamp(1.0, 127.0) as i8
    }
}

pub struct LauncherApp {
    license: String,
    ask_license: bool,
    status: String,
    status_color: Color32,
    busy: bool,
    stage: Stage,
    launchers: Vec<DetectedLauncher>,
    events: Receiver<UiEvent>,
    event_tx: mpsc::Sender<UiEvent>,
}

impl LauncherApp {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        apply_theme(&cc.egui_ctx);
        let (event_tx, events) = mpsc::channel();
        Self {
            license: String::new(),
            ask_license: !crate::any_saved_binding(),
            status: String::new(),
            status_color: MUTED,
            busy: false,
            stage: Stage::Main,
            launchers: Vec::new(),
            events,
            event_tx,
        }
    }

    fn pump_events(&mut self) {
        while let Ok(event) = self.events.try_recv() {
            match event {
                UiEvent::Status(line) => {
                    self.status = line;
                    self.status_color = TEXT;
                }
                UiEvent::Success => {
                    self.busy = false;
                    self.ask_license = false;
                    self.license.clear();
                    self.stage = Stage::Main;
                    self.status = "Launched.".to_owned();
                    self.status_color = SUCCESS;
                }
                UiEvent::Failure(error) => {
                    self.busy = false;
                    self.status = error.clone();
                    self.status_color = FAILURE;
                    if license_prompt_required(&error) {
                        self.ask_license = true;
                        self.stage = Stage::Main;
                    }
                }
            }
        }
    }

    fn request_launch(&mut self) {
        if self.ask_license && self.license.trim().len() < 20 {
            self.status = "Enter a valid Celeste license.".to_owned();
            self.status_color = FAILURE;
            return;
        }
        self.open_launchers();
    }

    fn open_launchers(&mut self) {
        if self.busy {
            return;
        }
        self.launchers = launchers::detect_installed();
        if self.launchers.is_empty() {
            self.status = "No launchers found.".to_owned();
            self.status_color = FAILURE;
            return;
        }
        self.stage = Stage::Launchers;
        self.status = String::new();
        self.status_color = MUTED;
    }

    fn pick_launcher(&mut self, launcher: DetectedLauncher) {
        if !launcher.supported() {
            self.status = format!("{} is unsupported.", launcher.label());
            self.status_color = FAILURE;
            return;
        }
        let all = launchers::list_instances(&launcher);
        let preferred = launchers::preferred_instances(&all);
        if preferred.is_empty() {
            self.status = format!("No Fabric 1.21.11 instance in {}.", launcher.label());
            self.status_color = FAILURE;
            self.stage = Stage::Main;
            return;
        }
        if preferred.len() == 1 {
            self.start_install(launcher, preferred.into_iter().next().unwrap());
            return;
        }
        self.stage = Stage::Instances {
            launcher,
            instances: preferred,
        };
    }

    fn start_install(&mut self, launcher: DetectedLauncher, instance: GameInstance) {
        if self.busy {
            return;
        }
        if !crate::saved_binding_exists(&instance.game_dir) && self.license.trim().len() < 20 {
            self.ask_license = true;
            self.stage = Stage::Main;
            self.status = "Enter a valid Celeste license to bind this instance.".to_owned();
            self.status_color = FAILURE;
            return;
        }
        let license = self.license.trim().to_owned();
        self.busy = true;
        self.stage = Stage::Main;
        self.status = "Working...".to_owned();
        self.status_color = TEXT;
        let tx = self.event_tx.clone();
        thread::spawn(
            move || match crate::verify_and_install(license, launcher, instance, &tx) {
                Ok(()) => {
                    let _ = tx.send(UiEvent::Success);
                }
                Err(error) => {
                    let _ = tx.send(UiEvent::Failure(format!("{error:#}")));
                }
            },
        );
    }

    fn paint_background(&self, ctx: &egui::Context) {
        let rect = ctx.screen_rect();
        let painter = ctx.layer_painter(LayerId::background());
        painter.rect_filled(rect, 0.0, BG);
    }
}

impl eframe::App for LauncherApp {
    fn clear_color(&self, _visuals: &Visuals) -> [f32; 4] {
        BG.to_normalized_gamma_f32()
    }

    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        apply_theme(ctx);
        self.pump_events();
        self.paint_background(ctx);

        // Keep the static OpenGL surface fresh when Windows exposes or restores
        // the window. This is intentionally slow while idle; it is not an
        // animation loop.
        ctx.request_repaint_after(Duration::from_millis(if self.busy { 50 } else { 500 }));

        egui::CentralPanel::default()
            .frame(
                Frame::new()
                    .fill(Color32::TRANSPARENT)
                    .inner_margin(Margin::ZERO),
            )
            .show(ctx, |ui| {
                ui.visuals_mut().extreme_bg_color = INPUT_BG;
                ui.visuals_mut().text_edit_bg_color = Some(INPUT_BG);
                match &self.stage {
                    Stage::Main => self.show_main(ui),
                    Stage::Launchers | Stage::Instances { .. } => self.show_picker(ui),
                }
            });
    }
}

impl LauncherApp {
    fn show_main(&mut self, ui: &mut egui::Ui) {
        let metrics = UiMetrics::from_screen(ui.ctx().screen_rect());
        let ctx = ui.ctx().clone();
        egui::Area::new(Id::new("celeste_main_card"))
            .anchor(Align2::CENTER_CENTER, Vec2::ZERO)
            .show(&ctx, |ui| {
                ui.spacing_mut().item_spacing =
                    Vec2::new(10.0 * metrics.factor, 10.0 * metrics.factor);
                show_card(ui, &metrics, |ui| {
                    ui.with_layout(Layout::top_down(Align::Center), |ui| {
                        ui.label(
                            RichText::new("Celeste")
                                .color(ACCENT)
                                .font(FontId::proportional(metrics.title_size))
                                .strong(),
                        );
                        ui.add_space(if self.ask_license { 18.0 } else { 10.0 } * metrics.factor);
                        if self.ask_license {
                            ui.add_enabled_ui(!self.busy, |ui| {
                                ui.add(
                                    TextEdit::singleline(&mut self.license)
                                        .password(true)
                                        .hint_text(
                                            RichText::new("CST-XXXXX-XXXXX-XXXXX-XXXXX")
                                                .color(MUTED)
                                                .size(metrics.body_size),
                                        )
                                        .font(FontId::proportional(metrics.body_size))
                                        .desired_width(ui.available_width())
                                        .background_color(INPUT_BG)
                                        .text_color(TEXT)
                                        .margin(Margin::symmetric(
                                            metrics.i8(12.0),
                                            metrics.i8(10.0),
                                        )),
                                );
                            });
                            ui.add_space(6.0 * metrics.factor);
                        }
                        let fill = if self.busy {
                            Color32::from_rgb(70, 70, 82)
                        } else {
                            ACCENT
                        };
                        let label = if self.busy { "Working..." } else { "Launch" };
                        let button = ui.add_enabled(
                            !self.busy,
                            egui::Button::new(
                                RichText::new(label)
                                    .color(Color32::WHITE)
                                    .size(15.0 * metrics.factor)
                                    .strong(),
                            )
                            .fill(fill)
                            .stroke(Stroke::NONE)
                            .corner_radius(8.0 * metrics.factor)
                            .min_size(Vec2::new(ui.available_width(), metrics.button_h)),
                        );
                        if button.clicked() {
                            self.request_launch();
                        }
                        if !self.busy && ui.input(|input| input.key_pressed(egui::Key::Enter)) {
                            self.request_launch();
                        }
                        if !self.status.is_empty() {
                            ui.add_space(4.0 * metrics.factor);
                            ui.label(
                                RichText::new(&self.status)
                                    .color(self.status_color)
                                    .size(metrics.status_size),
                            );
                        }
                    });
                });
            });
    }

    fn show_picker(&mut self, ui: &mut egui::Ui) {
        let metrics = UiMetrics::from_screen(ui.ctx().screen_rect());
        let ctx = ui.ctx().clone();
        egui::Area::new(Id::new("celeste_picker_card"))
            .anchor(Align2::CENTER_CENTER, Vec2::ZERO)
            .show(&ctx, |ui| {
                ui.spacing_mut().item_spacing =
                    Vec2::new(10.0 * metrics.factor, 10.0 * metrics.factor);
                show_card(ui, &metrics, |ui| {
                    ui.with_layout(Layout::top_down(Align::Center), |ui| {
                        let title = match &self.stage {
                            Stage::Instances { .. } => "Instances",
                            _ => "Launchers",
                        };
                        ui.label(
                            RichText::new(title)
                                .color(ACCENT)
                                .font(FontId::proportional(metrics.heading_size))
                                .strong(),
                        );
                        ui.add_space(8.0 * metrics.factor);

                        let mut picked_launcher: Option<DetectedLauncher> = None;
                        let mut picked_instance: Option<(DetectedLauncher, GameInstance)> = None;
                        let mut back = false;

                        egui::ScrollArea::vertical()
                            .max_height(180.0 * metrics.factor)
                            .auto_shrink([false, true])
                            .show(ui, |ui| {
                                ui.set_width(metrics.card_width);
                                match &self.stage {
                                    Stage::Launchers => {
                                        for launcher in &self.launchers {
                                            let supported = launcher.supported();
                                            let text = if supported {
                                                launcher.label().to_owned()
                                            } else {
                                                format!("{} (unsupported)", launcher.label())
                                            };
                                            let color = if supported { TEXT } else { MUTED };
                                            let button = ui.add_enabled(
                                                supported && !self.busy,
                                                row_button(
                                                    RichText::new(text)
                                                        .color(color)
                                                        .size(metrics.body_size),
                                                    &metrics,
                                                ),
                                            );
                                            if button.clicked() {
                                                picked_launcher = Some(launcher.clone());
                                            }
                                        }
                                    }
                                    Stage::Instances {
                                        launcher,
                                        instances,
                                    } => {
                                        for instance in instances {
                                            let label = if instance.version.is_empty() {
                                                instance.name.clone()
                                            } else {
                                                format!("{}  {}", instance.name, instance.version)
                                            };
                                            if ui
                                                .add_enabled(
                                                    !self.busy,
                                                    row_button(
                                                        RichText::new(label)
                                                            .color(TEXT)
                                                            .size(metrics.body_size),
                                                        &metrics,
                                                    ),
                                                )
                                                .clicked()
                                            {
                                                picked_instance =
                                                    Some((launcher.clone(), instance.clone()));
                                            }
                                        }
                                    }
                                    Stage::Main => {}
                                }
                            });

                        ui.add_space(8.0 * metrics.factor);
                        if ui
                            .add(
                                egui::Button::new(
                                    RichText::new("Back").color(TEXT).size(metrics.body_size),
                                )
                                .fill(ROW)
                                .stroke(Stroke::NONE)
                                .corner_radius(8.0 * metrics.factor)
                                .min_size(Vec2::new(120.0 * metrics.factor, 30.0 * metrics.factor)),
                            )
                            .clicked()
                        {
                            back = true;
                        }

                        if back {
                            self.stage = match &self.stage {
                                Stage::Instances { .. } => Stage::Launchers,
                                _ => Stage::Main,
                            };
                        } else if let Some(launcher) = picked_launcher {
                            self.pick_launcher(launcher);
                        } else if let Some((launcher, instance)) = picked_instance {
                            self.start_install(launcher, instance);
                        }
                    });
                });
            });
    }
}

fn license_prompt_required(error: &str) -> bool {
    let lower = error.to_lowercase();
    lower.contains("license")
        || lower.contains("enroll")
        || lower.contains("credential")
        || lower.contains("unauthorized")
        || lower.contains("(401)")
        || lower.contains("authentication denied")
        || lower.contains("bind")
}

fn show_card(ui: &mut egui::Ui, metrics: &UiMetrics, add_contents: impl FnOnce(&mut egui::Ui)) {
    Frame::new()
        .fill(CARD)
        .stroke(Stroke::NONE)
        .corner_radius(16.0 * metrics.factor)
        .inner_margin(Margin::symmetric(metrics.i8(24.0), metrics.i8(26.0)))
        .shadow(egui::Shadow::NONE)
        .show(ui, |ui| {
            ui.set_width(metrics.card_width);
            add_contents(ui);
        });
}

fn row_button<'a>(text: impl Into<egui::WidgetText>, metrics: &UiMetrics) -> egui::Button<'a> {
    egui::Button::new(text)
        .fill(ROW)
        .stroke(Stroke::NONE)
        .corner_radius(8.0 * metrics.factor)
        .min_size(Vec2::new(metrics.card_width, metrics.row_h))
}

fn paint_widget(
    visuals: &mut egui::style::WidgetVisuals,
    fill: Color32,
    stroke: Color32,
    fg: Color32,
) {
    visuals.bg_fill = fill;
    visuals.weak_bg_fill = fill;
    visuals.bg_stroke = if stroke == Color32::TRANSPARENT {
        Stroke::NONE
    } else {
        Stroke::new(1.0_f32, stroke)
    };
    visuals.fg_stroke = Stroke::new(1.0_f32, fg);
}

pub fn apply_theme(ctx: &egui::Context) {
    ctx.set_theme(egui::ThemePreference::Dark);

    let mut visuals = Visuals::dark();
    visuals.dark_mode = true;
    visuals.panel_fill = BG;
    visuals.window_fill = CARD;
    visuals.faint_bg_color = CARD;
    visuals.extreme_bg_color = INPUT_BG;
    visuals.text_edit_bg_color = Some(INPUT_BG);
    visuals.override_text_color = Some(TEXT);
    visuals.hyperlink_color = ACCENT;
    visuals.selection.bg_fill = Color32::from_rgba_unmultiplied(255, 79, 163, 90);
    visuals.selection.stroke = Stroke::new(1.0_f32, ACCENT);
    visuals.window_stroke = Stroke::NONE;
    visuals.window_shadow = egui::Shadow::NONE;
    visuals.popup_shadow = egui::Shadow::NONE;

    paint_widget(
        &mut visuals.widgets.noninteractive,
        CARD,
        Color32::TRANSPARENT,
        MUTED,
    );
    paint_widget(
        &mut visuals.widgets.inactive,
        ROW,
        Color32::TRANSPARENT,
        TEXT,
    );
    paint_widget(
        &mut visuals.widgets.hovered,
        ROW_HOVER,
        Color32::TRANSPARENT,
        TEXT,
    );
    paint_widget(
        &mut visuals.widgets.active,
        Color32::from_rgb(42, 42, 50),
        ACCENT,
        TEXT,
    );
    paint_widget(&mut visuals.widgets.open, ROW, Color32::TRANSPARENT, TEXT);

    ctx.all_styles_mut(|style| {
        style.visuals = visuals.clone();
        style.spacing.button_padding = Vec2::new(14.0, 8.0);
        style.spacing.item_spacing = Vec2::new(10.0, 10.0);
        style.visuals.widgets.inactive.corner_radius = egui::CornerRadius::same(8);
        style.visuals.widgets.hovered.corner_radius = egui::CornerRadius::same(8);
        style.visuals.widgets.active.corner_radius = egui::CornerRadius::same(8);
        style.visuals.widgets.noninteractive.corner_radius = egui::CornerRadius::same(8);
    });
}

pub fn launch_gui() -> anyhow::Result<()> {
    let mut viewport = egui::ViewportBuilder::default()
        .with_title("Celeste Launcher")
        .with_inner_size([520.0, 380.0])
        .with_min_inner_size([480.0, 340.0])
        .with_resizable(true);
    if let Ok(source) = image::load_from_memory(include_bytes!(
        "../../src/main/resources/assets/celeste/textures/gui/celeste-logo.png"
    )) {
        let source = source.into_rgba8();
        viewport = viewport.with_icon(egui::IconData {
            width: source.width(),
            height: source.height(),
            rgba: source.into_raw(),
        });
    }
    let options = eframe::NativeOptions {
        viewport,
        centered: true,
        persist_window: false,
        ..Default::default()
    };
    eframe::run_native(
        "Celeste Launcher",
        options,
        Box::new(|cc| Ok(Box::new(LauncherApp::new(cc)))),
    )
    .map_err(|error| anyhow::anyhow!("Could not open Celeste Launcher: {error}"))
}
