"""Validate the plugin's synthetic exact-substring extraction training contract."""

import argparse
import json
from pathlib import Path


def validate_record(record):
    if not isinstance(record, dict):
        raise ValueError("Record must be an object")
    for field in ("id", "instruction", "text", "output"):
        if not isinstance(record.get(field), str):
            raise ValueError(f"Missing or invalid {field}")
    if record.get("task") != "secret_extraction" or record.get("synthetic") is not True:
        raise ValueError("Only synthetic secret_extraction examples are accepted")
    if not 10 <= len(record["instruction"]) <= 4096:
        raise ValueError("Compiled instruction must contain 10–4096 characters")
    values = json.loads(record["output"])
    if not isinstance(values, list) or any(
        not isinstance(value, str) or len(value) < 4 or value not in record["text"]
        for value in values
    ):
        raise ValueError("Targets must be a JSON array of exact input substrings")


def validate_file(path):
    seen = set()
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
            validate_record(record)
            pair = (record["instruction"], record["output"])
            if pair in seen:
                raise ValueError("Duplicate instruction/output pair")
            seen.add(pair)
        except ValueError as error:
            raise ValueError(f"{path}:{number}: {error}") from error
    if not seen:
        raise ValueError(f"{path}: empty dataset")
    return len(seen)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps({"data": str(args.data), "validRecords": validate_file(args.data)}))


if __name__ == "__main__":
    main()
