"""Locations for disposable training outputs, independent of the current directory."""

import os
from pathlib import Path


TRAINING_WORK = Path(__file__).resolve().parents[3] / "work" / "tmp" / "training"


def configure_caches():
    """Keep default downloads in the workspace while respecting caller overrides."""
    os.environ.setdefault("HF_HOME", str(TRAINING_WORK / "hf-cache"))
    os.environ.setdefault("PIP_CACHE_DIR", str(TRAINING_WORK / "pip-cache"))
