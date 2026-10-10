package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

/** Generates and verifies the browser binding from the actual Java wire types. */
public class FrontendBindingsTest {
    private static final @NonNull ObjectMapper JSON = new ObjectMapper();
    private static final @NonNull JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Used by generateFrontendBindings; runtime libraries do not contain this build utility. */
    public static void main(@NonNull String @NonNull [] args) throws Exception {
        Path target = Path.of(args[0]);
        Files.writeString(target.resolve("schema.ts"), schemaSource(), StandardCharsets.UTF_8);
        Files.writeString(target.resolve("fixtures.json"), fixtureSource(), StandardCharsets.UTF_8);
    }

    @Test
    void browserBindingsMatchJavaContracts() throws Exception {
        assertEquals(schemaSource(), Files.readString(Path.of("frontend/schema.ts")));
        assertEquals(fixtureSource(), Files.readString(Path.of("frontend/fixtures.json")));
    }

    private static @NonNull String schemaSource() throws Exception {
        ObjectNode frames = NODES.objectNode();
        var types = Frame.class.getAnnotation(JsonSubTypes.class);
        if (types == null) throw new IllegalStateException("Missing frame subtype metadata");
        for (var type : types.value()) {
            frames.set(type.name(), fields(type.value()));
        }
        return "// Generated from Java protocol contracts; run :veto-protocol:generateFrontendBindings.\n"
                + "export const protocolVersion = "
                + Frame.PROTOCOL_VERSION
                + ";\n"
                + "export const frameSchemas = "
                + JSON.writeValueAsString(frames)
                + " as const;\n";
    }

    private static @NonNull ObjectNode fields(@NonNull Class<?> type) throws Exception {
        ObjectNode fields = NODES.objectNode();
        var components = type.getRecordComponents();
        if (components != null) {
            for (var component : components) {
                fields.set(
                        component.getName(),
                        shape(component.getAccessor().getAnnotatedReturnType()));
            }
        } else if (type == DAGPayload.class) {
            for (var field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fields.set(field.getName(), shape(field.getAnnotatedType()));
                }
            }
        } else {
            throw new IllegalArgumentException("No wire fields for " + type.getName());
        }
        return fields;
    }

    private static @NonNull JsonNode shape(@NonNull AnnotatedType type) throws Exception {
        var raw = type.getType();
        JsonNode shape;
        boolean primitive = false;
        if (type instanceof AnnotatedParameterizedType parameterized
                && raw instanceof ParameterizedType generic) {
            var arguments = parameterized.getAnnotatedActualTypeArguments();
            if (generic.getRawType() == Map.class) {
                shape = NODES.objectNode().set("map", shape(arguments[1]));
            } else {
                shape = NODES.objectNode().set("array", shape(arguments[0]));
            }
        } else if (raw instanceof Class<?> clazz) {
            primitive = clazz.isPrimitive();
            if (clazz == String.class
                    || clazz == UUID.class
                    || clazz == Instant.class
                    || clazz == Version.class) {
                shape = NODES.textNode("string");
            } else if (clazz == boolean.class) {
                shape = NODES.textNode("boolean");
            } else if (clazz == int.class || clazz == long.class) {
                shape = NODES.textNode("integer");
            } else if (clazz.isEnum()) {
                var names = NODES.arrayNode();
                var constants = clazz.getEnumConstants();
                if (constants == null) throw new IllegalStateException("Missing enum constants");
                for (var constant : constants) {
                    names.add(constant.toString());
                }
                shape = NODES.objectNode().set("enum", names);
            } else if (clazz == JsonNode.class || clazz == Object.class) {
                shape = NODES.textNode("json");
            } else {
                shape = NODES.objectNode().set("object", fields(clazz));
            }
        } else {
            throw new IllegalArgumentException("Unsupported wire type " + raw);
        }
        return primitive || type.isAnnotationPresent(NonNull.class)
                ? shape
                : NODES.objectNode().set("nullable", shape);
    }

    private static @NonNull String fixtureSource() throws Exception {
        var timestamp = Instant.parse("2026-10-10T00:00:00Z");
        var session = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Frame[] frames = {
            new Frame.Welcome(Frame.PROTOCOL_VERSION, 0, Version.parse("1.0.100")),
            new Frame.HeartbeatAck(7, timestamp),
            new Frame.Error("Invalid client frame", 8),
            new EventFrame(
                    session, 1, timestamp, EventFrame.Kind.NOTICE, "Server guidance", Map.of()),
            new EventFrame(
                    session, 2, timestamp, EventFrame.Kind.ASSISTANT_MESSAGE, "Hello", Map.of()),
            new Frame.Done(Map.of("turnNumber", 1), null)
        };
        var fixtures = NODES.arrayNode();
        for (var frame : frames) fixtures.add(JSON.readTree(FrameCodec.encodeString(frame)));
        return JSON.writeValueAsString(fixtures) + "\n";
    }
}
