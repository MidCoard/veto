package top.focess.veto.builtin.search;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.service.PluginService;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.service.ServiceCallContext;
import top.focess.veto.api.plugin.service.ServiceException;

/** Builtin-owned search service; external providers are revocable opaque JSON callbacks. */
public final class SearchHub extends PluginService {
    private record Callback(@NonNull String ownerId, @NonNull String id) {}

    private final @NonNull PluginServices services;
    private final @NonNull Map<@NonNull String, @NonNull SearchProvider> local;
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull Callback> callbacks =
            new ConcurrentHashMap<>();

    /** Creates a service with its built-in providers. */
    public SearchHub(
            @NonNull PluginServices services, @NonNull List<@NonNull SearchProvider> providers) {
        super(SearchProtocol.NAME, 1, PluginScope.APPLICATION);
        this.services = services;
        Map<@NonNull String, @NonNull SearchProvider> installed = new HashMap<>();
        for (var provider : providers)
            if (installed.putIfAbsent(provider.name(), provider) != null)
                throw new IllegalArgumentException("Duplicate search provider");
        local = Map.copyOf(installed);
    }

    @Override
    public @NonNull JsonValue invoke(@NonNull ServiceCallContext caller, @NonNull JsonValue request)
            throws ServiceException {
        if (!(request instanceof JsonValue.ObjectValue object))
            throw new ServiceException(ServiceException.Code.INVALID_REQUEST);
        var fields = object.values();
        var operation = fields.get("op");
        if (!(operation instanceof JsonValue.StringValue))
            throw new ServiceException(ServiceException.Code.INVALID_REQUEST);
        return switch (((JsonValue.StringValue) operation).value()) {
            case "register" -> register(caller, fields);
            case "providers" -> providers();
            case "search" -> search(fields, request);
            default -> throw new ServiceException(ServiceException.Code.INVALID_REQUEST);
        };
    }

    private @NonNull JsonValue register(
            @NonNull ServiceCallContext caller,
            @NonNull Map<@NonNull String, @NonNull JsonValue> fields)
            throws ServiceException {
        var name = fields.get("name");
        var callback = fields.get("callback");
        if (!(name instanceof JsonValue.StringValue)
                || !(callback instanceof JsonValue.StringValue)
                || !((JsonValue.StringValue) name).value().matches("[a-z][a-z0-9_-]{0,63}")
                || local.containsKey(((JsonValue.StringValue) name).value())
                || caller.callerId().isEmpty())
            throw new ServiceException(ServiceException.Code.INVALID_REQUEST);
        String providerName = ((JsonValue.StringValue) name).value();
        String callbackId = ((JsonValue.StringValue) callback).value();
        var handle =
                services.findCallback(callbackId)
                        .orElseThrow(() -> new ServiceException(ServiceException.Code.UNAVAILABLE));
        if (!caller.callerId().equals(handle.providerId()))
            throw new ServiceException(ServiceException.Code.INVALID_REQUEST);
        Callback existing =
                callbacks.putIfAbsent(providerName, new Callback(caller.callerId(), callbackId));
        if (existing != null) {
            if (!existing.ownerId().equals(caller.callerId()))
                throw new ServiceException(ServiceException.Code.INVALID_REQUEST);
            callbacks.replace(providerName, existing, new Callback(caller.callerId(), callbackId));
        }
        return new JsonValue.ObjectValue(Map.of("registered", new JsonValue.BooleanValue(true)));
    }

    private @NonNull JsonValue providers() {
        callbacks
                .entrySet()
                .removeIf(entry -> services.findCallback(entry.getValue().id()).isEmpty());
        List<@NonNull JsonValue> names = new ArrayList<>();
        local.keySet().stream()
                .sorted()
                .forEach(name -> names.add(new JsonValue.StringValue(name)));
        callbacks.entrySet().stream()
                .map(Map.Entry::getKey)
                .sorted()
                .forEach(name -> names.add(new JsonValue.StringValue(name)));
        return new JsonValue.ArrayValue(names);
    }

    private @NonNull JsonValue search(
            @NonNull Map<@NonNull String, @NonNull JsonValue> fields, @NonNull JsonValue request)
            throws ServiceException {
        var name = fields.get("provider");
        if (!(name instanceof JsonValue.StringValue))
            throw new ServiceException(ServiceException.Code.INVALID_REQUEST);
        String providerName = ((JsonValue.StringValue) name).value();
        var parsed = SearchProtocol.decodeRequest(request);
        SearchProvider builtin = local.get(providerName);
        if (builtin != null) {
            try {
                return SearchProtocol.encodeResults(
                        SearchPolicy.apply(
                                builtin.search(parsed.query(), parsed.options()),
                                parsed.options()));
            } catch (HttpTimeoutException failure) {
                throw new ServiceException(ServiceException.Code.TIMEOUT);
            } catch (Exception failure) {
                throw new ServiceException(ServiceException.Code.FAILED);
            }
        }
        Callback callback = callbacks.get(providerName);
        if (callback == null) throw new ServiceException(ServiceException.Code.UNAVAILABLE);
        var handle =
                services.findCallback(callback.id())
                        .orElseThrow(() -> new ServiceException(ServiceException.Code.UNAVAILABLE));
        if (!callback.ownerId().equals(handle.providerId()))
            throw new ServiceException(ServiceException.Code.UNAVAILABLE);
        JsonValue result = handle.invoke(SearchProtocol.request(parsed.query(), parsed.options()));
        try {
            return SearchProtocol.encodeResults(
                    SearchPolicy.apply(SearchProtocol.results(result), parsed.options()));
        } catch (IllegalArgumentException failure) {
            throw new ServiceException(ServiceException.Code.FAILED);
        }
    }
}
