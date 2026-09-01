package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * Compiles the two human-friendly input-schema formats into canonical {@link ToolDefinition}
 * instances and Draft-7 JSON Schema.
 *
 * <p>This is the home of the {@code ToolDefinition.of(NativeTool)} factory logic: the static {@code
 * of} convenience factories are intentionally absent from the shared {@link ToolDefinition}
 * interface (they are builders, not read surface); callers use {@link #compileNative} here to build
 * {@link NativeToolDefinition} instances.
 */
public final class ToolSchemaCompiler {

    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    private static final @NonNull Pattern DSL_PATTERN =
            Pattern.compile("^([a-zA-Z]+)([!?]?)\\s*(.*)$");

    private ToolSchemaCompiler() {}

    /**
     * Compiles an already-instantiated Spring-managed native tool bean into a {@link
     * NativeToolDefinition}. Native tools are Spring beans, so the bean instance is passed in —
     * this method never {@code newInstance}s the tool (that would bypass Spring DI). It only
     * reflects over the class to derive the schema + security hints.
     */
    public static @NonNull NativeToolDefinition compileNative(@NonNull NativeTool<?> toolBean) {
        Class<?> toolClass = ToolDocs.nonNullClass(toolBean.getClass());
        ToolSecurity security = toolClass.getAnnotation(ToolDocs.nonNullClass(ToolSecurity.class));
        if (security == null) {
            throw new IllegalArgumentException(
                    toolClass.getName() + " must be annotated with @ToolSecurity");
        }

        Class<?> argsClass = findArgsClass(toolClass);

        Map<String, ParamCategory> hints = hintsOf(argsClass);

        return new NativeToolDefinition(
                toolBean.getName(),
                toolBean.getDescription(),
                security.risk(),
                security.capability(),
                security.requiresSemanticScreening(),
                argsClass,
                hints);
    }

    /**
     * Reflects {@link SecurityHint} annotations off an args record's components into a map of
     * parameter name to {@link ParamCategory}. Extracted from {@link #compileNative}'s inline loop
     * so it can be reused by {@link AgentToolDefinition#from(Class)}.
     */
    public static @NonNull Map<@NonNull String, @NonNull ParamCategory> hintsOf(
            @NonNull Class<?> argsClass) {
        Map<@NonNull String, @NonNull ParamCategory> hints = new LinkedHashMap<>();
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

    /**
     * Compiles a key-value String DSL map ({@code "<name>": "<type><modifier> <description>"}) into
     * a Draft-7 JSON Schema. {@code !} = required, {@code ?}/omitted = optional.
     */
    public static @NonNull JsonNode compileFromStringDsl(@NonNull Map<String, String> dslMap) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode required = MAPPER.createArrayNode();

        for (Map.Entry<String, String> entry : dslMap.entrySet()) {
            String paramName = entry.getKey();
            String dslValue = entry.getValue().trim();
            Matcher matcher = DSL_PATTERN.matcher(dslValue);

            ObjectNode paramNode = MAPPER.createObjectNode();
            if (matcher.matches()) {
                String typeGroup = matcher.group(1);
                String modifier = matcher.group(2);
                String descriptionGroup = matcher.group(3);
                if (typeGroup == null || descriptionGroup == null) {
                    throw new IllegalArgumentException("Malformed tool parameter DSL: " + dslValue);
                }
                String type = typeGroup.toLowerCase();
                String description = descriptionGroup.trim();
                paramNode.put("type", type);
                if (!description.isEmpty()) {
                    paramNode.put("description", description);
                }
                if ("!".equals(modifier)) {
                    required.add(paramName);
                }
            } else {
                paramNode.put("type", "string");
                paramNode.put("description", dslValue);
            }
            properties.set(paramName, paramNode);
        }

        schema.set("properties", properties);
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Walks the {@link NativeTool} or {@link AgentTool} interface to find the type parameter (the
     * args record).
     */
    static @NonNull Class<?> findArgsClass(@NonNull Class<?> toolClass) {
        for (var iface : toolClass.getGenericInterfaces()) {
            if (iface instanceof java.lang.reflect.ParameterizedType pt) {
                var rawType = pt.getRawType();
                if (rawType == NativeTool.class || rawType == AgentTool.class) {
                    return (Class<?>) pt.getActualTypeArguments()[0];
                }
            }
        }
        throw new IllegalArgumentException(
                toolClass.getName() + " must implement NativeTool<T> or AgentTool<T>");
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
     * scalar elements the proper JSON-Schema type is used; raw (unparameterized) collections fall
     * back to {@code string} items.
     */
    private static @NonNull JsonNode itemsSchemaOf(@NonNull RecordComponent component) {
        Class<?> elementType = elementClassOf(component);
        if (elementType != null && elementType.isRecord()) {
            return compileFromRecord(elementType);
        }
        if (elementType != null && elementType.isEnum()) {
            return enumSchema(elementType);
        }
        ObjectNode items = MAPPER.createObjectNode();
        items.put("type", elementType != null ? mapJavaTypeToSchemaType(elementType) : "string");
        return items;
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

    /**
     * Resolves the element class of an array or collection component, or {@code null} for a raw
     * (unparameterized) collection whose element type is unknown at compile time.
     */
    private static Class<?> elementClassOf(@NonNull RecordComponent component) {
        Class<?> type = component.getType();
        if (type.isArray()) {
            return type.getComponentType();
        }
        if (Collection.class.isAssignableFrom(type)) {
            Type generic = component.getGenericType();
            if (generic instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length > 0) {
                    Type arg = args[0];
                    if (arg instanceof Class<?> c) {
                        return c;
                    }
                    // e.g. List<List<String>> - take the raw outer class of the nested
                    // parameterized type.
                    if (arg instanceof ParameterizedType nested
                            && nested.getRawType() instanceof Class<?> c) {
                        return c;
                    }
                }
            }
        }
        return null;
    }
}
