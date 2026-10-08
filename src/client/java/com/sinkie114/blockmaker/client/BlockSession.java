package com.sinkie114.blockmaker.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import java.util.*;
import java.util.function.Consumer;

/** No custom packets. Every server access runs on the integrated server executor; remote worlds are strictly read-only. */
public final class BlockSession {
    private static final int HISTORY_LIMIT = 100;
    public final Minecraft client;
    public final boolean editable;
    public final BlockPos pos;
    public final ResourceKey<Level> dimension;
    private final ClientLevel level;
    private final IntegratedServer server;
    private final Deque<CompoundTag> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private boolean pollPending;
    public boolean syncing, valid = true, changed;
    /**
     * Clients do not receive most block-entity data (for example chest contents), so an editable copy starts from the
     * integrated server's data. Editing stays locked until that first read arrives; read-only copies are loaded at once.
     */
    public boolean loaded;
    /** Off: write with client updates only, so neighbours, gravity and redstone are not triggered. */
    public boolean neighborUpdates = true;
    public CompoundTag document, baseline, observed;
    public BlockState preview;
    public String error = "", status = "";
    public int revision;

    private BlockSession(Minecraft client, BlockPos pos, boolean readOnly) {
        this.client = client; this.level = client.level; this.pos = pos.immutable();
        this.dimension = level.dimension();
        this.server = readOnly ? null : client.getSingleplayerServer();
        editable = server != null;
        document = readClient();
        baseline = document.copy(); observed = baseline.copy();
        loaded = !editable;
        refresh();
        if (editable) poll();
    }

    public static BlockSession open(Minecraft client, BlockPos pos) { return new BlockSession(client, pos, false); }
    /** The exact remote-world path, also used by tests in singleplayer. */
    public static BlockSession readOnly(Minecraft client, BlockPos pos) { return new BlockSession(client, pos, true); }

    private CompoundTag readClient() {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        try { return BlockData.encode(level.getBlockState(pos), blockEntity, level.registryAccess()); }
        // Some client-side block entities cannot save; the block state alone is still useful.
        catch (RuntimeException ex) { return BlockData.encode(level.getBlockState(pos), null, level.registryAccess()); }
    }

    /** True when the copy may be changed: local world, server data loaded, no sync in flight. */
    public boolean writable() { return editable && loaded && !syncing; }

    public void edit(CompoundTag next) {
        if (!writable()) return;
        if (!next.equals(document)) {
            undo.push(document);
            if (undo.size() > HISTORY_LIMIT) undo.removeLast();
            redo.clear();
        }
        document = next.copy(); revision++; status = ""; refresh();
    }
    public boolean canUndo() { return writable() && !undo.isEmpty(); }
    public boolean canRedo() { return writable() && !redo.isEmpty(); }
    public void undo() { step(undo, redo, "已撤销"); }
    public void redo() { step(redo, undo, "已重做"); }
    private void step(Deque<CompoundTag> from, Deque<CompoundTag> to, String message) {
        if (!writable() || from.isEmpty()) return;
        to.push(document); document = from.pop(); revision++; status = message; refresh();
    }

    private void refresh() {
        try { preview = BlockData.decodeState(document); error = ""; }
        catch (RuntimeException ex) { preview = null; error = ex.getMessage() == null ? ex.toString() : ex.getMessage(); }
    }

    public CompoundTag blockEntity() { return document.get(BlockData.BLOCK_ENTITY) instanceof CompoundTag c ? c : null; }

    private ServerLevel serverLevel() {
        ServerLevel target = server.getLevel(dimension);
        if (target == null) throw new IllegalStateException("维度不可用：" + dimension.identifier());
        if (!target.isLoaded(pos)) throw new IllegalStateException("方块所在区块未加载");
        return target;
    }

    public void tick() {
        if (client.player == null || client.level != level) { valid = false; changed = true; return; }
        if (!editable) {
            observed = readClient(); changed = !observed.equals(baseline);
            return;
        }
        poll();
    }

    private void poll() {
        if (pollPending || syncing || client.getSingleplayerServer() != server) return;
        pollPending = true;
        server.execute(() -> {
            CompoundTag data = null; String failure = null;
            try {
                ServerLevel target = serverLevel();
                data = BlockData.encode(target.getBlockState(pos), target.getBlockEntity(pos), target.registryAccess());
            } catch (RuntimeException ex) { failure = ex.getMessage(); }
            CompoundTag result = data; String reason = failure;
            client.execute(() -> {
                pollPending = false; valid = reason == null;
                if (result != null && !loaded) {
                    document = result.copy(); baseline = result.copy(); observed = result;
                    loaded = true; changed = false; revision++; refresh();
                } else if (result != null) { observed = result; changed = !observed.equals(baseline); }
                else changed = true;
            });
        });
    }

    public void sync(Consumer<Boolean> done) {
        if (!writable() || client.getSingleplayerServer() != server) return;
        if (!error.isEmpty()) { status = "同步失败：" + error; done.accept(false); return; }
        if (document.equals(baseline)) { status = "没有待同步的修改"; done.accept(false); return; }
        if (client.level != level || client.player == null) { status = "同步失败：已离开该世界或维度"; done.accept(false); return; }
        CompoundTag requested = document.copy();
        boolean neighbours = neighborUpdates;
        syncing = true; status = "同步中…";
        server.execute(() -> {
            CompoundTag actual = null; String failure = null; List<String> ignored = List.of();
            try {
                ServerLevel target = serverLevel();
                BlockState next = BlockData.decodeState(requested);
                BlockState current = target.getBlockState(pos);
                if (!current.equals(next)) {
                    // Replacing a container must not spill its contents; the document is the source of truth.
                    BlockEntity old = target.getBlockEntity(pos);
                    if (old instanceof Clearable clearable && !current.is(next.getBlock())) clearable.clearContent();
                    int flags = neighbours ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
                    if (!target.setBlock(pos, next, flags) && !target.getBlockState(pos).equals(next))
                        throw new IllegalStateException("世界拒绝放置该方块（可能超出建筑高度）");
                }
                CompoundTag data = requested.get(BlockData.BLOCK_ENTITY) instanceof CompoundTag c ? c : null;
                // A changed block type gets a fresh block entity; old data is only applied when the type is unchanged.
                if (data != null && current.is(next.getBlock())) {
                    BlockEntity blockEntity = target.getBlockEntity(pos);
                    if (blockEntity == null) throw new IllegalStateException("该方块没有方块实体，无法写入 block_entity");
                    blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, target.registryAccess(), data));
                    blockEntity.setChanged();
                    BlockState placed = target.getBlockState(pos);
                    target.sendBlockUpdated(pos, placed, placed, Block.UPDATE_ALL);
                }
                actual = BlockData.encode(target.getBlockState(pos), target.getBlockEntity(pos), target.registryAccess());
                ignored = differences(next, requested, actual, current.is(next.getBlock()));
            } catch (RuntimeException ex) { failure = ex.getMessage() == null ? ex.toString() : ex.getMessage(); }
            CompoundTag result = actual; String reason = failure; List<String> notes = ignored;
            client.execute(() -> {
                syncing = false;
                if (result != null) {
                    // Keep undo history: undoing after a sync returns to the pre-edit copy, which can be synced again.
                    document = result.copy(); baseline = result.copy(); observed = result.copy();
                    changed = false; valid = true; revision++; refresh();
                    status = notes.isEmpty() ? "同步成功" : "同步成功，但以下数据被游戏调整或忽略：" + String.join("、", notes);
                    done.accept(true);
                } else { status = "同步失败：" + reason; done.accept(false); }
            });
        });
    }

    /** Honest feedback: name every requested field that did not survive the write. */
    private static List<String> differences(BlockState next, CompoundTag requested, CompoundTag actual, boolean sameBlock) {
        List<String> notes = new ArrayList<>();
        // Compare normalised states, so omitted default properties are not reported.
        if (!BlockData.encodeState(next).equals(actual.get(BlockData.STATE))) notes.add("方块状态");
        if (sameBlock && requested.get(BlockData.BLOCK_ENTITY) instanceof CompoundTag wanted && actual.get(BlockData.BLOCK_ENTITY) instanceof CompoundTag got) {
            for (String key : wanted.keySet()) if (!Objects.equals(wanted.get(key), got.get(key))) notes.add("block_entity." + key);
        }
        return notes;
    }

    public void close() {
        client.setScreen(null);
        BlockMakerClient.activeSession = null;
    }
}
