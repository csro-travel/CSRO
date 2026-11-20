from __future__ import annotations
import csv
import json
from pathlib import Path

import pandas as pd

from .common import sha256


def write_csv(path: Path, rows: list[dict], columns: list[str] | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    keys = columns or (list(rows[0]) if rows else [])
    with path.open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=keys, lineterminator='\n')
        writer.writeheader()
        for row in rows:
            writer.writerow({key: json.dumps(row.get(key), ensure_ascii=False, sort_keys=True, separators=(',', ':'))
                if isinstance(row.get(key), (list, dict)) else row.get(key) for key in keys})


def write_parquet(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    flattened = [{k: json.dumps(v, ensure_ascii=False, sort_keys=True, separators=(',', ':')) if isinstance(v, (dict, list)) else v
                  for k, v in row.items()} for row in rows]
    pd.DataFrame(flattened).to_parquet(path, index=False, compression='zstd')


def verify_output(root: Path) -> dict:
    manifest = json.loads((root/'manifest.json').read_text(encoding='utf-8'))
    expected = manifest['files']
    actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file() and p.relative_to(root).as_posix()!='manifest.json'}
    if actual != set(expected):
        raise ValueError('Output file set changed since generation')
    for name, digest in expected.items():
        path = (root/name).resolve()
        if not path.is_relative_to(root.resolve()) or sha256(path) != digest:
            raise ValueError(f'Output checksum mismatch: {name}')
    return manifest
