package top.focess.veto.sandbox;

/**
 * Docker-engine substrate (cloud / self-hosted multi-user). A permitted subtype of the sealed
 * {@link SandboxSubstrate} so the contract hierarchy is complete; all operations throw until
 * implemented with the {@code docker-java} adapter.
 */
public final class ContainerSubstrate implements SandboxSubstrate {

    @Override
    public SandboxHandle provision(SandboxProfile profile) {
        throw new UnsupportedOperationException("ContainerSubstrate requires Docker Engine");
    }

    @Override
    public CommandResult runCommands(
            SandboxHandle h,
            java.util.List<Command> cmds,
            java.nio.file.Path cwd,
            ChainMode connect,
            java.time.Duration timeout) {
        throw new UnsupportedOperationException("ContainerSubstrate is not available");
    }

    @Override
    public byte[] readFile(SandboxHandle h, java.nio.file.Path rel) {
        throw new UnsupportedOperationException("ContainerSubstrate is not available");
    }

    @Override
    public void writeFile(SandboxHandle h, java.nio.file.Path rel, byte[] content) {
        throw new UnsupportedOperationException("ContainerSubstrate is not available");
    }

    @Override
    public void patchFile(SandboxHandle h, java.nio.file.Path rel, PatchSpec patch) {
        throw new UnsupportedOperationException("ContainerSubstrate is not available");
    }

    @Override
    public java.util.List<Entry> listDir(SandboxHandle h, java.nio.file.Path rel) {
        throw new UnsupportedOperationException("ContainerSubstrate is not available");
    }

    @Override
    public java.util.List<Match> grep(SandboxHandle h, java.nio.file.Path rel, GrepSpec spec) {
        throw new UnsupportedOperationException("ContainerSubstrate is not available");
    }

    @Override
    public Stat stat(SandboxHandle h, java.nio.file.Path rel) {
        throw new UnsupportedOperationException("ContainerSubstrate is not available");
    }

    @Override
    public void deprovision(SandboxHandle h) {
        throw new UnsupportedOperationException("ContainerSubstrate is not available");
    }
}
