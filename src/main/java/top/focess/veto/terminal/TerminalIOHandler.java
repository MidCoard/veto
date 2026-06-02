package top.focess.veto.terminal;

import top.focess.command.IOHandler;

/**
 * IOHandler that reads from stdin and writes to stdout.
 */
public class TerminalIOHandler extends IOHandler {

    @Override
    public void output(String output) {
        System.out.println(output);
    }
}
