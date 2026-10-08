package com.sinkie114.blockmaker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** Full-text SNBT editor. Parsing failure keeps the dialog and the document untouched. */
final class SnbtDialog extends Screen implements BlockLayer {
    private final Screen parent;
    private final boolean writable;
    private final Consumer<CompoundTag> commit;
    private String text, message = "";
    private MultiLineEditBox value;
    private int x, y, w, h;

    SnbtDialog(Screen parent, String title, String text, boolean writable, Consumer<CompoundTag> commit) {
        super(Component.literal(title)); this.parent = parent; this.text = text; this.writable = writable; this.commit = commit;
    }
    @Override public BlockSession session() { return ((BlockLayer) parent).session(); }

    static CompoundTag parse(String text) {
        try { return TagParser.parseCompoundFully(text); }
        catch (Exception ex) { throw new IllegalArgumentException(ex.getMessage() == null ? ex.toString() : ex.getMessage()); }
    }

    @Override protected void init() {
        w = Math.min(680, width - 16); h = Math.min(400, height - 16); x = (width - w) / 2; y = (height - h) / 2;
        value = addRenderableWidget(MultiLineEditBox.builder().setX(x + 8).setY(y + 25).build(font, w - 16, h - 86, Component.literal("SNBT")));
        value.setCharacterLimit(Integer.MAX_VALUE); value.setValue(text); value.setValueListener(s -> text = s);
        if (writable) addRenderableWidget(Button.builder(Component.literal("载入编辑副本"), b -> submit()).bounds(x + 8, y + h - 28, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("复制全文"), b -> minecraft.keyboardHandler.setClipboard(value.getValue()))
                .bounds(x + (writable ? 124 : 8), y + h - 28, 82, 20).build());
        addRenderableWidget(Button.builder(Component.literal(writable ? "取消" : "返回"), b -> onClose()).bounds(x + w - 78, y + h - 28, 70, 20).build());
        setInitialFocus(value);
    }

    private void submit() {
        try { commit.accept(parse(value.getValue())); minecraft.setScreen(parent); }
        catch (RuntimeException ex) { message = ex.getMessage() == null ? ex.toString() : ex.getMessage(); }
    }

    @Override public boolean charTyped(CharacterEvent event) { return writable && super.charTyped(event); }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) { onClose(); return true; }
        if (!writable && !(event.isCopy() || event.isSelectAll() || event.isLeft() || event.isRight() || event.isUp() || event.isDown()
                || event.key() == 268 || event.key() == 269 || event.key() == 266 || event.key() == 267 || event.isCycleFocus())) return true;
        if (event.hasControlDown() && event.isConfirmation() && writable) { submit(); return true; }
        return super.keyPressed(event);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        Ui.panel(g, x, y, w, h);
        g.drawString(font, title, x + 8, y + 9, 0xFF303030, false);
        g.drawString(font, font.plainSubstrByWidth(message.isEmpty() ? (writable ? "SNBT 字符串需要引号。Ctrl+Enter 载入编辑副本，点击主界面“同步”才写入世界。" : "多人 · 只读")
                : message, w - 16), x + 8, y + h - 52, message.isEmpty() ? 0xFF555555 : 0xFFAA2222, false);
        if (!message.isEmpty() && my >= y + h - 56 && my <= y + h - 32) g.setTooltipForNextFrame(font, Component.literal(message), mx, my);
        super.render(g, mx, my, delta);
    }
}
