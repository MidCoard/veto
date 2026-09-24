package top.focess.veto.llm.embedding;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.jspecify.annotations.NonNull;

/** Cancels the response as soon as it exceeds the fixed model-response byte ceiling. */
final class BoundedEmbeddingBody implements HttpResponse.BodySubscriber<byte[]> {
    private final @NonNull ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final @NonNull CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    @Override
    public @NonNull CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.@NonNull Subscription value) {
        subscription = value;
        value.request(1);
    }

    @Override
    public void onNext(@NonNull List<ByteBuffer> buffers) {
        for (var buffer : buffers) {
            if ((long) bytes.size() + buffer.remaining() > 1_048_576) {
                var active = subscription;
                if (active != null) active.cancel();
                result.completeExceptionally(
                        new IllegalStateException("Embedding response too large"));
                return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
        }
        var active = subscription;
        if (active != null) active.request(1);
    }

    @Override
    public void onError(@NonNull Throwable error) {
        result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
        result.complete(bytes.toByteArray());
    }
}
