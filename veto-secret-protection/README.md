# Secret protection plugin

The built-in secret-protection plugin: a self-contained `VetoPlugin`
(`top.focess.veto.secret.SecretProtectionPlugin`) discovered by the host through
`ServiceLoader` via `META-INF/services/top.focess.veto.api.plugin.VetoPlugin`.
It has a public no-arg constructor, owns its `SecretCandidateStore` internally,
and runs its own expiry scheduler on a daemon thread (`onStart`; shut down and
cleared in `onClose`). No Spring wiring, no host-shared store.

## Contributions

All through the typed catalog points (see the
[Veto API README](../veto-api/README.md)):

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
- `veto:native-tools` — `import_detected_credential`: imports a session
  SECRET_REF into the owner's encrypted vault after approval (capability
  `PRIVILEGED`, contributed as an in-process `CapabilityTool` that the host
  executes through its internal tool state like a built-in native tool).

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

All host contracts now come from veto-api; core never imports plugin classes.

- `api.credentials.CredentialImportAccess` / `CredentialWriter` provide the vault-backed import gate. The returned writer is bound to one exact approved invocation and revalidates owner/session/Agent/reference/service/label before storage. Without this service, import fails at call time while detection remains available.
- `api.llm.PromptRenderer` compiles the plugin's `prompts/secret-detection.mdc`. `MdcSecretDetectionModel` then submits compiled text and the plugin's `grammars/secret-detection.gbnf` through `api.llm.LocalModelCompletion`. The host binds purpose and lifecycle, enforces bounds/deadline and cancels local HTTP work. The plugin owns array parsing and deterministic fallback. Missing resources never trigger an inline-prompt fallback.
- `secret.api.SecretDetectionModel` is an internal detector adapter for plugin tests and implementation, not a host service.

The store enforces owner/session/agent scope isolation, TTL expiry, capacity
bounds, and import-once receipts; raw values never leave the store except
through the scope-checked reveal and the authorized import writer.

## Verify

```sh
./gradlew :veto-secret-protection:test
```
