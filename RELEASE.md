# Veto @VETO_VERSION@ — local release

This guide is for the assembled local release bundle. You need JDK 25 and a running
PostgreSQL database. For local secret detection, provide a compatible llama.cpp
`llama-server` on `PATH`. The bundle includes the secret-detection model and plugin;
it does not include a JDK, database, web UI or the separate advisory screening model. Veto is under active development; this bundle is not a certification
of hostile-code isolation on your operating system.

## Contents

- `core/veto-core.jar`: backend server.
- `terminal/veto-terminal-@VETO_VERSION@.zip`: terminal client distribution.
- `start-core.bat` and `start-core.sh`: backend launchers using Java from `PATH`.
- `models/veto-secret-slm.gguf`: ready-to-use local secret-detection model.
- `plugins/top.focess.secret-protection/`: secret-protection plugin and libraries.
- `config/application.yml`: bundled model routing.
- `VERSION` and `LICENSE`: version and licensing information.

Secret protection is included. Additional optional plugin packages can be installed
under `plugins/` before starting the backend. No Python environment, model setup
or model download is needed for the included secret model. Local inference starts
when the plugin receives its local-model service; check startup logs for availability.

## Start the backend

Open a terminal in the extracted bundle directory. Verify `java -version` reports
JDK 25. The development datasource defaults are database `veto` on
`localhost:5432`, user `veto`, with an empty password. Supply your own Spring Boot
datasource settings when needed, such as `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`. Hibernate schema
updates are a development convenience, not a production migration strategy.

Set an absolute audit directory before starting. Keep it outside agent-accessible
workspaces. The following examples use an `audit` directory in this bundle:

macOS/Linux:

```sh
export VETO_AUDIT_DIR="$PWD/audit"
sh ./start-core.sh
```

Windows PowerShell:

```powershell
$env:VETO_AUDIT_DIR = Join-Path (Get-Location).Path "audit"
.\start-core.bat
```

Keep the console open and check the startup log for readiness. The default REST
and application WebSocket port is 8443; terminal IPC uses `tcp://127.0.0.1:5555`.
Configuration may override these defaults. Create the initial administrator using
the separately deployed UI or `/api/auth/setup`; subsequent signup defaults to
invite-only.

## Start the terminal or web UI

Extract the terminal ZIP, then open a second terminal in its extracted distribution
directory. Pass an absolute workspace path:

macOS/Linux:

```sh
sh bin/veto-terminal --workspace /absolute/path/to/workspace
```

Windows PowerShell:

```powershell
.\bin\veto-terminal.bat --workspace C:\path\to\workspace
```

The web UI is distributed separately in the
[veto-ui repository](https://github.com/MidCoard/veto-ui).

## Operating boundaries

The default `FULL_ACCESS` deployer policy does not confine the agent to a workspace.
Configure a narrower policy and explicit workspace roots when confinement is needed.
Platform execution controls differ; see the matching source revision's security
documentation before deploying on a multi-user host.

Local SLM screening is optional and advisory. To enable it, provide `llama-server`
on `PATH` and configure a compatible GGUF model; confirm model loading in the log.
Without it, deterministic screening remains active. Do not assume semantic
screening is active merely because a model path is configured.

Installed Java and JavaScript plugins run as trusted server-user code. Plugin
packages remain isolated from the core server JAR. Administrators can use
`POST /api/plugins/{id}/disable` and `POST /api/plugins/{id}/enable` to toggle an
installed package without restarting; the startup-disabled configuration takes
effect again after restart.

Keep audit/vault data and database credentials private. See `LICENSE` for the
GNU Affero General Public License v3.0-only terms.
