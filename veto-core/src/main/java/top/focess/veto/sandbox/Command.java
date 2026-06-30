package top.focess.veto.sandbox;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * A single discrete command in a {@code run_command} chain. The model lists discrete commands; Veto
 * supplies the connector between them ({@link ChainMode})..
 *
 * @param executable the binary name (resolved against the exec allowlist), e.g. {@code "gradle"};
 *     never a shell string
 * @param args the argv array; glob/env expansion is done by Veto, not a shell
 */
public record Command(@NonNull String executable, @NonNull List<String> args) {}
