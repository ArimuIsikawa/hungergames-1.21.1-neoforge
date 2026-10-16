package ru.studhub.hungergames.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;

public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonFiles() {}

    public static <T> T read(Path path, Class<T> type) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T value = GSON.fromJson(reader, type);
            if (value == null) throw new IOException("Empty JSON: " + path);
            return value;
        } catch (RuntimeException e) {
            throw new IOException(path + ": " + e.getMessage(), e);
        }
    }

    public static void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        byte[] bytes = (GSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
