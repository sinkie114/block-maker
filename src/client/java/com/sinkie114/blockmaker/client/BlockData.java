package com.sinkie114.blockmaker.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;

/**
 * Editor document: {@code {state:{Name:"minecraft:chest",Properties:{facing:"north"}}, block_entity:{...}}}.
 * The state uses vanilla's block-state NBT layout; {@code block_entity} is the full block-entity save without x/y/z.
 */
public final class BlockData {
    private BlockData() {}
    public static final String STATE = "state", BLOCK_ENTITY = "block_entity";

    public static CompoundTag encode(BlockState state, BlockEntity blockEntity, HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        root.put(STATE, encodeState(state));
        if (blockEntity != null) root.put(BLOCK_ENTITY, encodeBlockEntity(blockEntity, registries));
        return root;
    }

    public static CompoundTag encodeState(BlockState state) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        CompoundTag properties = new CompoundTag();
        for (Property<?> property : state.getProperties()) properties.putString(property.getName(), valueName(state, property));
        if (!properties.isEmpty()) tag.put("Properties", properties);
        return tag;
    }

    /** The block position is implied by the edited location, so it never appears in the document. */
    public static CompoundTag encodeBlockEntity(BlockEntity blockEntity, HolderLookup.Provider registries) {
        CompoundTag tag = blockEntity.saveWithFullMetadata(registries);
        tag.remove("x"); tag.remove("y"); tag.remove("z");
        return tag;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    public static CompoundTag stateTag(CompoundTag document) {
        Tag state = document.get(STATE);
        if (!(state instanceof CompoundTag compound)) throw new IllegalArgumentException("state 必须是 Compound，例如 {Name:\"minecraft:stone\"}");
        return compound;
    }

    /** For display only: never throws, so a broken pasted document can still be shown and fixed. */
    public static CompoundTag stateOrEmpty(CompoundTag document) {
        return document.get(STATE) instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    /** Strict decoding: unknown blocks, properties or values are errors, never silently replaced by defaults. */
    public static BlockState decodeState(CompoundTag document) {
        for (String key : document.keySet()) if (!key.equals(STATE) && !key.equals(BLOCK_ENTITY))
            throw new IllegalArgumentException("未知根字段 " + key + "；只支持 state 和 block_entity");
        Tag blockEntity = document.get(BLOCK_ENTITY);
        if (blockEntity != null && !(blockEntity instanceof CompoundTag)) throw new IllegalArgumentException("block_entity 必须是 Compound");
        CompoundTag tag = stateTag(document);
        for (String key : tag.keySet()) if (!key.equals("Name") && !key.equals("Properties"))
            throw new IllegalArgumentException("state 只支持 Name 和 Properties，发现 " + key);
        Block block = block(tag.getString("Name").orElseThrow(() -> new IllegalArgumentException("state.Name 必须是方块 ID 字符串")));
        BlockState state = block.defaultBlockState();
        Tag rawProperties = tag.get("Properties");
        if (rawProperties != null && !(rawProperties instanceof CompoundTag)) throw new IllegalArgumentException("Properties 必须是 Compound");
        CompoundTag properties = rawProperties == null ? new CompoundTag() : (CompoundTag) rawProperties;
        for (String key : properties.keySet()) {
            Property<?> property = block.getStateDefinition().getProperty(key);
            if (property == null) throw new IllegalArgumentException(BuiltInRegistries.BLOCK.getKey(block) + " 没有属性 " + key
                    + "；可用属性：" + String.join(", ", propertyNames(block)));
            state = with(state, property, text(properties.get(key), key));
        }
        if (blockEntity instanceof CompoundTag data) checkBlockEntity(state, data);
        return state;
    }

    /**
     * block_entity must belong to the target block: a block without a block entity cannot take data, and a mismatched
     * {@code id} (for example chest data on a furnace) would silently lose fields, so both are errors before any write.
     */
    public static void checkBlockEntity(BlockState state, CompoundTag data) {
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!state.hasBlockEntity() || !(state.getBlock() instanceof EntityBlock entityBlock))
            throw new IllegalArgumentException(name + " 没有方块实体，请删除 block_entity");
        String id = data.getStringOr("id", "");
        if (id.isEmpty()) return;
        Identifier wanted = Identifier.tryParse(id);
        BlockEntity probe = entityBlock.newBlockEntity(BlockPos.ZERO, state);
        Identifier actual = probe == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(probe.getType());
        if (actual != null && !actual.equals(wanted))
            throw new IllegalArgumentException("block_entity.id 是 " + id + "，但 " + name + " 的方块实体是 " + actual);
    }

    /** {@code /setblock x y z id[props]{data}}; id and coordinates are left out of the data, the command supplies them. */
    public static String setblockCommand(BlockPos pos, CompoundTag document) {
        CompoundTag state = stateTag(document);
        StringBuilder command = new StringBuilder("/setblock ").append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ())
                .append(' ').append(state.getStringOr("Name", "minecraft:air"));
        CompoundTag properties = state.getCompoundOrEmpty("Properties");
        if (!properties.isEmpty()) {
            StringJoiner joined = new StringJoiner(",", "[", "]");
            for (String key : properties.keySet()) joined.add(key + "=" + text(properties.get(key), key));
            command.append(joined);
        }
        if (document.get(BLOCK_ENTITY) instanceof CompoundTag data) {
            CompoundTag copy = data.copy(); copy.remove("id");
            if (!copy.isEmpty()) command.append(copy);
        }
        return command.toString();
    }

    /** Vanilla writes values as strings; numbers and booleans typed by hand are accepted too. */
    private static String text(Tag tag, String key) {
        if (tag instanceof StringTag string) return string.value();
        if (tag instanceof NumericTag numeric) return tag instanceof net.minecraft.nbt.ByteTag && (numeric.longValue() == 0 || numeric.longValue() == 1)
                ? Boolean.toString(numeric.longValue() == 1) : Long.toString(numeric.longValue());
        throw new IllegalArgumentException("属性 " + key + " 的值必须是字符串");
    }

    public static Block block(String name) {
        Identifier id = Identifier.tryParse(name.trim());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("未注册的方块：" + name);
        return BuiltInRegistries.BLOCK.get(id).orElseThrow().value();
    }

    public static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        T parsed = property.getValue(value).orElseThrow(() -> new IllegalArgumentException("属性 " + property.getName() + " 不接受值 " + value
                + "；可选：" + String.join(", ", values(property))));
        return state.setValue(property, parsed);
    }

    public static <T extends Comparable<T>> List<String> values(Property<T> property) {
        List<String> names = new ArrayList<>();
        for (T value : property.getPossibleValues()) names.add(property.getName(value));
        return names;
    }

    public static List<String> propertyNames(Block block) {
        return block.getStateDefinition().getProperties().stream().map(p -> p.getName()).toList();
    }

    /** Switch block type while keeping every property the new block also has with a valid value. */
    public static BlockState convert(BlockState from, Block to) {
        BlockState result = to.defaultBlockState();
        for (Property<?> property : from.getProperties()) {
            Property<?> target = to.getStateDefinition().getProperty(property.getName());
            if (target == null) continue;
            String value = valueName(from, property);
            if (values(target).contains(value)) result = with(result, target, value);
        }
        return result;
    }

    public static String summary(CompoundTag blockEntity) {
        return blockEntity.getStringOr("id", "方块实体") + "（" + blockEntity.size() + " 个字段）";
    }
}
