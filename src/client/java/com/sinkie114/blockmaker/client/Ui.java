package com.sinkie114.blockmaker.client;

import net.minecraft.client.gui.GuiGraphics;

/** Same vanilla-style panel as NBT Maker. */
final class Ui {
    private Ui() {}
    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF373737);
        g.fill(x + 1, y + 1, x + w - 2, y + h - 2, 0xFFFFFFFF);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
    }
}
