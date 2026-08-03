package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern DSL_PATTERN = Pattern.compile("^([a-zA-Z]+)([!?]?)\\s*(.*)$");

    private ToolSchemaCompiler() {}

    /**
     * Compiles an already-instantiated Spring-managed native tool bean into a {@link
     * NativeToolDefinition}. Native tools are Spring beans, so the bean instance is passed in —
     * this method never {@code newInstance}s the tool (that would bypass Spring DI). It only
     * reflects over the class to derive the schema + security hints.
     */
    public static @NonNull NativeToolDefinition compileNative(@NonNull NativeTool<?> toolBean) {
        Class<?> toolClass = toolBean.getClass();
        ToolSecurity security = toolClass.getAnnotation(ToolSecurity.class);
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
                security.requiresSemanticScreening(),
                argsClass,
                hints);
    }

    /**
     * Reflects {@link SecurityHint} annotations off an args record's components into a map of
     * parameter name to {@link ParamCategory}. Extracted from {@link #compileNative}'s inline loop
     * so it can be reused by {@link AgentToolDefinition#from(Class)}.
     */
    public static @NonNull Map<String, ParamCategory> hintsOf(@NonNull Class<?> argsClass) {
        Map<String, ParamCategory> hints = new LinkedHashMap<>();
        for (RecordComponent c : argsClass.getRecordComponents()) {
            SecurityHint h = c.getAnnotation(SecurityHint.class);
            hints.put(c.getName(), h != null ? h.value() : ParamCategory.GENERIC);
        }
        return hints;
    }

    /**
     * Compiles a Java record type into a Draft-7 JSON Schema via reflection (the schema emitted to
     * the provider manifest). {@code @Doc} supplies parameter descriptions; non-nullable components
     * are added to {@code required}.
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

            ObjectNode paramNode = MAPPER.createObjectNode();
            paramNode.put("type", mapJavaTypeToSchemaType(type));

            Doc doc = component.getAnnotation(Doc.class);
            if (doc != null && !doc.value().isEmpty()) {
                paramNode.put("description", doc.value());
            }

            if (Collection.class.isAssignableFrom(type) || type.isArray()) {
                paramNode.putArray("items").addObject().put("type", "string");
            }

            properties.set(name, paramNode);

            // JSpecify @Nullable is @Target(TYPE_USE) only, so it lives on the component's
            // annotated type, not as a declaration annotation on the component itself
            // (RecordComponent.isAnnotationPresent would miss it). Check both surfaces so any
            // nullability marker - declaration or type-use - makes the param optional.
            boolean nullable =
                    component.isAnnotationPresent(Nullable.class)
                            || component.getAnnotatedType().isAnnotationPresent(Nullable.class);
            if (type.isPrimitive() || !nullable) {
                required.add(name);
            }
        }

        schema.set("properties", properties);
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    /**
     * Compiles a key-value String DSL map ({@code "<name>": "<type><modifier> <description>"}) into
     * a Draft-7 JSON Schema. {@code!} = required, {@code?}/omitted = optional.
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
                String type = matcher.group(1).toLowerCase();
                String modifier = matcher.group(2);
                String description = matcher.group(3).trim();
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
}
