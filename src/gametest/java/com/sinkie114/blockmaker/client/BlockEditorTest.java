package com.sinkie114.blockmaker.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

/** Runs in a disposable world through the real key binding, screens and integrated-server writes. */
public final class BlockEditorTest implements FabricClientGameTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            BlockPos[] positions = server.computeOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                BlockPos stairs = player.blockPosition().east().above(), chest = player.blockPosition().north(3);
                player.level().setBlockAndUpdate(stairs, Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
                player.level().setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
                ((ChestBlockEntity) player.level().getBlockEntity(chest)).setItem(0, new ItemStack(Items.STONE, 3));
                return new BlockPos[]{stairs, chest};
            });
            BlockPos stairs = positions[0], chest = positions[1];
            context.waitFor(c -> c.level.getBlockState(stairs).is(Blocks.OAK_STAIRS) && c.level.getBlockState(chest).is(Blocks.CHEST));
            context.runOnClient(c -> { c.player.setYRot(-90); c.player.setXRot(0); });
            context.waitFor(c -> c.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(stairs));
            context.getInput().pressKey(GLFW.GLFW_KEY_F7);
            context.waitForScreen(BlockEditorScreen.class);
            context.waitFor(c -> session(c).loaded);
            testStairs(context, server, stairs);
            testChest(context, server, chest);
            testReadOnly(context, stairs);
        }
    }

    private static void testStairs(ClientGameTestContext context, TestServerContext server, BlockPos pos) {
        context.runOnClient(c -> {
            var session = session(c);
            check(session.editable, "Singleplayer is editable");
            check(session.preview.is(Blocks.OAK_STAIRS) && session.preview.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.NORTH, "Preview is the real block");
            check(session.blockEntity() == null, "Stairs have no block entity");
        });
        context.takeScreenshot("block-maker-stairs");
        click(context, "north");
        context.waitForScreen(OptionPickerScreen.class);
        click(context, "east");
        context.waitForScreen(BlockEditorScreen.class);
        context.runOnClient(c -> check(BlockData.stateTag(session(c).document).getCompoundOrEmpty("Properties").getStringOr("facing", "").equals("east"),
                "Picker writes the property into the copy"));
        server.runOnServer(s -> check(s.overworld().getBlockState(pos).getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.NORTH, "Editing never writes the world"));
        click(context, "同步");
        context.waitFor(c -> session(c).status.startsWith("同步成功"));
        server.runOnServer(s -> check(s.overworld().getBlockState(pos).getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST, "Sync writes the world"));

        context.runOnClient(c -> {
            var session = session(c);
            session.undo();
            check(session.preview.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.NORTH, "Undo restores the copy");
            session.redo();
            CompoundTag bad = session.document.copy();
            BlockData.stateTag(bad).getCompoundOrEmpty("Properties").putString("facing", "up");
            session.edit(bad);
            check(!session.error.isEmpty(), "Invalid property values are reported, not replaced by defaults");
            session.sync(ok -> check(!ok, "Invalid data never syncs"));
            check(session.status.startsWith("同步失败"), "Failed sync is reported: " + session.status);
            session.undo();
            check(session.error.isEmpty(), "Undo leaves the invalid state");
            session.close();
        });
        context.waitTicks(2);
        server.runOnServer(s -> check(s.overworld().getBlockState(pos).getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST, "Undo never writes the world"));
    }

    private static void testChest(ClientGameTestContext context, TestServerContext server, BlockPos pos) {
        context.runOnClient(c -> BlockMakerClient.open(c, pos, false));
        context.waitForScreen(BlockEditorScreen.class);
        context.waitFor(c -> session(c).loaded);
        context.runOnClient(c -> {
            var session = session(c);
            // Clients never receive chest contents; the editable copy must come from the server.
            check(session.blockEntity() != null && session.blockEntity().getListOrEmpty("Items").size() == 1, "Chest copy is the server's data: " + session.document);
            CompoundTag next = session.document.copy();
            CompoundTag data = next.getCompoundOrEmpty(BlockData.BLOCK_ENTITY).copy();
            try { data.put("Items", TagParser.parseCompoundFully("{Items:[{Slot:0b,id:\"minecraft:diamond\",count:5}]}").get("Items")); }
            catch (Exception ex) { throw new AssertionError(ex); }
            next.put(BlockData.BLOCK_ENTITY, data);
            session.edit(next);
        });
        click(context, "同步");
        context.waitFor(c -> session(c).status.startsWith("同步"));
        context.runOnClient(c -> check(session(c).status.startsWith("同步成功"), "Chest sync: " + session(c).status));
        server.runOnServer(s -> {
            ItemStack stack = ((ChestBlockEntity) s.overworld().getBlockEntity(pos)).getItem(0);
            check(stack.is(Items.DIAMOND) && stack.getCount() == 5, "Block entity data reaches the real chest: " + stack);
        });
        context.runOnClient(c -> {
            var session = session(c);
            CompoundTag next = session.document.copy();
            next.put(BlockData.STATE, BlockData.encodeState(Blocks.STONE.defaultBlockState()));
            next.remove(BlockData.BLOCK_ENTITY);
            session.edit(next);
        });
        click(context, "同步");
        context.waitFor(c -> session(c).status.startsWith("同步"));
        server.runOnServer(s -> {
            check(s.overworld().getBlockState(pos).is(Blocks.STONE), "Block type can be replaced");
            check(s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3)).isEmpty(), "Replacing a container must not spill items");
        });
        context.runOnClient(c -> c.screen.onClose());
        context.waitTicks(2);
    }

    private static void testReadOnly(ClientGameTestContext context, BlockPos pos) {
        context.runOnClient(c -> BlockMakerClient.open(c, pos, true));
        context.waitForScreen(BlockEditorScreen.class);
        context.runOnClient(c -> {
            var session = session(c);
            check(!session.editable, "Read-only path is not editable");
            check(c.screen.children().stream().noneMatch(w -> w instanceof Button b && b.getMessage().getString().equals("同步")), "Read-only has no sync button");
            CompoundTag before = session.document.copy(), changed = before.copy();
            changed.put(BlockData.STATE, BlockData.encodeState(Blocks.DIRT.defaultBlockState()));
            session.edit(changed);
            check(session.document.equals(before), "Read-only copies cannot be edited");
        });
        context.takeScreenshot("block-maker-read-only");
        context.runOnClient(c -> c.screen.onClose());
        context.waitTicks(2);
    }

    private static BlockSession session(Minecraft c) { return ((BlockLayer) c.screen).session(); }

    private static void click(ClientGameTestContext context, String label) {
        context.runOnClient(c -> {
            Button button = c.screen.children().stream().filter(child -> child instanceof Button b && b.getMessage().getString().equals(label))
                    .map(child -> (Button) child).findFirst().orElseThrow(() -> new AssertionError("Missing button " + label));
            check(button.active, "Button must be enabled: " + label);
            var event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(0, 0));
            c.screen.mouseClicked(event, false);
        });
        context.waitTicks(2);
    }
}
