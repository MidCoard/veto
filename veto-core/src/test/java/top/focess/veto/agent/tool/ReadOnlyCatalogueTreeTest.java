package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DefaultQualifier(NonNull.class)
class ReadOnlyCatalogueTreeTest {
    @Test
    void selectedFileChangeAndExpiredGrantAreRejected(@TempDir @NonNull Path root)
            throws Exception {
        Path file = root.resolve("SKILL.md");
        Files.writeString(file, "one");
        var active = new AtomicBoolean(true);
        var tree =
                new ReadOnlyCatalogueTree(
                        List.of(root),
                        () -> {
                            if (!active.get()) throw new SecurityException("Expired");
                        });
        var selected = tree.files("", "SKILL.md").getFirst();
        assertEquals("one", selected.read());
        Files.writeString(file, "changed");
        assertThrows(SecurityException.class, selected::read);
        active.set(false);
        assertThrows(SecurityException.class, () -> tree.files("", "SKILL.md"));
    }

    @Test
    void traversalAndOversizedContentAreRejected(@TempDir @NonNull Path root) throws Exception {
        var tree = new ReadOnlyCatalogueTree(List.of(root), () -> {});
        assertThrows(SecurityException.class, () -> tree.files("../outside", "SKILL.md"));
        Files.writeString(root.resolve("SKILL.md"), "x".repeat(262145));
        assertThrows(java.io.IOException.class, () -> tree.files("", "SKILL.md"));
    }
}
