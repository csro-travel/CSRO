"""Supervised outcomes from collected source evidence, never private truth."""
from __future__ import annotations
from collections import Counter
from datetime import datetime

from .common import parse_time


def build_labels(source: dict, feature_rows: list[dict], config: dict) -> list[dict]:
    cutoff = parse_time(config['simulation']['end'])
    observed = {}
    for event in source['events']:
        if event['event_type'] != 'OBSERVED_OUTCOME':
            continue
        available, occurred = parse_time(event['available_at']), parse_time(event['occurred_at'])
        if occurred > available:
            raise ValueError('An observation cannot be available before it occurs')
        if available > cutoff:
            continue
        value = event['payload']['label']
        if isinstance(value, bool) or value not in (0, 1):
            raise ValueError('Only known 0/1 evidence may produce OBSERVED_OUTCOME')
        booking = event['booking_id']
        if booking in observed and observed[booking]['label'] != value:
            raise ValueError('Semantic outcome correction is outside v0.2')
        if booking not in observed or available < observed[booking]['at']:
            observed[booking] = {'label': value, 'at': available, 'event_id': event['event_id']}
    result = []
    for feature in feature_rows:
        cohort = feature['cohortBookingIds']
        if len(set(cohort)) != len(cohort):
            raise ValueError('Cohort booking IDs must be unique')
        known = [observed[bid] for bid in cohort if bid in observed]
        failed = [outcome for outcome in known if outcome['label'] == 1]
        target, available, status = None, None, 'UNKNOWN'
        if not cohort:
            status = 'NOT_ELIGIBLE'
        elif failed:
            target, available, status = 1, min(row['at'] for row in failed), 'KNOWN'
        elif len(known) == len(cohort):
            target, available, status = 0, max(row['at'] for row in known), 'KNOWN'
        result.append({
            'hotelId': feature['hotelId'], 'targetCheckInDate': feature['targetCheckInDate'],
            'asOf': feature['asOf'], 'target': target, 'labelStatus': status,
            'labelAvailableAt': available.isoformat(timespec='seconds') if available else None,
            'cohortSize': len(cohort), 'knownBookingCount': len(known),
            'failedBookingCount': len(failed), 'unknownBookingCount': len(cohort)-len(known),
        })
    return result


def split_rows(rows: list[dict], config: dict) -> tuple[dict, list[dict]]:
    parts = {'train': [], 'validation': [], 'test': []}
    audit = []
    ev = config['evaluation']
    cutoffs = {'train': parse_time(ev['train_label_available_before']),
               'validation': parse_time(ev['validation_label_available_before']),
               'test': parse_time(ev['test_label_available_by'])}
    for row in rows:
        day = row['targetCheckInDate']
        group = next((name for name in parts if ev[f'{name}_dates'][0] <= day <= ev[f'{name}_dates'][1]), 'warmup')
        reason = 'INCLUDED'
        if group == 'warmup':
            reason = 'WARMUP_NOT_TRAINED'
        elif row['labelStatus'] != 'KNOWN':
            reason = row['labelStatus']
        elif row['target'] not in (0, 1) or not row['labelAvailableAt']:
            raise ValueError('Known labels need a target and observation time')
        else:
            available = parse_time(row['labelAvailableAt'])
            if available > cutoffs[group] or (group != 'test' and available == cutoffs[group]):
                reason = 'PURGED_LABEL_NOT_AVAILABLE'
        audit.append({'hotelId': row['hotelId'], 'targetCheckInDate': day, 'asOf': row['asOf'],
                      'assignedSplit': group, 'status': reason,
                      'labelCutoff': cutoffs[group].isoformat() if group != 'warmup' else None})
        if reason == 'INCLUDED':
            parts[group].append(row)
    return parts, audit
