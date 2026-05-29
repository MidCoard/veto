#!/usr/bin/env python3
"""
Project Veto — Training Data Generator
Generates 500+ synthetic examples covering:
  1. Veto decision (pass/redact/block with reason + confidence)
  2. Secrets/redaction detection
  3. Structural constraint enforcement

All output conforms to the GBNF grammar in grammars/veto-output.gbnf.
"""
import json
import os
import random as _random
from typing import Any
from datetime import datetime

random = _random.Random(42)

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "data")
os.makedirs(OUT_DIR, exist_ok=True)

# ---------------------------------------------------------------------------
# Synthetic data templates
# ---------------------------------------------------------------------------

HARMLESS_PAYLOADS = [
    "The weather today is sunny with a high of 25 C.",
    "The refactored function computeGain() returns a value of 0.85.",
    "The UI renders a table with 12 rows and 4 columns.",
    "Build completed successfully in 45.2 seconds.",
    "Pushing commit a1b2c3d4 to branch main.",
    "The CI pipeline passed all 128 test cases.",
    "Updated README with new installation instructions.",
    "The deployment finished without errors.",
    "All unit tests pass in 3.2 seconds.",
    "Refactoring class VectorMath to use double precision.",
    "Output log level set to INFO for production.",
    "Cache hit ratio improved to 94.7 percent.",
    "Memory usage dropped by 120 MB after optimization.",
    "CPU utilization averages 34 percent under load.",
    "Thread pool size adjusted to 8 workers.",
    "Session timeout set to 1800 seconds as per policy.",
    "GraphQL query returned 247 nodes in 12ms.",
    "Webhook registered for deployment events on channel #ops.",
    "Retry policy configured with 3 attempts and exponential backoff.",
    "Rate limit set to 1000 requests per minute per tenant.",
    "The radix sort completes in O(n log n) for the dataset.",
    "Docker image size reduced from 1.2 GB to 480 MB.",
    "Kubernetes cluster scaled to 12 nodes in us-east-1.",
    "Database query plan shows index-only scan on table orders.",
    "The FFT window function uses Hamming with alpha=0.54.",
    "ADC sampling rate set to 48 kHz with 16-bit resolution.",
    "Phase-locked loop locks within 150 us under test conditions.",
    "The Kalman filter covariance matrix converges after 4 iterations.",
    "Chebyshev Type II filter with 40 dB stopband attenuation.",
    "The state vector contains 6 elements: position and velocity in 3D.",
    "Log file rotation configured at 100 MB per file.",
    "Health check endpoint returns 200 at /api/health.",
    "Compression ratio achieved: 4.2x on the waveform data.",
    "The correlation coefficient between channels is 0.997.",
    "Publish-subscribe topic has 12 active subscribers.",
    "Buffer pool extended to 512 pages for the streaming pipeline.",
    "Median response time under load is 247 ms.",
    "The async event bus processed 12K events in 3 seconds.",
    "Sorted list merge uses O(n+m) time complexity.",
    "Interpolation spline uses 64 control points for the surface.",
]

SECRETS_PAYLOADS = [
    ("The database connection uses jdbc:postgresql://prod-db.internal:5432/mydb with user admin_pass=secret123",
     {"secrets_found": True, "redacted_fields": [
         {"field": "connection_string", "type": "database_uri"},
         {"field": "password", "type": "credential"}
     ]}),
    ("Configure the API key sk-2a3b4c5d6e7f8g9h0i1j2k3l4m5n6o7p in the environment variable VETO_API_KEY",
     {"secrets_found": True, "redacted_fields": [
         {"field": "api_key", "type": "secret_token"}
     ]}),
    ("The SSH connection to root@192.168.1.100 uses key file /home/user/.ssh/id_rsa",
     {"secrets_found": True, "redacted_fields": [
         {"field": "ssh_connection", "type": "ssh_credential"},
         {"field": "ip_address", "type": "ipv4"},
         {"field": "key_path", "type": "credential_path"}
     ]}),
    ("Set the JWT token to eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gWFOwmqQ for authentication",
     {"secrets_found": True, "redacted_fields": [
         {"field": "jwt_token", "type": "auth_token"}
     ]}),
    ("Email the report to admin@internal.corp.com and cc engineering@company.com",
     {"secrets_found": True, "redacted_fields": [
         {"field": "email_admin", "type": "email"},
         {"field": "email_cc", "type": "email"}
     ]}),
    ("Connect to https://user:password@jenkins.internal.company.com/job/deploy",
     {"secrets_found": True, "redacted_fields": [
         {"field": "jenkins_url", "type": "authenticated_url"}
     ]}),
    ("The MySQL read-only replica is at mysql://reader:readonly_pass@replica-db.internal:3306/analytics",
     {"secrets_found": True, "redacted_fields": [
         {"field": "mysql_uri", "type": "database_uri"},
         {"field": "password", "type": "credential"}
     ]}),
    ("AWS secret access key: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY for S3 uploads",
     {"secrets_found": True, "redacted_fields": [
         {"field": "aws_secret_key", "type": "secret_token"}
     ]}),
    ("Deploy to https://jenkins-deployer:token-abc-123@jenkins.prod.internal/v2/build",
     {"secrets_found": True, "redacted_fields": [
         {"field": "jenkins_url", "type": "authenticated_url"},
         {"field": "auth_token", "type": "credential"}
     ]}),
    ("Redis password set to r3d1s_p@ss in /etc/veto/redis.conf",
     {"secrets_found": True, "redacted_fields": [
         {"field": "redis_password", "type": "credential"},
         {"field": "config_path", "type": "credential_path"}
     ]}),
    ("Client cert at /home/deploy/.certs/veto-client.pem with passphrase c3rt_p@ss",
     {"secrets_found": True, "redacted_fields": [
         {"field": "cert_path", "type": "credential_path"},
         {"field": "passphrase", "type": "credential"}
     ]}),
    ("Slack webhook URL: https://hooks.slack.com/services/T00/B00/xxxxx for channel alerts",
     {"secrets_found": True, "redacted_fields": [
         {"field": "webhook_url", "type": "authenticated_url"}
     ]}),
    ("GPG key fingerprint: A1B2 C3D4 E5F6 A7B8 C9D0 E1F2 A3B4 C5D6 E7F8 A9B0 for code signing",
     {"secrets_found": True, "redacted_fields": [
         {"field": "gpg_fingerprint", "type": "crypto_key"}
     ]}),
    ("K8s service account token with full JWT payload for deployer SA",
     {"secrets_found": True, "redacted_fields": [
         {"field": "k8s_sa_token", "type": "auth_token"}
     ]}),
    ("Internal LDAP bind DN: cn=veto-svc,ou=system,dc=internal,dc=corp with password ld@p_b1nd",
     {"secrets_found": True, "redacted_fields": [
         {"field": "ldap_bind_dn", "type": "credential"},
         {"field": "ldap_password", "type": "credential"}
     ]}),
    ("Cloud API: https://api.internal.corp.com/v2 with bearer token Bearer ya29_a0AfH6SMCAR",
     {"secrets_found": True, "redacted_fields": [
         {"field": "api_endpoint", "type": "internal_url"},
         {"field": "bearer_token", "type": "auth_token"}
     ]}),
    ("Private Docker registry: docker login registry.internal.corp.com -u veto-bot -p dckr_pat_12345",
     {"secrets_found": True, "redacted_fields": [
         {"field": "registry_auth", "type": "credential"},
         {"field": "registry_url", "type": "internal_url"}
     ]}),
    ("MongoDB Atlas: mongodb+srv://veto-app:atlas_mongo_pass@cluster0.xxxxx.mongodb.net/veto",
     {"secrets_found": True, "redacted_fields": [
         {"field": "mongo_uri", "type": "database_uri"},
         {"field": "password", "type": "credential"}
     ]}),
    ("GitHub token: ghp_xxxxxxxxxxxxxxxxxxxx for pushing to internal-veto-org/repo",
     {"secrets_found": True, "redacted_fields": [
         {"field": "github_token", "type": "secret_token"}
     ]}),
    ("Twilio account SID: ACxxxxxxxxxxxxxxxxxxxxxxxx with auth token xxxxxxxxxxxxxxxxxxxxxxxx",
     {"secrets_found": True, "redacted_fields": [
         {"field": "twilio_sid", "type": "api_credential"},
         {"field": "twilio_token", "type": "secret_token"}
     ]}),
    ("SSH private key path: /root/.ssh/deploy_key for git clone git@github.com:veto-org/deploy.git",
     {"secrets_found": True, "redacted_fields": [
         {"field": "ssh_key_path", "type": "credential_path"},
         {"field": "git_url", "type": "credential_url"}
     ]}),
]

PHYSICS_PAYLOADS_VALID = [
    "The normalized peak magnitude is 1.0.",
    "The antenna pattern uses discrete sidelobe suppression at angles 30, 45, 60 degrees.",
    "Array factor is computed using discrete element positions in meters.",
    "The beamformer output is normalized to unity gain in the main lobe direction.",
    "Scattering matrix eigenvalues are computed on a discrete grid of 1024 points.",
    "The phased array uses discrete element excitation weights normalized to unity.",
    "Normalized radar cross section at boresight is 0 dBsm using discrete frequency bins.",
    "AESA beam steering uses discrete phase shifters with 6-bit resolution.",
    "The DOA estimation uses MUSIC algorithm on discrete sensor array outputs.",
    "Pulse compression uses discrete matched filter coefficients normalized to 1.0.",
    "Range-Doppler map generated on 256x128 discrete grid with normalized power levels.",
    "STAP weights computed using discrete training samples from 32 range gates.",
    "The monopulse ratio uses discrete sum and difference channels with normalized gain.",
    "CFAR threshold computed on discrete reference cells with PFa = 1e-6.",
    "The adaptive beamformer uses discrete snapshots with diagonal loading factor 0.1.",
    "ISAR image formed on discrete range and cross-range grids with peak normalization.",
    "The Kalman filter uses discrete state transition matrix with unit normalization.",
    "Clutter map updated using discrete power measurements normalized to noise floor.",
    "The DFT-based channelizer uses 1024 discrete frequency bins with uniform spacing.",
    "Matched illumination waveform designed on discrete time grid with unit energy.",
]

PHYSICS_PAYLOADS_INVALID = [
    ("Array power is computed using MIN_DB threshold of -30.5 for noise floor estimation.",
     {"valid": False, "violations": [{
         "type": "normalization_violation",
         "field": "MIN_DB",
         "severity": "high",
         "message": "Using arbitrary MIN_DB threshold instead of normalized peak-to-peak analysis"
     }]}),
    ("The continuous approximation uses Taylor window with 35 dB sidelobe taper.",
     {"valid": False, "violations": [{
         "type": "continuity_violation",
         "field": "continuous_approximation",
         "severity": "high",
         "message": "Continuous approximation used; enforce discrete physics solutions"
     }]}),
    ("The array_5_config = { elements: 8, spacing: 0.5, weighting: chebyshev, taper_db: 40.0 }",
     {"valid": False, "violations": [{
         "type": "proprietary_structure",
         "field": "array_5_config",
         "severity": "high",
         "message": "Proprietary array configuration disclosed; must redact before outbound"
     }]}),
    ("Normalization factor peak_max_avg = 2.5 was used for the correlation matrix scaling.",
     {"valid": False, "violations": [{
         "type": "normalization_violation",
         "field": "peak_max_avg",
         "severity": "medium",
         "message": "Non-normalized peak value used; expected 1.0 for absolute normalization"
     }]}),
    ("Simulation uses gain_norm_max = 3.2 for the amplifier chain linearity check.",
     {"valid": False, "violations": [{
         "type": "normalization_violation",
         "field": "gain_norm_max",
         "severity": "high",
         "message": "Gain normalization factor exceeds unity; potential proprietary calibration data"
     }]}),
    ("Continuous wave approximation used for the multi-path fading channel model.",
     {"valid": False, "violations": [{
         "type": "continuity_violation",
         "field": "wave_approximation",
         "severity": "medium",
         "message": "Continuous wave approximation used; enforce discrete time-domain solutions"
     }]}),
    ("The array_12_config = { elements: 16, spacing_m: 0.045, freq_ghz: 10.0, taper: hamming, weight_db: 30 }",
     {"valid": False, "violations": [{
         "type": "proprietary_structure",
         "field": "array_12_config",
         "severity": "high",
         "message": "Proprietary array configuration disclosed; must redact before outbound"
     }]}),
    ("The beam pattern uses norm_peak_db = -1.2 for the adaptive null placement.",
     {"valid": False, "violations": [{
         "type": "normalization_violation",
         "field": "norm_peak_db",
         "severity": "medium",
         "message": "Non-normalized dB-based peak value; use absolute peak normalization of 1.0"
     }]}),
    ("RCS calibration uses amplitude_max = 12.7 for the corner reflector reference.",
     {"valid": False, "violations": [{
         "type": "normalization_violation",
         "field": "amplitude_max",
         "severity": "high",
         "message": "Un-normalized amplitude value exposed; calibrate to unity reference"
     }]}),
    ("Continuous-time transfer function used for the loop filter design with bandwidth 10 MHz.",
     {"valid": False, "violations": [{
         "type": "continuity_violation",
         "field": "transfer_function",
         "severity": "low",
         "message": "Continuous-time model used; convert to discrete z-domain representation"
     }]}),
    ("Phase noise approximation uses continuous PSD integration from 1 Hz to 1 MHz.",
     {"valid": False, "violations": [{
         "type": "continuity_violation",
         "field": "phase_noise_psd",
         "severity": "medium",
         "message": "Continuous PSD integration used; enforce discrete frequency bin summation"
     }]}),
    ("The array_3_config is proprietary and should never be sent outbound.",
     {"valid": False, "violations": [{
         "type": "proprietary_structure",
         "field": "array_3_config",
         "severity": "critical",
         "message": "Explicit proprietary configuration reference: must block entirely"
     }]}),
    ("Normalization of the covariance matrix uses trace_max_avg = 8.5 for conditioning.",
     {"valid": False, "violations": [{
         "type": "normalization_violation",
         "field": "trace_max_avg",
         "severity": "medium",
         "message": "Non-normalized trace value; expected 1.0 for absolute normalization"
     }]}),
]


def generate_parametrized_variations(base_samples: list, target: int = 500) -> list:
    """Multiply samples by rephrasing instructions to reach target count."""
    import copy
    prefixes = [
        "Evaluate this payload:",
        "Check the following:",
        "Process this data:",
        "Inspect this text:",
        "Review for safety:",
        "Analyze for compliance:",
        "Examine this content:",
        "Screen this payload:",
        "Vet this data:",
        "Validate this text:",
    ]
    result = list(base_samples)
    i = 0
    while len(result) < target and i < 20:
        for s in copy.deepcopy(base_samples):
            if len(result) >= target:
                break
            prefix = prefixes[i % len(prefixes)]
            # Original instruction has prefix before colon; replace it
            colon_idx = s["instruction"].find(":")
            if colon_idx > 0:
                # Use text after the colon, prepend new prefix
                after_colon = s["instruction"][colon_idx + 1:]
                new_inst = prefix + after_colon
            else:
                new_inst = prefix + " " + s["instruction"]
            s["instruction"] = new_inst
            s["id"] = s.get("id", "") + "-v" + str(i)
            result.append(s)
        i += 1
    return result


def generate_veto_decision_samples() -> list[dict]:
    """Generate samples for veto decision task (pass/redact/block)."""
    samples = []
    for payload in HARMLESS_PAYLOADS:
        samples.append({
            "instruction": f"Analyze the following payload and decide if it should pass, be redacted, or blocked:\n{payload}",
            "output": json.dumps({
                "veto_decision": "pass",
                "data": {
                    "reason": "Payload contains no sensitive information or policy violations.",
                    "confidence": 0.98
                }
            })
        })

    for payload, redact_info in SECRETS_PAYLOADS:
        # Redact decision
        redact_fields = [f["field"] for f in redact_info["redacted_fields"]]
        samples.append({
            "instruction": f"Analyze the following payload and decide if it should pass, be redacted, or blocked:\n{payload}",
            "output": json.dumps({
                "veto_decision": "redact",
                "data": {
                    "reason": f"Sensitive information detected: {', '.join(redact_fields)}. "
                              f"Redacted {len(redact_fields)} field(s).",
                    "confidence": 0.95,
                    "redacted_fields": redact_fields
                }
            })
        })

    for payload, viol_info in PHYSICS_PAYLOADS_INVALID:
        v = viol_info["violations"][0]
        samples.append({
            "instruction": f"Analyze the following payload and decide if it should pass, be redacted, or blocked:\n{payload}",
            "output": json.dumps({
                "veto_decision": "block",
                "data": {
                    "reason": f"Structural constraint violation: {v['message']} (severity: {v['severity']})",
                    "confidence": 0.93,
                    "violation_type": v["type"]
                }
            })
        })

    # Mixed: some payloads with harmless content but suspicious context
    mixed = [
        "The simulation log shows node 192_168_1_100 responded with status OK.",
        "Check the config at path /etc/veto/secrets.json for the latest deployment.",
    ]
    for payload in mixed:
        samples.append({
            "instruction": f"Analyze the following payload and decide if it should pass, be redacted, or blocked:\n{payload}",
            "output": json.dumps({
                "veto_decision": "redact",
                "data": {
                    "reason": "Potential internal path or obfuscated IP detected.",
                    "confidence": 0.78
                }
            })
        })

    return samples


def generate_redaction_samples() -> list[dict]:
    """Generate samples for secrets/redaction detection task."""
    samples = []
    for payload in HARMLESS_PAYLOADS:
        samples.append({
            "instruction": f"Scan the following payload for secrets, credentials, and sensitive information:\n{payload}",
            "output": json.dumps({
                "secrets_found": False,
                "redacted_fields": [],
                "safe_payload": payload
            })
        })

    for payload, redact_info in SECRETS_PAYLOADS:
        samples.append({
            "instruction": f"Scan the following payload for secrets, credentials, and sensitive information:\n{payload}",
            "output": json.dumps({
                "secrets_found": True,
                "redacted_fields": redact_info["redacted_fields"],
                "safe_payload": f"[REDACTED_PAYLOAD: {len(redact_info['redacted_fields'])} fields redacted]"
            })
        })

    for payload in PHYSICS_PAYLOADS_VALID:
        samples.append({
            "instruction": f"Scan the following payload for secrets, credentials, and sensitive information:\n{payload}",
            "output": json.dumps({
                "secrets_found": False,
                "redacted_fields": [],
                "safe_payload": payload
            })
        })

    return samples


def generate_structural_constraint_samples() -> list[dict]:
    """Generate samples for structural constraint validation task."""
    samples = []
    for payload in PHYSICS_PAYLOADS_VALID:
        samples.append({
            "instruction": f"Validate the following payload against structural constraints (normalization, discrete physics, no proprietary data):\n{payload}",
            "output": json.dumps({
                "valid": True,
                "violations": [],
                "redacted": payload
            })
        })

    for payload, viol_info in PHYSICS_PAYLOADS_INVALID:
        samples.append({
            "instruction": f"Validate the following payload against structural constraints (normalization, discrete physics, no proprietary data):\n{payload}",
            "output": json.dumps({
                "valid": False,
                "violations": viol_info["violations"],
                "redacted": f"[VIOLATION: {viol_info['violations'][0]['message']}]"
            })
        })

    # Also test clean payloads through the constraint system
    for payload in HARMLESS_PAYLOADS:
        samples.append({
            "instruction": f"Validate the following payload against structural constraints (normalization, discrete physics, no proprietary data):\n{payload}",
            "output": json.dumps({
                "valid": True,
                "violations": [],
                "redacted": payload
            })
        })

    return samples


def main():
    all_samples = []
    all_samples += generate_veto_decision_samples()
    all_samples += generate_redaction_samples()
    all_samples += generate_structural_constraint_samples()

    # Determine task type for each
    for i, s in enumerate(all_samples):
        if "decide if it should pass" in s["instruction"]:
            task = "veto_decision"
        elif "Scan the following payload for secrets" in s["instruction"]:
            task = "redaction"
        elif "Validate the following payload against structural constraints" in s["instruction"]:
            task = "structural_constraint"
        else:
            task = "unknown"
        s["task"] = task
        s["id"] = f"veto-train-{i:04d}"

    # Parametrize to reach 500+ samples
    all_samples = generate_parametrized_variations(all_samples, target=600)

    random.shuffle(all_samples)

    # Split 80/20
    split = int(len(all_samples) * 0.8)
    train_set = all_samples[:split]
    eval_set = all_samples[split:]

    # Write
    train_path = os.path.join(OUT_DIR, "veto_training_data.jsonl")
    eval_path = os.path.join(OUT_DIR, "veto_eval_data.jsonl")
    report_path = os.path.join(OUT_DIR, "dataset_report.json")

    with open(train_path, "w", encoding="utf-8") as f:
        for s in train_set:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")

    with open(eval_path, "w", encoding="utf-8") as f:
        for s in eval_set:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")

    # Count by task
    task_counts = {}
    for s in all_samples:
        task_counts[s["task"]] = task_counts.get(s["task"], 0) + 1

    report = {
        "total_generated": len(all_samples),
        "train_count": len(train_set),
        "eval_count": len(eval_set),
        "task_distribution": task_counts,
        "generated_at": datetime.utcnow().isoformat(),
        "seed": 42,
    }
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    print(f"[OK] Generated {len(all_samples)} total samples")
    print(f"  Train: {len(train_set)}, Eval: {len(eval_set)}")
    print(f"  Tasks: {task_counts}")
    print(f"  Written to {OUT_DIR}")


if __name__ == "__main__":
    main()
