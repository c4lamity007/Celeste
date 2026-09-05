package dev.c4lam.celeste.auth;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

final class LicenseScreen extends Screen {
    private TextFieldWidget license;
    private ButtonWidget activate;
    private ButtonWidget retry;
    private ButtonWidget clear;

    LicenseScreen() {
        super(Text.literal("License Gateway"));
    }

    @Override
    protected void init() {
        int center = width / 2;
        int top = Math.max(42, height / 2 - 70);
        license = new TextFieldWidget(textRenderer, center - 140, top + 45, 280, 20, Text.literal("License"));
        license.setMaxLength(96);
        license.setPlaceholder(Text.literal("CST-XXXXX-XXXXX-XXXXX-XXXXX"));
        addDrawableChild(license);
        activate = addDrawableChild(ButtonWidget.builder(Text.literal("Activate"), button -> {
            AuthBootstrap.submitLicense(license.getText());
            refresh();
        }).dimensions(center - 140, top + 74, 136, 20).build());
        retry = addDrawableChild(ButtonWidget.builder(Text.literal("Retry"), button -> {
            AuthBootstrap.retry();
            refresh();
        }).dimensions(center + 4, top + 74, 136, 20).build());
        clear = addDrawableChild(ButtonWidget.builder(Text.literal("Use another license"), button -> {
            AuthBootstrap.clearLocalBinding();
            license.setText("");
            refresh();
        }).dimensions(center - 140, top + 101, 280, 20).build());
        setInitialFocus(license);
        refresh();
    }

    void refresh() {
        if (activate == null) {
            return;
        }
        boolean busy = AuthBootstrap.busy();
        boolean stored = AuthBootstrap.hasLocalCredential();
        boolean locked = AuthBootstrap.locked();
        activate.active = !busy && !stored && !locked;
        retry.active = !busy && stored && !locked;
        clear.active = !busy && stored && !locked;
        license.setEditable(!busy && !stored && !locked);
    }

    @Override
    public void tick() {
        super.tick();
        refresh();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int center = width / 2;
        int top = Math.max(42, height / 2 - 70);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("License Gateway"), center, top, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(AuthBootstrap.status()), center, top + 22, 0xFFB7C1D1);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void close() {
    }
}
