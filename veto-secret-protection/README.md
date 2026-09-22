# Secret protection plugin

The built-in secret-protection plugin: a self-contained `VetoPlugin`
(`top.focess.veto.secret.SecretProtectionPlugin`) discovered by the host through
`ServiceLoader` via `META-INF/services/top.focess.veto.plugin.api.VetoPlugin`.
It has a public no-arg constructor, owns its `SecretCandidateStore` internally,
and runs its own expiry scheduler on a daemon thread (`onStart`; shut down and
cleared in `onClose`). No Spring wiring, no host-shared store.

## Contributions

All through the typed catalog points (see the
[plugin API README](../veto-plugin-api/README.md)):

- `veto:input-protection` / `veto:file-protection` — capture detected secrets as
  scoped SECRET_REF markers before text enters history.
- `veto:file-observation` — mask plain segments of a `view_file` observation
  while preserving live SECRET_REF markers.
- `veto:observation-middleware` — the session-less masking contribution; the
  host chains it as the observation/ingress masking floor.
- `veto:session-lifecycle` — owner/session/agent transitions map to store
  boundaries (`openOwner`/`closeOwner`/`retireSession`/`discardAgent`).
- `veto:frontend` — the browser reveal component (`show` action reads a live
  reference for its owning scope only).
- `veto:tools` — `import_detected_credential`: imports a session SECRET_REF into
  the owner's encrypted vault after approval (effect `PRIVILEGED`, contributed
  as a direct `Tool` implementation).

## SLM-driven detection, degraded mode

Detection — what counts as a secret — is model-primary. `SlmSecretDetector`
asks the detection model for the exact secret substrings in a text and computes
spans deterministically (every occurrence of each verified substring, replaced
with `[REDACTED_SLM_DETECTED]`); the model can choose content but cannot
corrupt offsets. Model spans are unioned with the deterministic rules — an
available model extends, never replaces, the deterministic floor. The same
injected `SecretDetector` drives SECRET_REF capture, the
observation-middleware contribution, and `view_file` segment masking.

The deterministic rules carry two category classes. Capture-worthy categories
(the credential patterns plus `slm-detected`) may become SECRET_REF candidates.
Mask-only categories — IPv4/IPv6 addresses, emails, internal hostnames,
credential URLs and credential paths, bare 32+ char tokens, and proprietary
physics parameters — are masked everywhere but never captured. The outbound
veto gateway routes every payload through the observation-middleware chain, so
these PII/network identifiers are masked before data leaves the host.

When no detection model is granted or its output is unusable, detection uses
the deterministic rules alone and logs the degradation once. The fixed rules
are the fail-safe net, not the primary path.

## Host-service contracts

`top.focess.veto.secret.api` is this plugin's own host-service surface, not part
of the shared plugin API — the generic layers stay plugin-agnostic so plugins
remain portable to other agent clients:

- `CredentialImportAccess` / `CredentialWriter` — the vault-backed import gate,
  obtained via `PluginContext.service(CredentialImportAccess.class)`. Without
  it, everything else works and credential import fails at call time.
- `SecretDetectionModel` — a minimal model port
  (`isAvailable()` + `Optional<String> complete(String prompt)`), obtained via
  `PluginContext.service(SecretDetectionModel.class)`. The host supplies only a
  completion; the plugin owns prompting and parsing, so any agent client can
  back it with any model. Without it, detection runs in degraded deterministic
  mode.

The store enforces owner/session/agent scope isolation, TTL expiry, capacity
bounds, and import-once receipts; raw values never leave the store except
through the scope-checked reveal and the authorized import writer.

## Verify

```sh
./gradlew :veto-secret-protection:test
```
