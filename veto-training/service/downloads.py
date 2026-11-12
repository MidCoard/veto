"""Persistent download jobs; partial Hub files belong to the model directory."""
import json
import re
import secrets
import shutil
import threading
import time
from pathlib import Path

from atomic import replace_with_retry


class Downloads:
    def __init__(self, root, state_dir, factory):
        self.root, self.state_dir, self.factory = root, state_dir, factory
        self.path = state_dir / 'downloads.json'
        self.rows = json.loads(self.path.read_text(encoding='utf-8')) if self.path.exists() else []
        self.lock = threading.RLock()
        self.worker = None
        self.current = None
        for row in self.rows:
            if row['status'] in ('discovering', 'downloading'):
                row['status'] = 'paused'

    def save(self):
        temp = self.path.with_suffix('.tmp')
        temp.write_text(json.dumps(self.rows, ensure_ascii=False), encoding='utf-8')
        replace_with_retry(temp, self.path)

    def snapshot(self):
        with self.lock:
            return [dict(row) for row in self.rows if not row.get('archived')
                    and not (row['status'] == 'completed' and row.get('registered'))]

    def start(self, payload, python, env, reserve, maximum, identifier=None):
        with self.lock:
            if self.worker:
                raise ValueError('请先暂停当前下载。')
            token = payload.get('token', '')
            if not isinstance(token, str) or len(token) > 512 or any(ord(c) < 32 for c in token):
                raise ValueError('访问令牌格式无效。')
            if identifier:
                row = next((r for r in self.rows if r['id'] == identifier and r['status'] in ('paused', 'failed')), None)
                if row is None:
                    raise ValueError('此下载不能恢复。')
            else:
                repo = str(payload.get('repo', '')).strip()
                revision = str(payload.get('revisionName', 'main')).strip()
                if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo) or not re.fullmatch(r'[A-Za-z0-9_./-]{1,128}', revision):
                    raise ValueError('请输入有效的 Hugging Face 仓库和版本。')
                if len(self.rows) >= 100:
                    raise ValueError('下载记录已达到 100 条上限。')
                if any(r['repo'] == repo and r['requestedRevision'] == revision and not r.get('archived') for r in self.rows):
                    raise ValueError('此模型已有下载记录，请从该记录继续。')
                identifier = secrets.token_hex(8)
                row = dict(id=identifier, repo=repo, requestedRevision=revision, status='discovering',
                           path=str(self.root / 'veto-training/models/downloads' / identifier), downloaded=0, total=0)
                self.rows.append(row)
            request = dict(repo=row['repo'], revision=row.get('revision', row['requestedRevision']),
                           path=row['path'], reserveBytes=reserve, maxBytes=maximum)
            command_file = self.state_dir / (identifier + '-download.json')
            command_file.write_text(json.dumps(request), encoding='utf-8')
            env = dict(env, HF_HUB_OFFLINE='0', HF_HUB_DISABLE_XET='1', HF_HUB_DOWNLOAD_TIMEOUT='30',
                       HF_HUB_DISABLE_PROGRESS_BARS='0', OMP_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1')
            if token:
                env['HF_TOKEN'] = token
            row.update(status='discovering', message='')
            self.save()
            try:
                self.maximum, self.reserve = maximum, reserve
                self.worker = self.factory([str(python), '-B', str(Path(__file__).with_name('download_worker.py')), str(command_file)],
                                           self.root, env, self.state_dir / (identifier + '-download.log'),
                                           backend=True, memory_limit=2 * 1024**3, on_line=lambda line: self.progress(row, line))
                self.current = row
                self.started = time.monotonic()
            except BaseException:
                row.update(status='failed', message='无法启动模型下载。')
                self.save()
                raise
            return dict(row)

    def progress(self, row, line):
        try:
            data = json.loads(line)
        except ValueError:
            return
        with self.lock:
            if row['status'] not in ('discovering', 'downloading'):
                return
            for key in ('downloaded', 'total'):
                if isinstance(data.get(key), int) and data[key] >= 0:
                    row[key] = data[key]
            if isinstance(data.get('revision'), str) and re.fullmatch('[a-f0-9]{40}', data['revision']):
                row['revision'] = data['revision']
            if data.get('status') in ('downloading', 'completed', 'failed'):
                row['status'] = data['status']
            if isinstance(data.get('message'), str):
                row['message'] = data['message'][:300]
            self.save()

    def pause(self, identifier):
        with self.lock:
            if not self.worker or self.current['id'] != identifier:
                raise ValueError('此下载未运行。')
            row, worker = self.current, self.worker
            self.worker = self.current = None
            row['status'] = 'paused'
            self.save()
        worker.close()

    def tick(self):
        with self.lock:
            if not self.worker:
                return
            code = self.worker.poll()
            row = self.current
            root = Path(row['path'])
            if code is None and root.exists():
                size = 0
                for path in root.rglob('*'):
                    try:
                        if path.is_file():
                            size += path.stat().st_size
                    except FileNotFoundError:
                        pass  # Hub may rename a completed partial file during the scan.
                row['downloaded'] = min(row['total'], size)
                if size > self.maximum + 8_000_000 or shutil.disk_usage(root).free < self.reserve:
                    row['message'] = '下载达到存储上限，已停止，可稍后继续。'
                    code = -1
            if code is None and time.monotonic() - self.started < 86400:
                return
            worker, row = self.worker, self.current
            self.worker = self.current = None
        worker.close()
        with self.lock:
            if code != 0 or row['status'] != 'completed':
                row.update(status='failed', message=row.get('message') or '下载中断，可继续下载。')
            self.save()

    def remove(self, path):
        with self.lock:
            target = Path(path).resolve()
            if self.current and Path(self.current['path']).resolve() == target:
                raise ValueError('请先暂停当前下载。')
            self.rows = [row for row in self.rows if Path(row['path']).resolve() != target]
            self.save()

    def close(self):
        if self.worker:
            self.pause(self.current['id'])
