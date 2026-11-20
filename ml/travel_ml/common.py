from __future__ import annotations
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path

SEOUL = timezone(timedelta(hours=9))
ROOT = Path(__file__).resolve().parents[1]
FEATURE_NAMES = (
    'pastFailureRate90d', 'targetConfirmedBookingCount', 'bookingVolumeGrowth7d',
    'reconfirmationOverdueRate7d', 'inventoryUnavailableRate7d', 'isPeakDay',
)


def parse_time(value: str) -> datetime:
    result = datetime.fromisoformat(value)
    if result.tzinfo is None or result.utcoffset() is None:
        raise ValueError('Every event timestamp must include a UTC offset')
    return result


def iso(value: datetime) -> str:
    if value.tzinfo is None:
        raise ValueError('Missing timezone')
    return value.astimezone(SEOUL).isoformat(timespec='seconds')


def dates(start: str, end: str):
    current, last = date.fromisoformat(start), date.fromisoformat(end)
    while current <= last:
        yield current
        current += timedelta(days=1)


def sha256(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(chunk)
    return hasher.hexdigest()


def json_dump(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + '\n', encoding='utf-8')


def json_hash(value) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def load_config(path: Path | None = None) -> dict:
    config = json.loads((path or ROOT/'config/simulator_v0.2.json').read_text(encoding='utf-8-sig'))
    if config['seed'] != 42 or config['simulation']['hotel_count'] != 100:
        raise ValueError('v0.2 experiment requires seed42 and a100-hotel universe')
    if tuple(config['features']['ordered_names']) != FEATURE_NAMES:
        raise ValueError('Feature contract differs from risk-features-0.2')
    return config
