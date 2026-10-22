package com.sinkie114.blockmaker.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

public class BlockMakerClient implements ClientModInitializer {
    public static KeyMapping OPEN;
    public static BlockSession activeSession;

    @Override public void onInitializeClient() {
        OPEN = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.block-maker.open", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F7, KeyMapping.Category.register(Identifier.parse("block-maker:editor"))));
        ClientTickEvents.END_CLIENT_TICK.register(BlockMakerClient::tick);
    }

    private static void tick(Minecraft client) {
        if (activeSession != null) {
            if (client.screen instanceof BlockLayer layer && layer.session() == activeSession) activeSession.tick();
            else activeSession = null;
        }
        if (client.screen != null || client.player == null || client.level == null) {
            while (OPEN.consumeClick()) { }
            return;
        }
        while (OPEN.consumeClick()) {
            // The vanilla crosshair target honours reach, fluids and the current camera.
            if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) open(client, hit.getBlockPos(), false);
            else client.player.displayClientMessage(Component.literal("请对准一个方块"), true);
            break;
        }
    }

    /** Opens the editor; remote worlds are always read-only, readOnly forces that path in singleplayer too. */
    public static void open(Minecraft client, BlockPos pos, boolean readOnly) {
        try {
            BlockSession session = readOnly ? BlockSession.readOnly(client, pos) : BlockSession.open(client, pos);
            activeSession = session;
            client.setScreen(new BlockEditorScreen(session));
        } catch (RuntimeException ex) {
            if (client.player != null) client.player.displayClientMessage(Component.literal("无法打开方块数据：" + ex.getMessage()), true);
        }
    }
}
