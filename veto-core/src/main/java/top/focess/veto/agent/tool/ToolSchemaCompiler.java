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
import java.util.Map;
import org.jspecify.annotations.NonNull;

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
        Class<?> toolClass = ToolDocs.nonNullClass(toolBean.getClass());
        ToolSecurity security = toolClass.getAnnotation(ToolDocs.nonNullClass(ToolSecurity.class));
        if (security == null) {
            throw new IllegalArgumentException(
                    toolClass.getName() + " must be annotated with @ToolSecurity");
        }

        Class<?> argsClass = toolBean.getArgsClass();

        Map<String, ParamCategory> hints = hintsOf(argsClass);

        return new NativeToolDefinition(
                toolBean.getName(),
                toolBean.getDescription(),
                security.capability(),
                security.defaultDanger(),
                security.requiresSemanticScreening(),
                argsClass,
                hints);
    }

    /**
     * Reflects {@link SecurityHint} annotations off an args record's components into a map of
     * parameter name to {@link ParamCategory}. Extracted from {@link #compileNative}'s inline loop
     * so it can be reused by {@link AgentToolDefinition#from(String, Class, ToolCapability)}.
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
