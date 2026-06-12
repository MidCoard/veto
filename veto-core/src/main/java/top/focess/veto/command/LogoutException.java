package top.focess.veto.command;

public class LogoutException extends RuntimeException {
    public LogoutException() {
        super("Logged out.");
    }
}
