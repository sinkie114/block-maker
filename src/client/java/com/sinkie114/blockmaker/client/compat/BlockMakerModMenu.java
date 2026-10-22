package com.sinkie114.blockmaker.client.compat;

import com.sinkie114.blockmaker.client.BlockMakerKeysScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Only loaded when Mod Menu is installed; the mod itself never requires it. */
public final class BlockMakerModMenu implements ModMenuApi {
    @Override public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return BlockMakerKeysScreen::new;
    }
}
