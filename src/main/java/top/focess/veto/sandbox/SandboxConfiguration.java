package top.focess.veto.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for C6 Atomic Tool Execution Sandbox. */
@Configuration
@ConfigurationProperties(prefix = "veto.sandbox")
public class SandboxConfiguration {

  private List<String> allowedCapabilities =
      List.of(
          "read_safe_file",
          "compile_cpp_target",
          "list_directory",
          "diff_text_files",
          "search_in_files");
  private boolean forbidGenericShell = true;
  private String tempDir = "./work/sandbox";

  public List<String> getAllowedCapabilities() {
    return allowedCapabilities;
  }

  public void setAllowedCapabilities(List<String> allowedCapabilities) {
    this.allowedCapabilities = allowedCapabilities;
  }

  public boolean isForbidGenericShell() {
    return forbidGenericShell;
  }

  public void setForbidGenericShell(boolean forbidGenericShell) {
    this.forbidGenericShell = forbidGenericShell;
  }

  public String getTempDir() {
    return tempDir;
  }

  public void setTempDir(String tempDir) {
    this.tempDir = tempDir;
  }
}
