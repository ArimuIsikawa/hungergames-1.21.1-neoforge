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
}
