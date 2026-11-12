"""Bounded Hub download worker. Model code is never executed here."""
import json
import os
import shutil
import sys
from pathlib import Path

from atomic import replace_with_retry


def emit(**values):
    print(json.dumps(values), flush=True)


def download(request):
    from huggingface_hub import HfApi, hf_hub_download

    root = Path(request['path'])
    root.mkdir(parents=True, exist_ok=True)
    plan_path = root / '.download-plan.json'
    if plan_path.exists():
        plan = json.loads(plan_path.read_text(encoding='utf-8'))
    else:
        model = HfApi().model_info(request['repo'], revision=request['revision'], files_metadata=True)
        candidates = [f for f in model.siblings if f.rfilename in ('README.md', 'LICENSE', 'LICENSE.md', 'NOTICE') or f.rfilename.endswith(('.safetensors', '.json', '.model', '.txt', '.tiktoken', '.jinja'))]
        if not any(f.rfilename.endswith('.safetensors') for f in candidates):
            candidates.extend(f for f in model.siblings if f.rfilename.startswith('pytorch_model') and f.rfilename.endswith('.bin'))
        files = []
        for file in candidates:
            path = Path(file.rfilename)
            if path.is_absolute() or path.drive or '..' in path.parts or '\\' in file.rfilename:
                raise ValueError('模型仓库包含无效文件路径。')
            # Alternate deployment formats/submodels are not training inputs.
            if len(path.parts) != 1 or path.name.startswith('.'):
                continue
            if file.size is None or file.size < 0:
                raise ValueError('无法确定下载大小。')
            files.append({'name': file.rfilename, 'size': file.size})
        if not any(f['name'] == 'config.json' for f in files) or not any(f['name'].endswith(('.safetensors', '.bin')) for f in files):
            raise ValueError('请选择包含训练权重及 config.json 的模型仓库。')
        total = sum(f['size'] for f in files)
        if total > request['maxBytes'] or len(files) > 500:
            raise ValueError('模型超过本次下载上限。')
        if shutil.disk_usage(root).free < total + request['reserveBytes']:
            raise ValueError('磁盘空间不足，未开始下载权重。')
        plan = {'repo': request['repo'], 'revision': model.sha, 'files': files, 'total': total}
        temp = plan_path.with_suffix('.tmp')
        temp.write_text(json.dumps(plan), encoding='utf-8')
        replace_with_retry(temp, plan_path)
    total = plan['total']
    if total > request['maxBytes']:
        raise ValueError('模型超过本次下载上限。')
    emit(status='downloading', revision=plan['revision'], total=total, downloaded=0)
    completed = 0
    for file in plan['files']:
        present = sum(p.stat().st_size for p in root.rglob("*") if p.is_file())
        if shutil.disk_usage(root).free < max(0, total - present) + request['reserveBytes']:
            raise ValueError('磁盘空间不足，可释放空间后继续下载。')
        path = Path(hf_hub_download(plan['repo'], file['name'], revision=plan['revision'],
                                   local_dir=root))
        if path.stat().st_size != file['size']:
            raise ValueError('下载文件大小不一致。')
        completed += file['size']
        emit(downloaded=completed, total=total)
    emit(status='completed', downloaded=total, total=total, revision=plan['revision'])


if __name__ == '__main__':
    try:
        download(json.loads(Path(sys.argv[1]).read_text(encoding='utf-8')))
    except Exception as error:
        # SDK exceptions may contain request headers. Never log credentials.
        message = str(error) if isinstance(error, ValueError) else '下载失败，请检查仓库、版本、网络或访问令牌后重试。'
        emit(status='failed', message=message[:300])
        sys.exit(1)
