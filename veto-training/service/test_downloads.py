import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from download_worker import download


class DownloadWorkerTests(unittest.TestCase):
    def test_pins_revision_and_rejects_oversized_plan_before_weight_download(self):
        base = Path('work/tmp/training-service/tests')
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as directory:
            root = Path(directory)
            files = [SimpleNamespace(rfilename='config.json', size=2),
                     SimpleNamespace(rfilename='model.safetensors', size=4)]
            api = SimpleNamespace(model_info=lambda *args, **kwargs: SimpleNamespace(sha='a'*40, siblings=files))
            calls = []
            def fetch(repo, filename, *, revision, local_dir):
                calls.append((repo, filename, revision))
                path = local_dir / filename
                path.write_bytes(b'{}' if filename == 'config.json' else b'data')
                return str(path)
            modules = {'huggingface_hub': SimpleNamespace(HfApi=lambda:api, hf_hub_download=fetch)}
            request = dict(path=str(root/'model'), repo='org/model', revision='main', maxBytes=5, reserveBytes=0)
            with patch.dict('sys.modules', modules):
                with self.assertRaisesRegex(ValueError, '上限'):
                    download(request)
                self.assertEqual([], calls)
                request['maxBytes'] = 10
                download(request)
                self.assertEqual(['a'*40, 'a'*40], [call[2] for call in calls])
                self.assertEqual(6, json.loads((root/'model/.download-plan.json').read_text())['total'])
