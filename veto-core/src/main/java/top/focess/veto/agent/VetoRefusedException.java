package top.focess.veto.agent;

/** Internal control signal that host screening or a required approval refused execution. */
final class VetoRefusedException extends RuntimeException {
    final boolean approvalRequested;

    VetoRefusedException() {
        this(false);
    }

    VetoRefusedException(boolean approvalRequested) {
        this.approvalRequested = approvalRequested;
    }
}
