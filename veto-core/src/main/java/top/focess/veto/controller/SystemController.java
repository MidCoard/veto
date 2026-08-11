package top.focess.veto.controller;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Host facts for frontends: the server-side OS family + path syntax, so a browser client (which
 * cannot see the server's platform) renders path placeholders and examples that match the machine
 * the tools actually run on. No user data, safe to expose without a session.
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    @GetMapping("/info")
    public @NonNull Map<String, Object> info() {
        String osName = System.getProperty("os.name", "unknown");
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        return Map.of(
                "os", osName,
                "arch", System.getProperty("os.arch", "unknown"),
                "family", windows ? "windows" : "posix",
                "pathSeparator", windows ? "\\" : "/",
                "pathExample", windows ? "D:\\projects\\one" : "/home/user/projects/one");
    }
}
