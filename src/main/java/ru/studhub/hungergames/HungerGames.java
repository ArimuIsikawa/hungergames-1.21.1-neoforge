package ru.studhub.hungergames;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(HungerGames.MOD_ID)
public final class HungerGames {
    public static final String MOD_ID = "hungergames";
    public static final Logger LOGGER = LoggerFactory.getLogger(HungerGames.class);

    public HungerGames() {
        LOGGER.info("Hunger Games initialized");
    }
}
