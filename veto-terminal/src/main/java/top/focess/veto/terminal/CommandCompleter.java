package top.focess.veto.terminal;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jspecify.annotations.NonNull;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ProtocolClient;

/**
 * JLine {@link Completer} that fetches tab-completion candidates from the backend.
 *
 * <p>Completion is triggered only when the buffer starts with {@code /} (slash-commands).
 * Candidates are retrieved synchronously via {@link ProtocolClient#complete} with a 2-second
 * timeout; if the backend does not respond in time, no candidates are offered.
 */
final class CommandCompleter implements Completer {
    private final @NonNull ProtocolClient client;

    CommandCompleter(@NonNull ProtocolClient client) {
        this.client = client;
    }

    @Override
    @SuppressWarnings("argument") // JLine Candidate defines optional metadata with null sentinels.
    public void complete(
            @NonNull LineReader r, @NonNull ParsedLine line, @NonNull List<Candidate> out) {
        String fullLine = line.line();
        // Completion is only requested for slash commands.
        if (!fullLine.startsWith("/")) return;
        Frame.CompleteResult compResult = client.complete(fullLine, 2, TimeUnit.SECONDS);
        if (compResult != null) {
            for (Frame.Completion comp : compResult.candidates()) {
                String name = comp.value();
                String rawDescription = comp.description();
                String desc =
                        rawDescription != null && !rawDescription.isEmpty() ? rawDescription : null;
                String rawGroup = comp.group();
                String group = rawGroup != null && !rawGroup.isEmpty() ? rawGroup : null;
                out.add(new Candidate(name, name, group, desc, null, null, true));
            }
        }
    }
}
