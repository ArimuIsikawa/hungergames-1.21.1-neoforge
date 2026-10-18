package ru.studhub.hungergames.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFilesTest {
    @TempDir Path directory;
    record Value(String text, int number) {}

    @Test void writesUtf8AndReplacesExistingJournal() throws Exception {
        Path path = directory.resolve("nested/event.json");
        JsonFiles.write(path, new Value("Возврат", 1));
        JsonFiles.write(path, new Value("Награда", 2));
        assertEquals(new Value("Награда", 2), JsonFiles.read(path, Value.class));
        assertFalse(Files.exists(path.resolveSibling("event.json.tmp")));
    }

    @Test void malformedAndEmptyJournalFailInsteadOfDiscardingData() throws Exception {
        Path path = directory.resolve("event.json");
        Files.writeString(path, "{bad");
        assertThrows(java.io.IOException.class, () -> JsonFiles.read(path, Value.class));
        Files.writeString(path, "null");
        assertThrows(java.io.IOException.class, () -> JsonFiles.read(path, Value.class));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void retriesBriefWindowsReplacementLock() throws Exception {
        Path path = directory.resolve("locked.json");
        JsonFiles.write(path, new Value("before", 1));
        var lock = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
        Thread release = Thread.ofPlatform().start(() -> {
            try { Thread.sleep(10); lock.close(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        try { JsonFiles.write(path, new Value("after", 2)); }
        finally { lock.close(); release.join(); }
        assertEquals(new Value("after", 2), JsonFiles.read(path, Value.class));
    }
}
