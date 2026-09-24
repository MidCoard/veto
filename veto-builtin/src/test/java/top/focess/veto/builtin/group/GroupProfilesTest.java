package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Production role policy replaces the removed, unused LlmLeader parser's tests. */
class GroupProfilesTest {
    @Test
    void memoryEligibilityRemainsFeatureOwnedAfterGenericCapabilityMigration() {
        try (var fixture = new GroupTestHost()) {
            var names = Set.of("recall_memory", "write_memory", "forget_memory");
            var tools =
                    names.stream()
                            .map(
                                    name ->
                                            new AgentConfiguration.Tool(
                                                    name,
                                                    ToolCapability.PLUGIN_LOCAL,
                                                    "top.focess.builtin",
                                                    name))
                            .toList();
            var base =
                    new AgentProfile(
                            "agent", "description", "STANDALONE", names, "DEFAULT", null, Map.of());
            var context =
                    new AgentConfiguration.Context(
                            "owner", fixture.scope, fixture.agents, "leader", base, tools, "");
            assertEquals(names, GroupProfiles.standalone(context).tools());
            assertEquals(
                    Set.of("recall_memory"),
                    GroupProfiles.role(context, "mate", "work", false).tools());
            assertTrue(GroupProfiles.role(context, "leader", "work", true).tools().isEmpty());
        }
    }

    @Test
    void rolesKeepAgentControlAndCredentialImportWithinTheirOwnBoundaries() {
        try (var fixture = new GroupTestHost()) {
            var tools =
                    List.of(
                            new AgentConfiguration.Tool("read", ToolCapability.WORKSPACE_READ),
                            new AgentConfiguration.Tool("think", ToolCapability.LOOP_CONTROL),
                            new AgentConfiguration.Tool(
                                    "create_group",
                                    ToolCapability.PLUGIN_LOCAL,
                                    "top.focess.builtin",
                                    "create_group"),
                            new AgentConfiguration.Tool(
                                    "post_message",
                                    ToolCapability.PLUGIN_LOCAL,
                                    "top.focess.builtin",
                                    "post_message"),
                            new AgentConfiguration.Tool(
                                    "unclassified", ToolCapability.AGENT_CONTROL),
                            new AgentConfiguration.Tool(
                                    "import_secret", ToolCapability.PRIVILEGED));
            var base =
                    new AgentProfile(
                            "name",
                            "description",
                            "STANDALONE",
                            Set.of(
                                    "read",
                                    "think",
                                    "create_group",
                                    "post_message",
                                    "unclassified",
                                    "import_secret"),
                            "DEFAULT",
                            null,
                            Map.of());
            var context =
                    new AgentConfiguration.Context(
                            "owner", fixture.scope, fixture.agents, "leader", base, tools, "");
            assertEquals(
                    Set.of("read", "think", "create_group", "import_secret"),
                    GroupProfiles.standalone(context).tools());
            assertEquals(
                    Set.of("read", "think", "import_secret"),
                    GroupProfiles.role(context, "mate", "work", false).tools());
            assertEquals(
                    Set.of("read", "think", "post_message"),
                    GroupProfiles.role(context, "leader", "work", true).tools());
        }
    }

    @Test
    void aliasesUseContributionIdentityAndPreserveOtherPluginsTools() throws Exception {
        for (var prefix : List.of("", "plugin_top_focess_builtin__", "custom_")) {
            try (var fixture = new GroupTestHost()) {
                String create = prefix + "create_group", post = prefix + "post_message";
                List<AgentConfiguration.@NonNull Tool> tools =
                        List.of(
                                new AgentConfiguration.Tool(
                                        create,
                                        ToolCapability.PLUGIN_LOCAL,
                                        "top.focess.builtin",
                                        "create_group"),
                                new AgentConfiguration.Tool(
                                        post,
                                        ToolCapability.PLUGIN_LOCAL,
                                        "top.focess.builtin",
                                        "post_message"),
                                new AgentConfiguration.Tool(
                                        "foreign_post",
                                        ToolCapability.PLUGIN_LOCAL,
                                        "other.plugin",
                                        "post_message"));
                var base =
                        new AgentProfile(
                                "name",
                                "description",
                                "STANDALONE",
                                Set.of(create, post, "foreign_post"),
                                null,
                                null,
                                Map.of());
                var context =
                        new AgentConfiguration.Context(
                                "owner",
                                fixture.scope,
                                fixture.agents,
                                "leader",
                                base,
                                tools,
                                "task");
                assertEquals(
                        Set.of(create, "foreign_post"), GroupProfiles.standalone(context).tools());
                assertEquals(
                        Set.of(post, "foreign_post"),
                        GroupProfiles.role(context, "leader", "work", true).tools());
                assertEquals(
                        Set.of(create, "foreign_post"),
                        GroupTestHost.required(fixture.runtime.configure(context))
                                .profile()
                                .tools());
            }
        }
    }

    @Test
    void configuredRoleTiersGuidanceAndTickRemainPluginOwned() {
        var config =
                new GroupConfig(
                        Map.of(
                                "group-tick-interval-ms", new JsonValue.StringValue("275"),
                                "group-leader-tier", new JsonValue.StringValue("MID"),
                                "group-mate-tier", new JsonValue.StringValue("LOW"),
                                "group-leader-guidance",
                                        new JsonValue.StringValue("Review evidence carefully"),
                                "group-mate-guidance",
                                        new JsonValue.StringValue("Record verification results")));
        assertEquals(275, config.tickMillis());
        assertEquals(1000, GroupConfig.defaults().tickMillis());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroupConfig(
                                        Map.of(
                                                "group-tick-interval-ms",
                                                new JsonValue.StringValue("0")))
                                .tickMillis());
        try (var fixture = new GroupTestHost()) {
            var leader =
                    GroupProfiles.role(
                            fixture.configuration, "lead", "coordinate", true, config, null);
            var mate =
                    GroupProfiles.role(
                            fixture.configuration, "reviewer", "review", false, config, null);
            assertEquals("MID", leader.tier());
            assertEquals("LOW", mate.tier());
            assertEquals(
                    "builtin-leader-profile", GroupTestHost.required(leader.prompt()).resource());
            assertEquals("builtin-mate-profile", GroupTestHost.required(mate.prompt()).resource());
            assertEquals(
                    new JsonValue.StringValue("Review evidence carefully"),
                    GroupTestHost.required(leader.prompt()).data().values().get("guidance"));
            assertEquals(
                    new JsonValue.StringValue("Record verification results"),
                    GroupTestHost.required(mate.prompt()).data().values().get("guidance"));
        }
    }

    @Test
    void legacySkillsetsOverrideIndividualFieldsButFreeResponsibilityDoesNotSelectConfiguration() {
        var config =
                new GroupConfig(
                        Map.of(
                                "group-mate-tier", new JsonValue.StringValue("LOW"),
                                "group-mate-guidance", new JsonValue.StringValue("Global guidance"),
                                "group-skillset.coder.tier", new JsonValue.StringValue("TOP"),
                                "group-skillset.reviewer.guidance",
                                        new JsonValue.StringValue("Legacy review guidance")));
        try (var fixture = new GroupTestHost()) {
            var coder =
                    GroupProfiles.role(
                            fixture.configuration, "old coder", "coding", false, config, "coder");
            var reviewer =
                    GroupProfiles.role(
                            fixture.configuration,
                            "old reviewer",
                            "review",
                            false,
                            config,
                            "reviewer");
            var named =
                    GroupProfiles.role(
                            fixture.configuration, "new mate", "coder", false, config, null);
            assertEquals("TOP", coder.tier());
            assertEquals(
                    new JsonValue.StringValue("Global guidance"),
                    GroupTestHost.required(coder.prompt()).data().values().get("guidance"));
            assertEquals("LOW", reviewer.tier());
            assertEquals(
                    new JsonValue.StringValue("Legacy review guidance"),
                    GroupTestHost.required(reviewer.prompt()).data().values().get("guidance"));
            assertEquals("LOW", named.tier());
            assertEquals(
                    new JsonValue.StringValue("Global guidance"),
                    GroupTestHost.required(named.prompt()).data().values().get("guidance"));
        }
    }
}
