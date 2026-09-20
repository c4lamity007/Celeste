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
    Success(PathBuf),
    Failure(String, bool),
    ActivationVerified(crate::InstallCredential),
    ActivationRequired(String),
    ActivationRetry(String),
    Folder(Option<PathBuf>),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SetupState {
    Checking,
    Activating,
    NeedsKey,
    Retry,
    Ready,
}

impl SetupState {
    fn allows_download(self) -> bool {
        self == Self::Ready
    }

    fn footer_label(self) -> Option<&'static str> {
        self.allows_download()
            .then_some("Fabric 1.21.11 · Move the JAR yourself")
    }

    fn primary_label(self, busy: bool) -> &'static str {
        match self {
            Self::Checking => "Checking activation...",
            Self::Activating => "Activating...",
            Self::NeedsKey => "Activate Celeste",
            Self::Retry => "Retry verification",
            Self::Ready if busy => "Downloading...",
            Self::Ready => "Download Celeste",
        }
    }

    fn primary_enabled(
        self,
        busy: bool,
        discovering: bool,
        valid_key: bool,
        selected: bool,
    ) -> bool {
        if busy || discovering {
            return false;
        }
        match self {
            Self::NeedsKey => valid_key,
            Self::Retry => true,
            Self::Ready => selected,
            Self::Checking | Self::Activating => false,
        }
    }
}

pub struct LauncherApp {
    license: String,
    setup: SetupState,
    credential: Option<crate::InstallCredential>,
    status: String,
    status_color: Color32,
    busy: bool,
    discovering: bool,
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
            setup: SetupState::Checking,
            credential: None,
            status: String::new(),
            status_color: MUTED,
            busy: false,
            discovering: false,
            folder: String::new(),
            events,
            event_tx,
        };
        app.start_verification(None);
        app
    }

    fn start_verification(&mut self, license: Option<String>) {
        if self.busy || self.discovering {
            return;
        }
        self.setup = if license.is_some() {
            SetupState::Activating
        } else {
            SetupState::Checking
        };
        self.credential = None;
        self.busy = true;
        self.status = if license.is_some() {
            "Verifying your Celeste license and saving activation...".into()
        } else {
            "Checking for a saved activation...".into()
        };
        self.status_color = TEXT;
        let tx = self.event_tx.clone();
        thread::spawn(move || {
            let event = match crate::verify_activation(license) {
                Ok(Some(credential)) => UiEvent::ActivationVerified(credential),
                Ok(None) => UiEvent::ActivationRequired(
                    "Enter your license to activate this computer.".into(),
                ),
                Err(error) if crate::activation_key_required(&error) => {
                    UiEvent::ActivationRequired(format!("{error:#}"))
                }
                Err(error) => UiEvent::ActivationRetry(format!("{error:#}")),
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
                UiEvent::Success(path) => {
                    self.busy = false;
                    self.license.clear();
                    self.status = format!(
                        "Saved to {}. Move this JAR into your Fabric 1.21.11 mods folder, then launch Minecraft normally.",
                        path.display()
                    );
                    self.status_color = SUCCESS;
                }
                UiEvent::Failure(error, activation_required) => {
                    self.busy = false;
                    self.discovering = false;
                    if activation_required {
                        self.setup = SetupState::NeedsKey;
                        self.credential = None;
                    }
                    self.status = error;
                    self.status_color = FAILURE;
                }
                UiEvent::ActivationVerified(credential) => {
                    self.busy = false;
                    self.setup = SetupState::Ready;
                    self.credential = Some(credential);
                    self.license.clear();
                    self.status = "Activation verified. Choose where to save Celeste.jar.".into();
                    self.status_color = SUCCESS;
                }
                UiEvent::ActivationRequired(message) => {
                    self.busy = false;
                    self.setup = SetupState::NeedsKey;
                    self.credential = None;
                    self.status = message;
                    self.status_color = MUTED;
                }
                UiEvent::ActivationRetry(message) => {
                    self.busy = false;
                    self.setup = SetupState::Retry;
                    self.credential = None;
                    self.status = message;
                    self.status_color = FAILURE;
                }
                UiEvent::Folder(folder) => {
                    self.discovering = false;
                    if let Some(folder) = folder {
                        self.folder = folder.display().to_string();
                        self.status.clear();
                    }
                }
            }
        }
    }

    fn start_download(&mut self) {
        if !self.setup.allows_download() || self.busy || self.discovering {
            return;
        }
        let folder =
            match crate::folder_delivery::validate_folder(&PathBuf::from(self.folder.trim())) {
                Ok(folder) => folder,
                Err(error) => {
                    self.status = format!("{error:#}");
                    self.status_color = FAILURE;
                    return;
                }
            };
        let Some(credential) = self.credential.clone() else {
            self.setup = SetupState::NeedsKey;
            return;
        };
        self.busy = true;
        self.status = "Authenticating your download...".into();
        self.status_color = TEXT;
        let tx = self.event_tx.clone();
        thread::spawn(move || {
            let event = match crate::verify_and_download(credential, folder, &tx) {
                Ok(path) => UiEvent::Success(path),
                Err(e) => UiEvent::Failure(format!("{e:#}"), crate::activation_key_required(&e)),
            };
            let _ = tx.send(event);
        });
    }

    fn body(&mut self, ui: &mut egui::Ui) {
        if !self.setup.allows_download() {
            self.activation_body(ui);
            return;
        }
        let width = ui.available_width();
        ui.label(RichText::new("Save location").color(MUTED).size(12.0));
        ui.add_enabled_ui(!self.busy && !self.discovering, |ui| {
            ui.add(
                TextEdit::singleline(&mut self.folder)
                    .desired_width(width)
                    .hint_text("Full path to your download folder"),
            );
            if ui.button("Browse...").clicked() {
                self.discovering = true;
                let tx = self.event_tx.clone();
                thread::spawn(move || {
                    let picked = rfd::FileDialog::new()
                        .set_title("Choose where to save Celeste.jar")
                        .pick_folder();
                    let _ = tx.send(UiEvent::Folder(picked));
                });
            }
        });
        ui.label(RichText::new("Save the mod here, then move the JAR into your Minecraft mods folder yourself. Use Fabric for Minecraft 1.21.11 on this activated computer.").size(12.0).color(MUTED));
        ui.add_space(2.0);
        egui::CollapsingHeader::new("License details")
            .id_salt("celeste-details")
            .show(ui, |ui| {
                ui.set_width(width - 18.0);
                ui.label(
                    RichText::new("Celeste activation verified for this account.")
                        .size(11.5)
                        .color(SUCCESS),
                );
                if ui
                    .add_enabled(
                        !self.busy && !self.discovering,
                        egui::Button::new("Change license"),
                    )
                    .clicked()
                {
                    self.setup = SetupState::NeedsKey;
                    self.credential = None;
                    self.status = "Enter the license you want to use.".into();
                    self.status_color = MUTED;
                }
            });
        self.status_label(ui);
    }

    fn activation_body(&mut self, ui: &mut egui::Ui) {
        ui.label(
            RichText::new("Activate this computer")
                .size(18.0)
                .color(TEXT),
        );
        ui.label(RichText::new("Verify your license before choosing a save location. Your activation is protected for this account; the license key is not saved.").size(12.0).color(MUTED));
        if self.setup == SetupState::Retry {
            ui.label(RichText::new("Verification could not finish. Retry checks your saved activation without enrolling again.").size(12.0).color(MUTED));
            if ui.button("Use a different license").clicked() {
                self.setup = SetupState::NeedsKey;
                self.status = "Enter your license.".into();
                self.status_color = MUTED;
            }
        } else {
            ui.label(RichText::new("License key").size(12.0).color(MUTED));
            let response = ui.add_enabled(
                self.setup == SetupState::NeedsKey && !self.busy,
                TextEdit::singleline(&mut self.license)
                    .password(true)
                    .hint_text("Enter License Here")
                    .desired_width(ui.available_width()),
            );
            if response.lost_focus()
                && ui.input(|input| input.key_pressed(egui::Key::Enter))
                && self.setup == SetupState::NeedsKey
                && crate::license_looks_valid(self.license.trim())
            {
                self.start_verification(Some(self.license.trim().to_owned()));
            }
        }
        if self.busy {
            ui.spinner();
        }
        self.status_label(ui);
    }

    fn status_label(&self, ui: &mut egui::Ui) {
        if !self.status.is_empty() {
            ui.add(
                egui::Label::new(
                    RichText::new(&self.status)
                        .size(12.0)
                        .color(self.status_color),
                )
                .wrap(),
            );
            if self.status_color == FAILURE {
                ui.horizontal(|ui| {
                    if ui.small_button("Copy error").clicked() {
                        ui.ctx().copy_text(format!(
                            "Celeste setup build {}\n{}",
                            crate::SETUP_BUILD,
                            self.status
                        ));
                    }
                    ui.label(RichText::new(crate::SETUP_BUILD).size(10.0).color(MUTED));
                });
            }
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
                                    RichText::new(if self.setup.allows_download() {
                                        "Choose a folder. Download your mod."
                                    } else {
                                        "Activate once. Launch normally."
                                    })
                                    .size(12.0)
                                    .color(MUTED),
                                );
                            });
                            ui.add_space(10.0);
                            let footer_h = if self.setup.allows_download() {
                                67.0
                            } else {
                                44.0
                            };
                            let body_h = (ui.available_height() - footer_h - 8.0).max(60.0);
                            egui::ScrollArea::vertical()
                                .id_salt("celeste-install-body")
                                .auto_shrink([false, false])
                                .max_height(body_h)
                                .show(ui, |ui| self.body(ui));
                            ui.with_layout(Layout::bottom_up(Align::Center), |ui| {
                                if let Some(label) = self.setup.footer_label() {
                                    ui.label(RichText::new(label).size(11.0).color(MUTED));
                                }
                                let enabled = self.setup.primary_enabled(
                                    self.busy,
                                    self.discovering,
                                    crate::license_looks_valid(self.license.trim()),
                                    PathBuf::from(self.folder.trim()).is_absolute(),
                                );
                                let label = self.setup.primary_label(self.busy);
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
                                    match self.setup {
                                        SetupState::NeedsKey => self.start_verification(Some(
                                            self.license.trim().to_owned(),
                                        )),
                                        SetupState::Retry => self.start_verification(None),
                                        SetupState::Ready => self.start_download(),
                                        SetupState::Checking | SetupState::Activating => {}
                                    }
                                }
                            });
                        });
                });
            });
    }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn activation_screens_have_no_license_requirement_footer() {
        for state in [
            SetupState::Checking,
            SetupState::Activating,
            SetupState::NeedsKey,
            SetupState::Retry,
        ] {
            assert_eq!(state.footer_label(), None);
        }
        assert_eq!(
            SetupState::Ready.footer_label(),
            Some("Fabric 1.21.11 · Move the JAR yourself")
        );
    }

    #[test]
    fn all_unverified_states_hide_folder_selection_and_download() {
        for state in [
            SetupState::Checking,
            SetupState::Activating,
            SetupState::NeedsKey,
            SetupState::Retry,
        ] {
            assert!(!state.allows_download());
            assert!(!state.primary_label(false).starts_with("Download"));
        }
        assert!(SetupState::Ready.allows_download());
    }

    #[test]
    fn first_activation_requires_a_key_but_never_an_instance() {
        assert_eq!(
            SetupState::NeedsKey.primary_label(false),
            "Activate Celeste"
        );
        assert!(!SetupState::NeedsKey.primary_enabled(false, false, false, true));
        assert!(SetupState::NeedsKey.primary_enabled(false, false, true, false));
    }

    #[test]
    fn saved_activation_retry_does_not_require_a_key_or_instance() {
        assert_eq!(SetupState::Retry.primary_label(false), "Retry verification");
        assert!(SetupState::Retry.primary_enabled(false, false, false, false));
    }

    #[test]
    fn in_flight_activation_never_enables_a_second_action() {
        for state in [SetupState::Checking, SetupState::Activating] {
            assert!(!state.primary_enabled(false, false, true, true));
        }
        assert!(!SetupState::NeedsKey.primary_enabled(true, false, true, false));
        assert!(!SetupState::Retry.primary_enabled(true, false, false, false));
    }

    #[test]
    fn verified_download_needs_a_folder_but_not_a_key() {
        assert_eq!(SetupState::Ready.primary_label(false), "Download Celeste");
        assert!(!SetupState::Ready.primary_enabled(false, false, false, false));
        assert!(SetupState::Ready.primary_enabled(false, false, false, true));
        assert!(!SetupState::Ready.primary_enabled(false, true, false, true));
    }
}
