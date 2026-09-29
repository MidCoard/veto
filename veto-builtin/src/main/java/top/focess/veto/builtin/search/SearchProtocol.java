package top.focess.veto.builtin.search;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.service.ServiceException;

/** Builtin-owned JSON protocol shared by its search service and built-in provider callbacks. */
public final class SearchProtocol {
    private SearchProtocol() {}

    /** Public service protocol identity; provider names are request data, not service names. */
    public static final @NonNull String NAME = "veto.search";

    /** Parsed search request shared by the service and its local providers. */
    public record Request(@NonNull String query, @NonNull SearchOptions options) {}

    /**
     * Encodes a search request for the named JSON service.
     *
     * @param query search query
     * @param options filters and result cap
     * @return portable JSON request object
     */
    public static JsonValue.@NonNull ObjectValue request(
            @NonNull String query, @NonNull SearchOptions options) {
        return new JsonValue.ObjectValue(
                Map.of(
                        "query",
                        new JsonValue.StringValue(query),
                        "allowedDomains",
                        strings(options.allowedDomains()),
                        "blockedDomains",
                        strings(options.blockedDomains()),
                        "maxResults",
                        new JsonValue.NumberValue(BigDecimal.valueOf(options.maxResults()))));
    }

    /** Encodes one search call to the builtin-owned service. */
    public static JsonValue.@NonNull ObjectValue searchRequest(
            @NonNull String provider, @NonNull String query, @NonNull SearchOptions options) {
        var request = request(query, options);
        var fields = new HashMap<>(request.values());
        fields.put("op", new JsonValue.StringValue("search"));
        fields.put("provider", new JsonValue.StringValue(provider));
        return new JsonValue.ObjectValue(fields);
    }

    /** Decodes a provider callback request or the search fields in a service request. */
    public static @NonNull Request decodeRequest(@NonNull JsonValue request)
            throws ServiceException {
        try {
            if (!(request instanceof JsonValue.ObjectValue object))
                throw new IllegalArgumentException("Expected request");
            var fields = object.values();
            String query = text(fields.get("query"));
            var max = fields.get("maxResults");
            if (!(max instanceof JsonValue.NumberValue))
                throw new IllegalArgumentException("Expected result limit");
            return new Request(
                    query,
                    new SearchOptions(
                            strings(fields.get("allowedDomains")),
                            strings(fields.get("blockedDomains")),
                            ((JsonValue.NumberValue) max).value().intValueExact()));
        } catch (RuntimeException failure) {
            throw new ServiceException(ServiceException.Code.INVALID_REQUEST);
        }
    }

    private static @NonNull JsonValue strings(List<@NonNull String> values) {
        if (values == null) return JsonValue.NullValue.INSTANCE;
        List<@NonNull JsonValue> items = new ArrayList<>();
        for (var value : values) items.add(new JsonValue.StringValue(value));
        return new JsonValue.ArrayValue(items);
    }

    private static List<@NonNull String> strings(JsonValue value) {
        if (value == null || value instanceof JsonValue.NullValue) return null;
        if (!(value instanceof JsonValue.ArrayValue array))
            throw new IllegalArgumentException("Expected string array");
        List<@NonNull String> result = new ArrayList<>();
        for (var item : array.values()) result.add(text(item));
        return List.copyOf(result);
    }

    private static @NonNull String text(JsonValue value) {
        if (value instanceof JsonValue.StringValue) return ((JsonValue.StringValue) value).value();
        throw new IllegalArgumentException("Expected string");
    }

    /**
     * Decodes search results returned by the named JSON service.
     *
     * @param value portable JSON result array
     * @return immutable list of decoded hits
     * @throws IllegalArgumentException if the protocol payload is malformed
     */
    public static @NonNull List<@NonNull SearchResult> results(@NonNull JsonValue value) {
        if (!(value instanceof JsonValue.ArrayValue array))
            throw new IllegalArgumentException("Expected search results");
        List<@NonNull SearchResult> result = new ArrayList<>();
        for (var item : array.values()) {
            if (!(item instanceof JsonValue.ObjectValue object))
                throw new IllegalArgumentException("Expected search result");
            var fields = object.values();
            result.add(
                    new SearchResult(
                            text(fields.get("title")),
                            text(fields.get("url")),
                            text(fields.get("snippet"))));
        }
        return List.copyOf(result);
    }

    /** Encodes search results for a provider callback or service caller. */
    public static JsonValue.@NonNull ArrayValue encodeResults(
            @NonNull List<@NonNull SearchResult> results) {
        List<@NonNull JsonValue> values = new ArrayList<>();
        for (var result : results)
            values.add(
                    new JsonValue.ObjectValue(
                            Map.of(
                                    "title",
                                    new JsonValue.StringValue(result.title()),
                                    "url",
                                    new JsonValue.StringValue(result.url()),
                                    "snippet",
                                    new JsonValue.StringValue(result.snippet()))));
        return new JsonValue.ArrayValue(values);
    }
}
