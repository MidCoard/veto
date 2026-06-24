package top.focess.veto.sandbox;

/**
 * Firecracker microVM substrate (public cloud / hostile untrusted tenants). A permitted subtype of
 * the sealed {@link SandboxSubstrate}; all operations throw until implemented with the Firecracker
 * API adapter.
 */
public final class MicrovmSubstrate implements SandboxSubstrate {

    @Override
    public SandboxHandle provision(SandboxProfile profile) {
        throw new UnsupportedOperationException("MicrovmSubstrate requires Firecracker");
    }

    @Override
    public CommandResult runCommands(
            SandboxHandle h,
            java.util.List<Command> cmds,
            java.nio.file.Path cwd,
            ChainMode connect,
            java.time.Duration timeout) {
        throw new UnsupportedOperationException("MicrovmSubstrate is not available");
    }

    @Override
    public byte[] readFile(SandboxHandle h, java.nio.file.Path rel) {
        throw new UnsupportedOperationException("MicrovmSubstrate is not available");
    }

    @Override
    public void writeFile(SandboxHandle h, java.nio.file.Path rel, byte[] content) {
        throw new UnsupportedOperationException("MicrovmSubstrate is not available");
    }

    @Override
    public void patchFile(SandboxHandle h, java.nio.file.Path rel, PatchSpec patch) {
        throw new UnsupportedOperationException("MicrovmSubstrate is not available");
    }

    @Override
    public java.util.List<Entry> listDir(SandboxHandle h, java.nio.file.Path rel) {
        throw new UnsupportedOperationException("MicrovmSubstrate is not available");
    }

    @Override
    public java.util.List<Match> grep(SandboxHandle h, java.nio.file.Path rel, GrepSpec spec) {
        throw new UnsupportedOperationException("MicrovmSubstrate is not available");
    }

    @Override
    public Stat stat(SandboxHandle h, java.nio.file.Path rel) {
        throw new UnsupportedOperationException("MicrovmSubstrate is not available");
    }

    @Override
    public void deprovision(SandboxHandle h) {
        throw new UnsupportedOperationException("MicrovmSubstrate is not available");
    }
}
