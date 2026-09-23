package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.plugin.contract.Tool;

/**
 * Compiles annotated Java parameter records into tool definitions and JSON Schema.
 *
 * <p>This is the home of the {@code ToolDefinition.of(NativeTool)} factory logic: the static {@code
 * of} convenience factories are intentionally absent from the shared {@link ToolDefinition}
 * interface (they are builders, not read surface); callers use {@link #compileNative} here to build
 * {@link NativeToolDefinition} instances.
 */
public final class ToolSchemaCompiler {

    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private ToolSchemaCompiler() {}

    /**
     * Compiles an already-instantiated Spring-managed native tool bean into a {@link
     * NativeToolDefinition}. Native tools are Spring beans, so the bean instance is passed in —
     * this method never {@code newInstance}s the tool (that would bypass Spring DI). It only
     * reflects over the class to derive the schema + security hints.
     */
    public static @NonNull NativeToolDefinition compileNative(@NonNull NativeTool<?> toolBean) {
        ToolSecurity security = securityOf(toolBean.getClass());

        Class<?> argsClass = toolBean.getArgsClass();

        Map<String, ParamCategory> hints = hintsOf(argsClass);

        return new NativeToolDefinition(
                toolBean.getName(),
                toolBean.getDescription(),
                toolBean.getCapability(),
                security.defaultDanger(),
                security.requiresSemanticScreening(),
                toolBean.getClass(),
                argsClass,
                hints);
    }

    static @NonNull ToolSecurity securityOf(@NonNull Class<?> toolClass) {
        ToolSecurity security = toolClass.getAnnotation(ToolDocs.nonNullClass(ToolSecurity.class));
        if (security == null) {
            throw new IllegalArgumentException(
                    toolClass.getName() + " must be annotated with @ToolSecurity");
        }
        return security;
    }

    /**
     * Compiles a plugin-contributed {@link CapabilityTool} instance into an ordinary {@link
     * NativeToolDefinition} that carries {@link Provenance}. The plugin tool executes through the
     * host's internal tool state exactly like a core native tool, so the definition is the same
     * shape — the reflected schema, the {@link ToolSecurity} contract, and the provenance that
     * keeps it session-scoped and revision-pinned.
     */
    public static @NonNull NativeToolDefinition compilePluginNative(
            @NonNull CapabilityTool<?> tool,
            @NonNull String name,
            @NonNull String bindingId,
            @NonNull String pluginId,
            @NonNull String pluginVersion) {
        ToolSecurity security = securityOf(tool.getClass());
        Class<?> argsClass = tool.getArgsClass();
        return new NativeToolDefinition(
                name,
                ToolDocs.descriptionOf(tool.getClass()),
                tool.getCapability(),
                security.defaultDanger(),
                security.requiresSemanticScreening(),
                tool.getClass(),
                argsClass,
                hintsOf(argsClass),
                new Provenance(pluginId, bindingId, pluginVersion));
    }

    /**
     * Compiles an out-of-process script plugin's {@link Tool} descriptor into a {@link
     * RemoteToolDefinition} that carries {@link Provenance}. A script tool has no Java record, so
     * it keeps the raw JSON Schema and the remote execution shape; the declared effect selects the
     * capability and danger (a PRIVILEGED script tool crosses the host trust boundary).
     */
    public static @NonNull RemoteToolDefinition compilePluginScript(
            @NonNull Tool descriptor,
            @NonNull String name,
            @NonNull JsonNode inputSchema,
            @NonNull String bindingId,
            @NonNull String pluginId,
            @NonNull String pluginVersion) {
        boolean privileged = descriptor.effect() == Tool.Effect.PRIVILEGED;
        return new RemoteToolDefinition(
                name,
                descriptor.description(),
                pluginId,
                privileged ? ToolCapability.PRIVILEGED : ToolCapability.REMOTE_UNKNOWN,
                privileged ? Danger.DANGEROUS : Danger.ELEVATED,
                List.of(ToolResultFormat.JSON),
                inputSchema,
                new Provenance(pluginId, bindingId, pluginVersion));
    }

    /**
     * Reflects {@link SecurityHint} annotations off an args record's components into a map of
     * parameter name to {@link ParamCategory}. Extracted from {@link #compileNative}'s inline loop
     * so it can be reused by {@link AgentToolDefinition#from(String, Class, Class,
     * ToolCapability)}.
     */
    public static @NonNull Map<@NonNull String, @NonNull ParamCategory> hintsOf(
            @NonNull Class<?> argsClass) {
        Map<@NonNull String, @NonNull ParamCategory> hints = new LinkedHashMap<>();
        if (!argsClass.isRecord()) {
            throw new IllegalArgumentException("Tool arguments must be a Java Record");
        }
        for (RecordComponent c : argsClass.getRecordComponents()) {
            SecurityHint h = c.getAnnotation(ToolDocs.nonNullClass(SecurityHint.class));
            hints.put(c.getName(), h != null ? h.value() : ParamCategory.GENERIC);
        }
        return hints;
    }

    /**
     * Compiles a Java record type into a Draft-7 JSON Schema via reflection (the schema emitted to
     * the provider manifest). {@code @Doc} supplies parameter descriptions. Reference components
     * are required only when explicitly {@code @NonNull}; primitive components must explicitly use
     * {@link Required} because nullability annotations do not apply to primitives.
     */
    public static @NonNull JsonNode compileFromRecord(@NonNull Class<?> recordClass) {
        var custom = recordClass.getAnnotation(ToolDocs.nonNullClass(ToolInputSchema.class));
        if (custom != null) {
            try {
                return custom.value().getDeclaredConstructor().newInstance().schema();
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Cannot construct tool schema", e);
            }
        }
        if (!recordClass.isRecord()) {
            throw new IllegalArgumentException("Class must be a Java Record");
        }

        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode required = MAPPER.createArrayNode();

        for (RecordComponent component : recordClass.getRecordComponents()) {
            String name = component.getName();
            Class<?> type = component.getType();

            validateConditionalRequirement(recordClass, component);

            ObjectNode paramNode;
            if (type.isArray() || Collection.class.isAssignableFrom(type)) {
                paramNode = MAPPER.createObjectNode();
                paramNode.put("type", "array");
                paramNode.set("items", itemsSchemaOf(component));
            } else if (type.isEnum()) {
                paramNode = enumSchema(type);
            } else if (type.isRecord()) {
                // A nested record component (not wrapped in a collection) - emit its full object
                // schema inline so the provider sees the structured shape, not a bare "object".
                paramNode = (ObjectNode) compileFromRecord(type);
            } else {
                paramNode = MAPPER.createObjectNode();
                paramNode.put("type", mapJavaTypeToSchemaType(type));
            }

            Doc doc = component.getAnnotation(ToolDocs.nonNullClass(Doc.class));
            if (doc != null && !doc.value().isEmpty()) {
                paramNode.put("description", doc.value());
            }

            ArraySize size = component.getAnnotation(ToolDocs.nonNullClass(ArraySize.class));
            if (size != null) {
                if (!"array".equals(paramNode.path("type").asText())
                        || size.min() < 0
                        || size.max() < size.min()) {
                    throw new IllegalArgumentException("Invalid @ArraySize on " + name);
                }
                paramNode.put("minItems", size.min());
                paramNode.put("maxItems", size.max());
            }

            StringConstraint text =
                    component.getAnnotation(ToolDocs.nonNullClass(StringConstraint.class));
            if (text != null) {
                if (type != String.class
                        || text.minLength() < 0
                        || text.maxLength() < text.minLength()) {
                    throw new IllegalArgumentException("Invalid @StringConstraint on " + name);
                }
                paramNode.put("minLength", text.minLength());
                if (text.maxLength() != Integer.MAX_VALUE) {
                    paramNode.put("maxLength", text.maxLength());
                }
                if (!text.pattern().isEmpty()) {
                    paramNode.put("pattern", Pattern.compile(text.pattern()).pattern());
                }
            }

            properties.set(name, paramNode);

            // Repository contracts are nullable by default. JSpecify is @Target(TYPE_USE), so the
            // explicit @NonNull marker normally lives on the annotated component type; checking
            // both reflection surfaces also supports declaration-capable non-null annotations.
            boolean explicitlyNonNull =
                    component.isAnnotationPresent(NonNull.class)
                            || component.getAnnotatedType().isAnnotationPresent(NonNull.class);
            boolean explicitlyRequired =
                    component.isAnnotationPresent(ToolDocs.nonNullClass(Required.class));
            if (type.isPrimitive() && !explicitlyRequired) {
                throw new IllegalArgumentException(
                        "Primitive tool parameter '"
                                + name
                                + "' must declare @Required or use a boxed optional type");
            }
            if (!type.isPrimitive() && explicitlyRequired) {
                throw new IllegalArgumentException(
                        "Reference tool parameter '"
                                + name
                                + "' must use @NonNull instead of @Required");
            }
            if (explicitlyRequired || explicitlyNonNull) {
                required.add(name);
            }
        }

        schema.set("properties", properties);
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static void validateConditionalRequirement(
            @NonNull Class<?> recordClass, @NonNull RecordComponent component) {
        RequiredWhen requiredWhen =
                component.getAnnotation(ToolDocs.nonNullClass(RequiredWhen.class));
        if (requiredWhen == null) {
            return;
        }
        if (component.getType().isPrimitive()) {
            throw new IllegalArgumentException(
                    "Conditionally required tool parameter '"
                            + component.getName()
                            + "' must use an optional reference type");
        }
        if (requiredWhen.values().length == 0) {
            throw new IllegalArgumentException(
                    "Conditionally required tool parameter '"
                            + component.getName()
                            + "' must declare at least one discriminator value");
        }
        for (RecordComponent candidate : recordClass.getRecordComponents()) {
            if (candidate.getName().equals(requiredWhen.field())) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Conditionally required tool parameter '"
                        + component.getName()
                        + "' references unknown discriminator '"
                        + requiredWhen.field()
                        + "'");
    }

    private static @NonNull String mapJavaTypeToSchemaType(@NonNull Class<?> type) {
        if (type == String.class) return "string";
        if (type.isEnum()) return "string";
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class)
            return "integer";
        if (type == double.class
                || type == Double.class
                || type == float.class
                || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (Collection.class.isAssignableFrom(type) || type.isArray()) return "array";
        return "object";
    }

    /**
     * Builds the {@code items} schema for an array/collection component. When the element type is
     * itself a record, its full object schema is emitted recursively - without this a {@code
     * List<NestedRecord>} is advertised as {@code items: {type: string}} and the model has to guess
     * the nested shape from the prose description (observed live: a model then formatted an inner
     * {@code List<String>} field as a bracketed string, which Jackson could not deserialize). For
     * scalar elements the proper JSON-Schema type is used; nested collections retain their element
     * schemas and raw collections leave the element type unrestricted.
     */
    private static @NonNull JsonNode itemsSchemaOf(@NonNull RecordComponent component) {
        Type type = component.getGenericType();
        if (type instanceof Class<?> array && array.isArray()) {
            return schemaOf(array.getComponentType());
        }
        if (type instanceof ParameterizedType collection) {
            return schemaOf(collection.getActualTypeArguments()[0]);
        }
        return MAPPER.createObjectNode();
    }

    private static @NonNull JsonNode schemaOf(Type type) {
        ObjectNode schema = MAPPER.createObjectNode();
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw
                && Collection.class.isAssignableFrom(raw)) {
            schema.put("type", "array");
            schema.set("items", schemaOf(parameterized.getActualTypeArguments()[0]));
        } else if (type instanceof Class<?> concrete) {
            if (concrete.isRecord()) return compileFromRecord(concrete);
            if (concrete.isEnum()) return enumSchema(concrete);
            schema.put("type", mapJavaTypeToSchemaType(concrete));
            if (concrete.isArray()) schema.set("items", schemaOf(concrete.getComponentType()));
        }
        return schema;
    }

    private static @NonNull ObjectNode enumSchema(@NonNull Class<?> enumType) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "string");
        ArrayNode values = schema.putArray("enum");
        Object[] constants = enumType.getEnumConstants();
        if (constants != null) {
            for (Object constant : constants) {
                values.add(((Enum<?>) constant).name());
            }
        }
        return schema;
    }

    static @NonNull ObjectNode emptyObjectSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode());
        schema.put("additionalProperties", false);
        return schema;
    }
}
