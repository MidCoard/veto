package top.focess.veto.api.search;

import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.service.ServiceException;
import top.focess.veto.api.plugin.service.ServiceRegistration;

/** Optional helpers for the JSON search service protocol, independent of provider classes. */
public final class SearchServices {
    private SearchServices() {}

    public static @NonNull String name(@NonNull String provider) {
        return "veto.search:" + provider;
    }

    public static @NonNull JsonValue request(
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

    private static @NonNull JsonValue strings(@Nullable List<String> values) {
        if (values == null) return JsonValue.NullValue.INSTANCE;
        List<JsonValue> items = new ArrayList<>();
        for (var value : values) items.add(new JsonValue.StringValue(value));
        return new JsonValue.ArrayValue(items);
    }

    private static @Nullable List<String> strings(@Nullable JsonValue value) {
        if (value == null || value instanceof JsonValue.NullValue) return null;
        if (!(value instanceof JsonValue.ArrayValue array))
            throw new IllegalArgumentException("Expected string array");
        List<String> result = new ArrayList<>();
        for (var item : array.values()) result.add(text(item));
        return List.copyOf(result);
    }

    private static @NonNull String text(@Nullable JsonValue value) {
        if (value instanceof JsonValue.StringValue text) return text.value();
        throw new IllegalArgumentException("Expected string");
    }

    public static @NonNull List<SearchResult> results(@NonNull JsonValue value) {
        if (!(value instanceof JsonValue.ArrayValue array))
            throw new IllegalArgumentException("Expected search results");
        List<SearchResult> result = new ArrayList<>();
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

    public static @NonNull ServiceRegistration registration(@NonNull SearchProvider provider) {
        return new ServiceRegistration(
                name(provider.name()),
                1,
                request -> {
                    String query;
                    SearchOptions options;
                    try {
                        if (!(request instanceof JsonValue.ObjectValue object))
                            throw new IllegalArgumentException("Expected request");
                        var fields = object.values();
                        query = text(fields.get("query"));
                        var max = fields.get("maxResults");
                        if (!(max instanceof JsonValue.NumberValue number))
                            throw new IllegalArgumentException("Expected result limit");
                        options =
                                new SearchOptions(
                                        strings(fields.get("allowedDomains")),
                                        strings(fields.get("blockedDomains")),
                                        number.value().intValueExact());
                    } catch (RuntimeException failure) {
                        throw new ServiceException(ServiceException.Code.INVALID_REQUEST);
                    }
                    List<SearchResult> results;
                    try {
                        results = provider.search(query, options);
                    } catch (HttpTimeoutException failure) {
                        throw new ServiceException(ServiceException.Code.TIMEOUT);
                    }
                    List<JsonValue> values = new ArrayList<>();
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
                });
    }
}
