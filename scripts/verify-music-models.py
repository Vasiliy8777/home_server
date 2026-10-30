"""Offline verification against model indexes and optional HF download metadata."""
import argparse
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, default=Path('C:/AI/muz_gen/models'))
parser.add_argument('--hash', action='store_true', help='Verify downloaded HF SHA256 hashes (reads all weights)')
parser.add_argument('--components', nargs='+', default=['vae', 'Qwen3-Embedding-0.6B', 'acestep-v15-turbo', 'acestep-5Hz-lm-1.7B', 'acestep-v15-xl-turbo'])
args = parser.parse_args()
errors = []
for name in args.components:
    folder = args.root / name
    if not folder.is_dir():
        errors.append(f'{name}: missing directory')
        continue
    weights = list(folder.glob('*.safetensors'))
    verified_hashes = 0
    if not weights:
        errors.append(f'{name}: missing weights')
    if not (folder / 'config.json').is_file():
        errors.append(f'{name}: missing config.json')
    for index in folder.glob('*.safetensors.index.json'):
        for shard in set(json.loads(index.read_text(encoding='utf-8'))['weight_map'].values()):
            if not (folder / shard).is_file():
                errors.append(f'{name}: missing shard {shard}')
    if 'lm-' in name or 'Embedding' in name:
        if not (folder / 'tokenizer.json').is_file():
            errors.append(f'{name}: missing tokenizer.json')
    for weight in weights:
        # Check safetensors header offsets even without importing torch.
        try:
            with weight.open('rb') as stream:
                size = int.from_bytes(stream.read(8), 'little')
                if size < 2 or size > 100_000_000:
                    raise ValueError('invalid header length')
                header = json.loads(stream.read(size))
            last = max(v['data_offsets'][1] for k, v in header.items() if k != '__metadata__')
            if 8 + size + last != weight.stat().st_size:
                errors.append(f'{name}/{weight.name}: truncated or invalid weights')
        except (ValueError, KeyError) as error:
            errors.append(f'{name}/{weight.name}: {error}')
        metadata = folder / '.cache/huggingface/download' / (weight.name + '.metadata')
        if not metadata.is_file():
            metadata = args.root / '.cache/huggingface/download' / name / (weight.name + '.metadata')
        if args.hash and metadata.is_file():
            lines = metadata.read_text().splitlines()
            if len(lines) > 1 and len(lines[1]) == 64:
                with weight.open('rb') as stream:
                    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                if digest != lines[1]:
                    errors.append(f'{name}/{weight.name}: SHA256 mismatch')
                else:
                    verified_hashes += 1
            else:
                errors.append(f'{name}/{weight.name}: no SHA256 in download metadata')
        elif args.hash:
            errors.append(f'{name}/{weight.name}: missing download metadata for SHA256 check')
    print(f'{name}: {len(weights)} weight files, {sum(p.stat().st_size for p in weights) / 1e9:.2f} GB, {verified_hashes} SHA256 verified', flush=True)
for error in errors:
    print('ERROR:', error)
raise SystemExit(1 if errors else 0)
