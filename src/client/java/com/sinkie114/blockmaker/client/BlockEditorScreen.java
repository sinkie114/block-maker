package com.sinkie114.blockmaker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.lwjgl.glfw.GLFW;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Form editor: block type, every block-state property, and the block entity as SNBT. */
public final class BlockEditorScreen extends Screen implements BlockLayer {
    private static final int ROW = 24;
    private final BlockSession session;
    private int x, y, w, h, offset, pageSize, revision;
    private List<Property<?>> properties = List.of();
    private Button syncButton;
    /** First close with unsynced edits only warns; a second close discards them. */
    private boolean confirmClose;

    public BlockEditorScreen(BlockSession session) {
        super(Component.literal("方块数据调试器"));
        this.session = session;
    }
    @Override public BlockSession session() { return session; }
    private boolean writable() { return session.writable(); }

    @Override protected void init() {
        revision = session.revision; syncButton = null;
        w = Math.min(560, width - 12); h = Math.min(400, height - 12); x = (width - w) / 2; y = (height - h) / 2;
        addRenderableWidget(Button.builder(Component.literal("更换方块"), b -> pickBlock()).bounds(x + 8, y + 34, 80, 20)
                .tooltip(Tooltip.create(Component.literal("选择新的方块类型；同名属性会尽量保留"))).build()).active = writable();
        CompoundTag blockEntity = session.blockEntity();
        Button entity = addRenderableWidget(Button.builder(Component.literal(blockEntity == null ? "无方块实体" : "方块实体数据"), b -> editBlockEntity())
                .bounds(x + 92, y + 34, 90, 20)
                .tooltip(Tooltip.create(Component.literal(blockEntity == null ? "这个方块没有方块实体（箱子、告示牌、刷怪笼等才有）"
                        : BlockData.summary(blockEntity) + "\n以 SNBT 查看或编辑"))).build());
        entity.active = blockEntity != null;
        addRenderableWidget(Button.builder(Component.literal("SNBT"), b -> minecraft.setScreen(new SnbtMenu())).bounds(x + 186, y + 34, 56, 20).build());
        addRenderableWidget(Button.builder(Component.literal("邻近更新：" + (session.neighborUpdates ? "开" : "关")), b -> {
            session.neighborUpdates = !session.neighborUpdates; rebuildWidgets();
        }).bounds(x + 246, y + 34, 96, 20).tooltip(Tooltip.create(Component.literal(
                "开：同步时像普通放置一样通知相邻方块。\n关：只更新方块本身，不触发沙子下落、红石或形状更新。"))).build()).active = writable();
        addRenderableWidget(Button.builder(Component.literal("重新读取"), b -> session.reload()).bounds(x + 346, y + 34, 70, 20)
                .tooltip(Tooltip.create(Component.literal("放弃副本中的修改，重新读取世界中的方块（可撤销）"))).build())
                .active = session.loaded && !session.syncing;

        properties = session.preview == null ? List.of() : List.copyOf(session.preview.getProperties());
        int top = y + 62;
        pageSize = Math.max(1, (h - 146) / ROW);
        offset = Math.max(0, Math.min(offset, Math.max(0, (properties.size() - 1) / pageSize * pageSize)));
        for (int i = offset; i < Math.min(properties.size(), offset + pageSize); i++) {
            Property<?> property = properties.get(i);
            Button value = addRenderableWidget(Button.builder(Component.literal(currentValue(property)), b -> editProperty(property))
                    .bounds(x + w / 2, top + (i - offset) * ROW, w / 2 - 8, 20)
                    .tooltip(Tooltip.create(Component.literal("可选：" + String.join(", ", BlockData.values(property))))).build());
            value.active = writable();
        }
        if (properties.size() > pageSize) {
            addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { offset -= pageSize; rebuildWidgets(); }).bounds(x + 8, y + h - 80, 56, 20).build()).active = offset > 0;
            addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { offset += pageSize; rebuildWidgets(); }).bounds(x + 68, y + h - 80, 56, 20).build()).active = offset + pageSize < properties.size();
        }
        addRenderableWidget(Button.builder(Component.literal("撤销"), b -> session.undo()).bounds(x + w - 136, y + h - 80, 62, 20)
                .tooltip(Tooltip.create(Component.literal("撤销上一次副本修改（Ctrl+Z）"))).build()).active = session.canUndo();
        addRenderableWidget(Button.builder(Component.literal("重做"), b -> session.redo()).bounds(x + w - 70, y + h - 80, 62, 20)
                .tooltip(Tooltip.create(Component.literal("重做（Ctrl+Y / Ctrl+Shift+Z）"))).build()).active = session.canRedo();
        if (session.editable) syncButton = addRenderableWidget(Button.builder(Component.literal("同步"), b -> session.sync(ok -> rebuildWidgets()))
                .bounds(x + 8, y + h - 28, 62, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose()).bounds(x + w - 70, y + h - 28, 62, 20).build());
    }

    private String currentValue(Property<?> property) {
        CompoundTag props = BlockData.stateOrEmpty(session.document).getCompoundOrEmpty("Properties");
        return props.getStringOr(property.getName(), session.preview == null ? "?" : nameOf(session.preview, property));
    }
    private static <T extends Comparable<T>> String nameOf(BlockState state, Property<T> property) { return property.getName(state.getValue(property)); }

    private void setProperty(String name, String value) {
        CompoundTag next = session.document.copy(), state = BlockData.stateOrEmpty(next).copy();
        CompoundTag props = state.getCompoundOrEmpty("Properties").copy();
        props.putString(name, value); state.put("Properties", props); next.put(BlockData.STATE, state);
        session.edit(next);
    }

    private void editProperty(Property<?> property) {
        if (!writable()) return;
        List<String> values = BlockData.values(property);
        if (property instanceof BooleanProperty) {
            // Two values: a click is faster than a picker.
            String current = currentValue(property);
            setProperty(property.getName(), values.get((values.indexOf(current) + 1) % values.size()));
            return;
        }
        List<OptionPickerScreen.Option> options = values.stream().map(v -> new OptionPickerScreen.Option(v, v)).toList();
        minecraft.setScreen(new OptionPickerScreen(this, "选择 " + property.getName(), options, value -> {
            setProperty(property.getName(), value); minecraft.setScreen(this);
        }));
    }

    private void pickBlock() {
        if (!writable()) return;
        List<OptionPickerScreen.Option> options = BuiltInRegistries.BLOCK.keySet().stream().sorted()
                .map(id -> new OptionPickerScreen.Option(id.toString(), BuiltInRegistries.BLOCK.get(id).orElseThrow().value().getName().getString())).toList();
        minecraft.setScreen(new OptionPickerScreen(this, "更换方块", options, id -> {
            minecraft.setScreen(this);
            try {
                Block block = BlockData.block(id);
                BlockState from = session.preview != null ? session.preview : block.defaultBlockState();
                CompoundTag next = session.document.copy();
                next.put(BlockData.STATE, BlockData.encodeState(BlockData.convert(from, block)));
                boolean typeChanged = session.preview == null || !session.preview.is(block);
                // Old block-entity data belongs to the old type; the new block starts from its own defaults.
                if (typeChanged) next.remove(BlockData.BLOCK_ENTITY);
                session.edit(next);
                if (typeChanged && block.defaultBlockState().hasBlockEntity()) session.status = "同步后会生成新方块的默认方块实体，可再编辑";
            } catch (RuntimeException ex) { session.status = ex.getMessage(); }
        }));
    }

    private void editBlockEntity() {
        CompoundTag data = session.blockEntity();
        if (data == null) return;
        minecraft.setScreen(new SnbtDialog(this, "方块实体数据 · " + BlockData.summary(data), data.toString(), writable(), tag -> {
            CompoundTag next = session.document.copy(); next.put(BlockData.BLOCK_ENTITY, tag); session.edit(next);
        }));
    }

    private void copy(CompoundTag data, String what) {
        minecraft.keyboardHandler.setClipboard(data.toString()); session.status = "已复制" + what + "（SNBT）";
    }
    private void copySetblock() {
        if (!session.error.isEmpty()) { session.status = "数据无效，无法生成命令：" + session.error; return; }
        String command = BlockData.setblockCommand(session.pos, session.document);
        minecraft.keyboardHandler.setClipboard(command);
        session.status = "已复制 /setblock 命令（" + command.length() + " 字符" + (command.length() > 256 ? "，超过聊天栏 256 字符上限，请粘贴到命令方块" : "") + "）";
    }

    private static Path exportDir(Path gameDir) { return gameDir.resolve("block-maker").resolve("exports"); }

    /** Lists exported .snbt files, newest first; the chosen one opens in the SNBT editor before it reaches the copy. */
    private void importFile() {
        if (!writable()) return;
        Path dir = exportDir(minecraft.gameDirectory.toPath());
        List<OptionPickerScreen.Option> options = new ArrayList<>();
        try (var files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".snbt") && Files.isRegularFile(f))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                    .forEach(f -> options.add(new OptionPickerScreen.Option(f.getFileName().toString(), f.getFileName().toString(), f.toString())));
        } catch (Exception ex) { /* missing folder: nothing exported yet */ }
        if (options.isEmpty()) { session.status = "没有可导入的文件：" + dir; return; }
        minecraft.setScreen(new OptionPickerScreen(this, "从文件导入", options, name -> {
            try {
                Path file = dir.resolve(name);
                if (Files.size(file) > 4 * 1024 * 1024) throw new IllegalArgumentException("文件超过 4 MB");
                String text = Files.readString(file, StandardCharsets.UTF_8);
                minecraft.setScreen(new SnbtDialog(this, "导入 " + name, text, true, session::edit));
            } catch (Exception ex) { minecraft.setScreen(this); session.status = "导入失败：" + ex.getMessage(); }
        }));
    }

    private void export() {
        try {
            Path dir = exportDir(minecraft.gameDirectory.toPath());
            Files.createDirectories(dir);
            Path file = dir.resolve("block-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".snbt");
            Files.writeString(file, session.document.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            session.status = "已导出：" + file;
        } catch (Exception ex) { session.status = "导出失败：" + ex.getMessage(); }
    }

    private final class SnbtMenu extends Screen implements BlockLayer {
        private int sx, sy, sw, sh;
        SnbtMenu() { super(Component.literal("SNBT 复制、粘贴与导出")); }
        @Override public BlockSession session() { return session; }
        @Override protected void init() {
            String[] labels = {"复制副本", "复制原方块", "复制 /setblock 命令", "编辑全部 SNBT", "粘贴 SNBT", "导出文件", "从文件导入", "返回"};
            sw = Math.min(320, width - 16); sh = Math.min(36 + labels.length * 26, height - 16); sx = (width - sw) / 2; sy = (height - sh) / 2;
            Runnable[] tasks = {() -> copy(session.document, "副本"), () -> copy(session.observed, "原方块"), BlockEditorScreen.this::copySetblock,
                    () -> minecraft.setScreen(new SnbtDialog(BlockEditorScreen.this, "全部数据（state / block_entity）", session.document.toString(), writable(), session::edit)),
                    () -> minecraft.setScreen(new SnbtDialog(BlockEditorScreen.this, "从剪贴板导入 SNBT", minecraft.keyboardHandler.getClipboard(), true, session::edit)),
                    BlockEditorScreen.this::export, BlockEditorScreen.this::importFile, () -> {}};
            for (int i = 0; i < labels.length; i++) {
                Runnable task = tasks[i];
                Button b = addRenderableWidget(Button.builder(Component.literal(labels[i]), button -> {
                    minecraft.setScreen(BlockEditorScreen.this); task.run();
                }).bounds(sx + 8, sy + 28 + i * 26, sw - 16, 20).build());
                if (i == 4 || i == 6) b.active = writable();
            }
        }
        @Override public void onClose() { minecraft.setScreen(BlockEditorScreen.this); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void render(GuiGraphics g, int mx, int my, float delta) {
            Ui.panel(g, sx, sy, sw, sh); g.drawString(font, title, sx + 8, sy + 10, 0xFF303030, false);
            super.render(g, mx, my, delta);
        }
    }

    @Override public void tick() {
        if (revision != session.revision) { confirmClose = false; rebuildWidgets(); }
        if (syncButton != null) syncButton.active = session.writable();
    }
    @Override public void onClose() {
        if (session.dirty() && !session.syncing && !confirmClose) {
            confirmClose = true; session.status = "有未同步的修改，再次关闭将丢弃它们";
            return;
        }
        session.close();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (properties.size() > pageSize && scrollY != 0) {
            int target = offset + (scrollY < 0 ? pageSize : -pageSize);
            if (target >= 0 && target < properties.size()) { offset = target; rebuildWidgets(); }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override public boolean keyPressed(KeyEvent key) {
        if (key.isEscape() || minecraft.options.keyInventory.matches(key)) { onClose(); return true; }
        if (key.hasControlDown()) {
            boolean shift = (key.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
            if (key.key() == GLFW.GLFW_KEY_Z && !shift) { session.undo(); return true; }
            if (key.key() == GLFW.GLFW_KEY_Y || key.key() == GLFW.GLFW_KEY_Z) { session.redo(); return true; }
        }
        return super.keyPressed(key);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        Ui.panel(g, x, y, w, h);
        g.fill(x + 7, y + 7, x + 27, y + 27, 0xFF8B8B8B);
        ItemStack icon = session.preview == null ? ItemStack.EMPTY : new ItemStack(session.preview.getBlock().asItem());
        if (!icon.isEmpty()) g.renderItem(icon, x + 9, y + 9);
        String name = session.preview == null ? "无效方块" : session.preview.getBlock().getName().getString();
        g.drawString(font, font.plainSubstrByWidth(name, w - 40), x + 32, y + 8, 0xFF303030, false);
        String where = BlockData.stateOrEmpty(session.document).getStringOr("Name", "?") + " · " + session.pos.toShortString()
                + " · " + session.dimension.identifier() + " · " + (session.editable ? "本地 · 可编辑" : "多人 · 严格只读");
        g.drawString(font, font.plainSubstrByWidth(where, w - 40), x + 32, y + 20, 0xFF555555, false);
        int top = y + 62;
        if (properties.isEmpty()) g.drawString(font, session.preview == null ? "数据无法解析，请在 SNBT 中修正" : "这个方块没有方块状态属性", x + 8, top + 6, 0xFF555555, false);
        for (int i = offset; i < Math.min(properties.size(), offset + pageSize); i++)
            g.drawString(font, properties.get(i).getName(), x + 8, top + (i - offset) * ROW + 6, 0xFF303030, false);
        String warning = !session.valid ? "目标位置已失效（区块未加载或已离开维度）" : session.changed ? "警告：世界中的方块已发生变化，同步将覆盖它" : "";
        String feedback = !session.error.isEmpty() ? "数据无效：" + session.error : !session.loaded ? "正在读取服务端数据…"
                : !session.status.isEmpty() ? session.status
                : session.editable ? "修改仅保留在副本中，点击“同步”写回世界。" : "多人 · 严格只读：可以查看和复制。";
        if (!warning.isEmpty()) g.drawString(font, font.plainSubstrByWidth(warning, w - 16), x + 8, y + h - 56, 0xFFAA2222, false);
        boolean bad = !session.error.isEmpty() || feedback.startsWith("同步失败") || confirmClose;
        g.drawString(font, font.plainSubstrByWidth(feedback, w - 16), x + 8, y + h - 44, bad ? 0xFFAA2222 : 0xFF555555, false);
        super.render(g, mx, my, delta);
        if (my >= y + h - 47 && my < y + h - 33 && font.width(feedback) > w - 16) g.setTooltipForNextFrame(font, Component.literal(feedback), mx, my);
        if (mx >= x + 7 && mx < x + 27 && my >= y + 7 && my < y + 27) g.setTooltipForNextFrame(font, Component.literal(BlockData.stateOrEmpty(session.document).toString()), mx, my);
    }
}
