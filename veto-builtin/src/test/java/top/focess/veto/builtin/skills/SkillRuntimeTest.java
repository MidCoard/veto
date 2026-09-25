package top.focess.veto.builtin.skills;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.resources.CatalogueAccess;
import top.focess.veto.api.resources.CatalogueTree;
import top.focess.veto.builtin.tools.LoadSkillTool;

class SkillRuntimeTest {
    @Test
    void parsesFrontmatterAndPreservesBodyHashCompatibility() {
        var parsed =
                SkillRuntime.parse(
                                "file",
                                "---\nname: verify\ndescription: Verify safely\n---\n First\n---\nSecond ",
                                "PROJECT")
                        .orElseThrow();
        assertEquals("verify", parsed.name());
        assertEquals("Verify safely", parsed.description());
        assertEquals("First\n\nSecond", parsed.body());
        assertEquals(SkillRuntime.hash(parsed.body()), parsed.bodyHash());
        assertTrue(SkillRuntime.parse("file", "missing frontmatter", "PROJECT").isEmpty());
    }

    @Test
    void projectWinsAndChangedSelectedFileNeverFallsBack() {
        var fixture = new Fixture();
        fixture.personal.body = skill("Personal");
        fixture.project.body = skill("Project");
        assertEquals(
                "Project", fixture.runtime.catalogue(fixture.project).getFirst().description());
        fixture.project.body = skill("Changed");
        assertTrue(fixture.runtime.load("review").isEmpty());
        verify(fixture.host).invocation("load_skill");
    }

    @Test
    void personalWorksAndAnchorSurvivesRuntimeRestart() {
        var fixture = new Fixture();
        fixture.personal.body = skill("Personal");
        assertEquals(Optional.of("Personal instructions"), fixture.runtime.load("review"));
        fixture.personal.body = skill("Changed");
        assertTrue(fixture.newRuntime().load("review").isEmpty());
    }

    @Test
    void legacyMismatchAndClosedRuntimeRefuse() {
        var fixture = new Fixture();
        fixture.project.body = skill("Project");
        fixture.runtime.importLegacy("project-file", SkillRuntime.hash("Old instructions"));
        assertTrue(fixture.runtime.load("review").isEmpty());
        fixture.runtime.close();
        assertTrue(fixture.runtime.catalogue(fixture.project).isEmpty());
        assertThrows(SecurityException.class, () -> fixture.runtime.load("review"));
    }

    @Test
    void emptyCatalogueHidesToolAndDoesNotStoreAnythingDuringPresentation() {
        var fixture = new Fixture();
        assertFalse(new LoadSkillTool(fixture.runtime).describe(fixture.project).available());
        assertTrue(fixture.saved.isEmpty());
    }

    private static String skill(String description) {
        return "---\nname: review\ndescription: "
                + description
                + "\n---\n"
                + description
                + " instructions";
    }

    private static final class Tree implements CatalogueTree {
        final String id;
        String body = "";

        Tree(String id) {
            this.id = id;
        }

        public String identity() {
            return id;
        }

        public List<File> files(String directory, String filename) {
            return body.isEmpty()
                    ? List.of()
                    : List.of(
                            new File() {
                                public String identity() {
                                    return id + "-file";
                                }

                                public String relativePath() {
                                    return "review/SKILL.md";
                                }

                                public String read() {
                                    return body;
                                }
                            });
        }
    }

    private static final class Fixture {
        final Tree personal = new Tree("personal"), project = new Tree("project");
        final PluginHost host = mock(PluginHost.class);
        final PluginStorage storage = mock(PluginStorage.class);
        final PluginStorage.Store store = mock(PluginStorage.Store.class);
        final Map<String, PluginStorage.Entry> saved = new LinkedHashMap<>();
        final CatalogueAccess resources =
                new CatalogueAccess() {
                    public Optional<CatalogueTree> shared(String name) {
                        return Optional.of(personal);
                    }

                    public CatalogueTree workspace() {
                        return project;
                    }
                };
        final SkillRuntime runtime;

        Fixture() {
            when(storage.application()).thenReturn(store);
            when(store.get(anyString()))
                    .thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
            when(store.put(anyString(), isNull(), any()))
                    .thenAnswer(
                            call -> {
                                String key = call.getArgument(0);
                                if (key == null) throw new AssertionError("Missing storage key");
                                if (saved.containsKey(key)) throw new PluginStorage.Conflict();
                                var entry = new PluginStorage.Entry(key, "1", call.getArgument(2));
                                saved.put(key, entry);
                                return entry;
                            });
            runtime = createRuntime(resources, host, storage);
        }

        SkillRuntime newRuntime() {
            return createRuntime(resources, host, storage);
        }

        private static @NonNull SkillRuntime createRuntime(
                @NonNull CatalogueAccess resources,
                @NonNull PluginHost host,
                @NonNull PluginStorage storage) {
            return new SkillRuntime(
                    new PluginContext(
                            new PluginIdentity("test", "1.0.0"),
                            () -> {},
                            () -> {
                                throw new IllegalStateException(
                                        "Plugin context is not bound to a lifecycle owner");
                            },
                            Map.of(
                                    ToolDocs.nonNullClass(CatalogueAccess.class),
                                    resources,
                                    ToolDocs.nonNullClass(PluginHost.class),
                                    host,
                                    ToolDocs.nonNullClass(PluginStorage.class),
                                    storage)),
                    new JsonValue.ObjectValue(Map.of()));
        }
    }
}
