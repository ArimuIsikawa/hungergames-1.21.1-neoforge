package ru.studhub.hungergames.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import ru.studhub.hungergames.player.PlayerRecord;

/** World-local journal, written before transfers and removed after the player save. */
public final class EventJournal {
    public static final class Data {
        public int schemaVersion = 1;
        public String lastScheduledDate = "";
        public String eventId = "";
        public int startingPlayers;
        public Map<String, PlayerRecord> players = new LinkedHashMap<>();
    }

    public final Data data;
    private final Path path;

    public EventJournal(MinecraftServer server) throws IOException {
        path = server.getWorldPath(LevelResource.ROOT).resolve("data/hungergames/event.json");
        if (Files.exists(path)) {
            JsonObject object = JsonFiles.read(path, JsonObject.class);
            if (!object.has("schemaVersion") || !object.has("players")) throw new IOException("Incomplete journal: " + path);
            data = JsonFiles.GSON.fromJson(object, Data.class);
        } else data = new Data();
        if (data.schemaVersion != 1 || data.players == null) throw new IOException("Unsupported or corrupt journal: " + path);
        for (var entry : data.players.entrySet()) {
            PlayerRecord record = entry.getValue();
            if (record == null || record.origin == null || record.token == null || record.gameMode == null
                    || !entry.getKey().equals(record.uuid)) throw new IOException("Invalid player record: " + path);
            record.origin.validate();
            UUID.fromString(record.uuid);
            record.role = PlayerRecord.Role.RETURNING;
        }
        save();
    }

    public void save() {
        try {
            JsonFiles.write(path, data);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить журнал Hunger Games; перенос отменён.", e);
        }
    }
}
