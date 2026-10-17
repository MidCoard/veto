package top.focess.veto.secret;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.event.AgentTerminatedEvent;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.event.EventHandler;
import top.focess.veto.api.event.Listener;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.event.UserAuthenticatedEvent;
import top.focess.veto.api.event.UserLogoutEvent;
import top.focess.veto.secret.detection.SlmSecretDetector;
import top.focess.veto.secret.references.SecretCandidateStore;

/**
 * Scopes secret-candidate availability to the userId, session, and agent transitions the host
 * broadcasts. A closed scope makes its captured references unrecoverable, so capture fails closed
 * until the userId is opened again.
 */
public final class SecretProtectionListener implements Listener {
    private final @NonNull SecretCandidateStore candidates;
    private final @NonNull SlmSecretDetector detector;

    /** Creates the listener over the candidate store it scopes. */
    public SecretProtectionListener(
            @NonNull SecretCandidateStore candidates, @NonNull SlmSecretDetector detector) {
        this.candidates = candidates;
        this.detector = detector;
    }

    /** Protects text before the host commits it, preserving captured reference markers. */
    @EventHandler
    public void onTextCommit(@NonNull BeforeTextCommitEvent event) {
        var scope = event.scope();
        switch (event.phase()) {
            case INPUT ->
                    event.setText(candidates.capture(scope, event.sourceId(), event.text()).text());
            case FILE_CAPTURE ->
                    event.setText(
                            candidates.captureFile(scope, event.sourceId(), event.text()).text());
            case FILE_OBSERVATION -> {
                var output = new StringBuilder();
                for (var segment : candidates.referenceSegments(scope, event.text()))
                    output.append(
                            segment.reference() ? segment.text() : detector.mask(segment.text()));
                event.setText(output.toString());
            }
        }
    }

    @EventHandler
    public void onUserAuthenticated(@NonNull UserAuthenticatedEvent event) {
        candidates.openUser(event.scope().userId());
    }

    @EventHandler
    public void onUserLogout(@NonNull UserLogoutEvent event) {
        candidates.closeUser(event.scope().userId());
    }

    @EventHandler
    public void onSessionDeleted(@NonNull SessionDeletedEvent event) {
        candidates.retireSession(event.scope().userId(), event.scope().session());
    }

    @EventHandler
    public void onAgentTerminated(@NonNull AgentTerminatedEvent event) {
        candidates.discardAgent(event.scope());
    }
}
