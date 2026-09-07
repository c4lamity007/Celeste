use crate::launchers::{self, InstallTarget};
use eframe::egui::{
    self, Align, Align2, Color32, FontId, Frame, Layout, Margin, RichText, Stroke, TextEdit, Vec2,
    Visuals,
};
use std::path::PathBuf;
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

pub enum UiEvent {
    Status(String),
    Success,
    Failure(String),
    Instances(Vec<InstallTarget>),
    Folder(Option<PathBuf>),
}

pub struct LauncherApp {
    license: String,
    ask_license: bool,
    status: String,
    status_color: Color32,
    busy: bool,
    discovering: bool,
    instances: Vec<InstallTarget>,
    selected: Option<usize>,
    folder: String,
    events: Receiver<UiEvent>,
    event_tx: mpsc::Sender<UiEvent>,
}

impl LauncherApp {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        apply_theme(&cc.egui_ctx);
        let (event_tx, events) = mpsc::channel();
        let mut app = Self {
            license: String::new(),
            ask_license: !crate::any_saved_binding(),
            status: String::new(),
            status_color: MUTED,
            busy: false,
            discovering: false,
            instances: Vec::new(),
            selected: None,
            folder: String::new(),
            events,
            event_tx,
        };
        app.refresh();
        app
    }

    fn refresh(&mut self) {
        if self.busy || self.discovering {
            return;
        }
        self.discovering = true;
        let tx = self.event_tx.clone();
        thread::spawn(move || {
            let _ = tx.send(UiEvent::Instances(launchers::discover_install_targets()));
        });
    }

    fn inspect_folder(&mut self, folder: PathBuf) {
        if self.busy || self.discovering {
            return;
        }
        self.folder = folder.display().to_string();
        self.discovering = true;
        let tx = self.event_tx.clone();
        thread::spawn(move || {
            let event = match launchers::inspect_folder(&folder) {
                Ok(instances) => UiEvent::Instances(instances),
                Err(e) => UiEvent::Failure(format!("{e:#}")),
            };
            let _ = tx.send(event);
        });
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
                    self.status = "Installed. Open this instance in your launcher.".into();
                    self.status_color = SUCCESS;
                }
                UiEvent::Failure(error) => {
                    self.busy = false;
                    self.discovering = false;
                    if license_prompt_required(&error) {
                        self.ask_license = true;
                    }
                    self.status = error;
                    self.status_color = FAILURE;
                }
                UiEvent::Instances(instances) => {
                    let previous = self
                        .selected
                        .and_then(|i| self.instances.get(i))
                        .map(|t| t.instance.game_dir.clone());
                    self.selected = previous
                        .and_then(|p| instances.iter().position(|t| t.instance.game_dir == p));
                    self.instances = instances;
                    self.discovering = false;
                    self.status = if self.instances.is_empty() {
                        "No Fabric 1.21.11 instances found. Choose a folder below.".into()
                    } else {
                        String::new()
                    };
                    self.status_color = MUTED;
                }
                UiEvent::Folder(folder) => {
                    self.discovering = false;
                    if let Some(folder) = folder {
                        self.inspect_folder(folder);
                    }
                }
            }
        }
    }

    fn start_install(&mut self) {
        if self.busy || self.discovering {
            return;
        }
        let Some(target) = self.selected.and_then(|i| self.instances.get(i)).cloned() else {
            return;
        };
        if !crate::saved_binding_exists(&target.instance.game_dir) && self.license.trim().len() < 20
        {
            self.ask_license = true;
            self.status = "Enter your Celeste license to activate this PC.".into();
            self.status_color = FAILURE;
            return;
        }
        let license = self.license.trim().to_owned();
        self.busy = true;
        self.status = "Checking this instance...".into();
        self.status_color = TEXT;
        let tx = self.event_tx.clone();
        thread::spawn(move || {
            let event =
                match crate::verify_and_install(license, target.launcher, target.instance, &tx) {
                    Ok(()) => UiEvent::Success,
                    Err(e) => UiEvent::Failure(format!("{e:#}")),
                };
            let _ = tx.send(event);
        });
    }

    fn body(&mut self, ui: &mut egui::Ui) {
        let width = ui.available_width();
        ui.label(RichText::new("Minecraft instance").color(MUTED).size(12.0));
        let selected_text = self
            .selected
            .and_then(|i| self.instances.get(i))
            .map(|t| format!("{} · {}", t.launcher.label(), t.instance.name))
            .unwrap_or_else(|| {
                if self.discovering {
                    "Finding instances...".into()
                } else {
                    "Choose your instance".into()
                }
            });
        ui.add_enabled_ui(!self.busy && !self.discovering, |ui| {
            egui::ComboBox::from_id_salt("celeste-instance")
                .width(width)
                .truncate()
                .selected_text(RichText::new(selected_text).size(13.0))
                .show_ui(ui, |ui| {
                    ui.set_width(width - 16.0);
                    for (i, target) in self.instances.iter().enumerate() {
                        let label =
                            format!("{} · {}", target.launcher.label(), target.instance.name);
                        let response = ui.add(
                            egui::Button::selectable(
                                self.selected == Some(i),
                                RichText::new(label).size(13.0),
                            )
                            .wrap(),
                        );
                        if response.clicked() {
                            self.selected = Some(i);
                            self.status.clear();
                        }
                        response.on_hover_text(target.instance.game_dir.display().to_string());
                    }
                });
        });
        if let Some(target) = self.selected.and_then(|i| self.instances.get(i)) {
            ui.add(
                egui::Label::new(
                    RichText::new(target.instance.game_dir.display().to_string())
                        .size(11.5)
                        .color(MUTED),
                )
                .wrap(),
            );
        }
        ui.add_space(2.0);
        if self.ask_license {
            ui.label(RichText::new("License key").size(12.0).color(MUTED));
            ui.add_enabled(
                !self.busy,
                TextEdit::singleline(&mut self.license)
                    .password(true)
                    .hint_text("Celeste license")
                    .desired_width(width),
            );
        }
        egui::CollapsingHeader::new("Other folder / details")
            .id_salt("celeste-details")
            .show(ui, |ui| {
                ui.set_width(width - 18.0);
                ui.label(
                    RichText::new("Fabric · Minecraft 1.21.11")
                        .size(12.0)
                        .color(MUTED),
                );
                ui.add_enabled_ui(!self.busy && !self.discovering, |ui| {
                    ui.horizontal(|ui| {
                        if ui.button("Browse...").clicked() {
                            self.discovering = true;
                            let tx = self.event_tx.clone();
                            thread::spawn(move || {
                                let picked = rfd::FileDialog::new()
                                    .set_title("Choose your Minecraft instance folder")
                                    .pick_folder();
                                let _ = tx.send(UiEvent::Folder(picked));
                            });
                        }
                        if ui.button("Refresh").clicked() {
                            self.refresh();
                        }
                    });
                    ui.add(
                        TextEdit::singleline(&mut self.folder)
                            .desired_width(ui.available_width())
                            .hint_text("Full path to instance or .minecraft"),
                    );
                    if ui.button("Use this folder").clicked() {
                        self.inspect_folder(PathBuf::from(self.folder.trim()));
                    }
                });
                if !self.ask_license {
                    ui.label(
                        RichText::new("Saved activation is used. Enter a key to change licenses.")
                            .size(11.5)
                            .color(MUTED),
                    );
                    ui.add_enabled(
                        !self.busy,
                        TextEdit::singleline(&mut self.license)
                            .password(true)
                            .desired_width(ui.available_width())
                            .hint_text("Different Celeste license (optional)"),
                    );
                }
            });
        if !self.status.is_empty() {
            ui.add(
                egui::Label::new(
                    RichText::new(&self.status)
                        .size(12.0)
                        .color(self.status_color),
                )
                .wrap(),
            );
        }
    }
}

impl eframe::App for LauncherApp {
    fn clear_color(&self, _: &Visuals) -> [f32; 4] {
        BG.to_normalized_gamma_f32()
    }
    fn update(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        self.pump_events();
        ctx.request_repaint_after(Duration::from_millis(if self.busy || self.discovering {
            80
        } else {
            500
        }));
        egui::CentralPanel::default()
            .frame(Frame::new().fill(BG))
            .show(ctx, |ui| {
                let outer = ui.max_rect();
                let card_width = (outer.width() - 36.0).clamp(300.0, 420.0);
                let card_height = (outer.height() - 36.0).clamp(260.0, 460.0);
                let rect = egui::Rect::from_center_size(
                    outer.center(),
                    Vec2::new(card_width, card_height),
                );
                ui.scope_builder(egui::UiBuilder::new().max_rect(rect), |ui| {
                    Frame::new()
                        .fill(CARD)
                        .corner_radius(16.0)
                        .inner_margin(Margin::same(22))
                        .show(ui, |ui| {
                            ui.set_width(card_width - 44.0);
                            ui.set_height(card_height - 44.0);
                            ui.vertical_centered(|ui| {
                                ui.label(
                                    RichText::new("Celeste").size(29.0).strong().color(ACCENT),
                                );
                                ui.label(
                                    RichText::new("Install once. Launch normally.")
                                        .size(12.0)
                                        .color(MUTED),
                                );
                            });
                            ui.add_space(10.0);
                            let footer_h = 67.0;
                            let body_h = (ui.available_height() - footer_h - 8.0).max(60.0);
                            egui::ScrollArea::vertical()
                                .id_salt("celeste-install-body")
                                .auto_shrink([false, false])
                                .max_height(body_h)
                                .show(ui, |ui| self.body(ui));
                            ui.with_layout(Layout::bottom_up(Align::Center), |ui| {
                                ui.label(
                                    RichText::new("Fabric 1.21.11 · mods/Celeste.jar")
                                        .size(11.0)
                                        .color(MUTED),
                                );
                                let enabled =
                                    !self.busy && !self.discovering && self.selected.is_some();
                                let label = if self.busy {
                                    "Installing..."
                                } else {
                                    "Install Celeste"
                                };
                                let response = ui.add_enabled(
                                    enabled,
                                    egui::Button::new("")
                                        .fill(ACCENT)
                                        .stroke(Stroke::NONE)
                                        .corner_radius(9.0)
                                        .min_size(Vec2::new(ui.available_width(), 38.0)),
                                );
                                ui.painter().text(
                                    response.rect.center(),
                                    Align2::CENTER_CENTER,
                                    label,
                                    FontId::proportional(14.0),
                                    if enabled {
                                        Color32::WHITE
                                    } else {
                                        Color32::from_gray(190)
                                    },
                                );
                                if response.clicked() {
                                    self.start_install();
                                }
                            });
                        });
                });
            });
    }
}

fn license_prompt_required(error: &str) -> bool {
    let lower = error.to_lowercase();
    [
        "license",
        "enroll",
        "credential",
        "unauthorized",
        "(401)",
        "(403)",
        "authentication denied",
        "bind",
    ]
    .iter()
    .any(|s| lower.contains(s))
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
        .with_title("Celeste")
        .with_inner_size([480.0, 440.0])
        .with_min_inner_size([400.0, 380.0])
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
        "Celeste",
        options,
        Box::new(|cc| Ok(Box::new(LauncherApp::new(cc)))),
    )
    .map_err(|error| anyhow::anyhow!("Could not open Celeste: {error}"))
}
