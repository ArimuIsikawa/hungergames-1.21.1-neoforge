package ru.studhub.hungergames;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import ru.studhub.hungergames.event.EventController;
import ru.studhub.hungergames.event.ServerEvents;

@Mod(HungerGames.MOD_ID)
public final class HungerGames {
    public static final String MOD_ID = "hungergames";
    public static final Logger LOGGER = LoggerFactory.getLogger(HungerGames.class);
    private static volatile EventController controller;

    public static EventController controller() { return controller; }

    public static void start(MinecraftServer server) {
        controller = null;
        try { controller = new EventController(server); }
        catch (Exception e) { LOGGER.error("Hunger Games disabled: could not read recovery journal", e); }
    }

    public static void stop() {
        if (controller != null) {
            try { controller.stop(); }
            finally { controller = null; }
        }
    }

    public HungerGames() {
        NeoForge.EVENT_BUS.register(new ServerEvents());
        LOGGER.info("Hunger Games initialized");
    }
}
