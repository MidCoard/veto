package top.focess.veto.controller;

import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.focess.veto.controller.dto.*;

/**
 * Host facts for frontends: the server-side OS family + path syntax, so a browser client (which
 * cannot see the server's platform) renders path placeholders and examples that match the machine
 * the tools actually run on. No user data, safe to expose without a session.
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    /** Returns the host OS name/arch, path syntax family, separator, and an example root path. */
    @GetMapping("/info")
    public @NonNull SystemInfoResponse info() {
        String osName = System.getProperty("os.name", "unknown");
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        return new SystemInfoResponse(
                osName,
                System.getProperty("os.arch", "unknown"),
                windows ? "windows" : "posix",
                windows ? "\\" : "/",
                windows ? "D:\\projects\\one" : "/home/user/projects/one");
    }
}
