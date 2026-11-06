"""Run the live Veto evaluation slice and write Inspect logs plus a compact summary."""

import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import uuid

from inspect_ai import eval as inspect_eval

from .agentdojo import AgentDojoServer
from .client import Settings
from .tasks import build_tasks


def source_fingerprint(root):
    """Hash present repository files, including new source but excluding ignored data."""
    names = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=root,
    ).decode("utf-8").split("\0")
    digest = hashlib.sha256()
    for name in sorted(set(names)):
        path = root / name
        if not name or not path.is_file():
            continue
        digest.update(name.encode("utf-8") + b"\0")
        with path.open("rb") as source:
            while block := source.read(256 * 1024):
                digest.update(block)
        digest.update(b"\0")
    return digest.hexdigest()


def summarize(logs):
    """Keep errors outside the score denominator and expose incomplete coverage."""
    tasks = []
    for log in logs:
        samples = log.samples or []
        successful = [s for s in samples if s.error is None and s.scores]
        metrics = {}
        for sample in successful:
            for name, value in sample.scores.items():
                metrics.setdefault(name, []).append(value.value)
        sample_summaries = []
        for sample in samples:
            result = sample.metadata.get("result") or sample.metadata.get("partial_result", {})
            measurements = {}
            for turn in result.get("records", []):
                for usage in turn.get("llmUsage", []) or []:
                    # Keep calls made by child agents and rewound work. Count each
                    # attributed call once, even when repeated in record snapshots.
                    key = usage.get("modelCallId") or (
                        turn.get("turnNumber"), len(measurements)
                    )
                    measurements[key] = usage
            usage = None
            if measurements:
                usage = {
                    "measured_calls": len(measurements),
                    "input_tokens": sum(item["inputTokens"] for item in measurements.values()),
                    "output_tokens": sum(item["outputTokens"] for item in measurements.values()),
                    "models": sorted({
                        item["model"] for item in measurements.values() if item.get("model")
                    }),
                }
            sample_summaries.append({
                "id": sample.id,
                "epoch": sample.epoch,
                "error": sample.error.message if sample.error else None,
                "scores": {key: score.value for key, score in (sample.scores or {}).items()},
                "workspace": sample.metadata.get("workspace"),
                "duration_seconds": result.get("duration_seconds"),
                "usage": usage,
                "approvals": len(result.get("approvals", [])),
                "questions": len(result.get("questions", [])),
            })
        tasks.append({
            "task": log.eval.task,
            "status": log.status,
            "planned": log.results.total_samples if log.results else len(samples),
            "scored": len(successful),
            "errors": [
                {"id": sample.id, "epoch": sample.epoch, "message": sample.error.message}
                for sample in samples if sample.error
            ],
            "metrics": {
                name: {"mean": sum(values) / len(values), "count": len(values)}
                for name, values in metrics.items()
            },
            "samples": sample_summaries,
        })
    return tasks


def render_report(summary):
    """Present observed outcomes and incomplete coverage without inferring success."""
    configuration = summary["configuration"]
    lines = [
        "Veto agent evaluation",
        f"Suite: {configuration['suite']}; approval policy: {configuration['approval_policy']}",
        f"Timeout: {configuration['timeout_seconds']:g}s per request; cost: not measured",
        "These are live agent results. Passing adapter/build tests is not a task-success score.",
        "",
    ]
    for task in summary["tasks"]:
        lines.append(f"{task['task']}: {task['scored']}/{task['planned']} samples scored, "
                     f"{len(task['errors'])} errors")
        for sample in task["samples"]:
            scores = sample["scores"]
            outcome = "ERROR" if sample["error"] else (
                "PASS" if scores.get("task_success") == 1 else
                "FAIL" if scores.get("task_success") == 0 else "NOT SCORED"
            )
            lines.append(f"  {sample['id']} (epoch {sample['epoch']}): {outcome}")
            if "attack_success" in scores:
                attack = "ACHIEVED" if scores["attack_success"] == 1 else "NOT ACHIEVED"
                lines.append(f"    Attack goal: {attack}; this does not imply user-task success.")
            if sample["error"]:
                lines.append("    Error: " + sample["error"])
            duration = sample["duration_seconds"]
            if duration is not None:
                lines.append(f"    Duration: {duration:.2f}s")
            usage = sample["usage"]
            if usage:
                lines.append(f"    Model: {', '.join(usage['models'])}; calls: {usage['measured_calls']}; "
                             f"tokens: {usage['input_tokens']} input / {usage['output_tokens']} output")
            else:
                lines.append("    Model usage: unavailable")
            lines.append(f"    Approvals: {sample['approvals']}; questions: {sample['questions']}")
            lines.append(f"    Workspace: {sample['workspace']}")
        lines.append("")
    lines.extend([
        "Errors and unscored samples are excluded from score denominators, never counted as defense successes.",
        "Read summary.json for metrics/provenance and logs/*.eval for execution evidence.",
        "This bounded subset is not a full benchmark or a general security guarantee.",
    ])
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8443")
    parser.add_argument("--username", default=os.environ.get("VETO_TEST_USERNAME"))
    parser.add_argument("--password-env", default="VETO_TEST_PASSWORD")
    parser.add_argument("--pattern", required=True, help="Pattern resolved through the active model-tier profile")
    parser.add_argument(
        "--plugins",
        default="top.focess.llm-providers,top.focess.builtin,top.focess.secret-protection",
    )
    parser.add_argument("--suite", choices=("smoke", "agentdojo", "all"), default="smoke")
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--epochs", type=int, default=1)
    parser.add_argument("--user-tasks", default="user_task_0")
    parser.add_argument("--injection-tasks", default="injection_task_0")
    root = Path(__file__).resolve().parents[3]
    parser.add_argument("--output", type=Path, default=root / "work/tmp/veto-evaluations")
    args = parser.parse_args()
    password = os.environ.get(args.password_env)
    if not args.username or not password:
        parser.error("set VETO_TEST_USERNAME and the password environment variable")
    if not 0 < args.timeout <= 1800 or not 1 <= args.epochs <= 10:
        parser.error("timeout must be in (0, 1800] seconds and epochs in [1, 10]")
    settings = Settings(
        args.base_url, args.username, password, args.pattern,
        plugins=tuple(args.plugins.split(",")), timeout=args.timeout,
    )
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    output = args.output.resolve() / run_id
    output.mkdir(parents=True)
    provenance = {
        "execution": "live-veto",
        "veto_pattern": args.pattern,
        "plugins": list(settings.plugins),
        "suite": args.suite,
        "user_tasks": args.user_tasks.split(","),
        "injection_tasks": args.injection_tasks.split(","),
        "source_sha256": source_fingerprint(root),
        "git_commit": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=root, text=True
        ).strip(),
        "dependencies": {
            dist.metadata["Name"]: dist.version
            for dist in importlib.metadata.distributions()
        },
        "approval_policy": "screened-one-call-v1",
        "epochs": args.epochs,
        "timeout_seconds": args.timeout,
        "cost": None,
        "notes": (
            "Veto chooses and calls the real model. Inspect has no default model. "
            "This is a subset, not a leaderboard run."
        ),
    }
    (output / "configuration.json").write_text(json.dumps(provenance, indent=2), encoding="utf-8")
    with ExitStack() as resources:
        server = resources.enter_context(AgentDojoServer()) if args.suite != "smoke" else None
        tasks = build_tasks(
            settings, output, args.suite, server,
            provenance["user_tasks"], provenance["injection_tasks"],
        )
        logs = inspect_eval(
            tasks, model=None, log_dir=str(output / "logs"),
            max_samples=1, max_tasks=1, epochs=args.epochs,
            fail_on_error=False, display="plain", metadata=provenance,
            log_shared=False, ctl_server=False,
        )
    summary = {"configuration": provenance, "tasks": summarize(logs)}
    (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    report = render_report(summary)
    (output / "report.txt").write_text(report, encoding="utf-8")
    print(report)
    print("Report: " + str(output / "report.txt"))
    return int(any(
        task["status"] != "success" or task["errors"] or task["scored"] == 0
        or task["scored"] != task["planned"]
        for task in summary["tasks"]
    ))


if __name__ == "__main__":
    raise SystemExit(main())
