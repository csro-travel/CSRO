"""One source ledger -> observed labels/features -> immutable experiment files."""
from __future__ import annotations
from collections import Counter
from copy import deepcopy
from datetime import date, timedelta
import gc
from concurrent.futures import ProcessPoolExecutor, as_completed
import multiprocessing
import json
from pathlib import Path
import shutil
import tempfile
import time

from .common import FEATURE_NAMES, ROOT, json_dump, json_hash, sha256
from .labels import build_labels, split_rows
from .storage import verify_output, write_csv, write_parquet

MODEL_COLUMNS = ['hotelId','targetCheckInDate','asOf','featureSetVersion',*FEATURE_NAMES,
                 'target','labelStatus','labelAvailableAt','cohortSize','knownBookingCount',
                 'failedBookingCount','unknownBookingCount']
PUBLIC_EVENT_TYPES = {'BOOKING_STATUS','RECONFIRMATION_REQUEST','HOTEL_REPLY','INVENTORY_PROBE','OBSERVED_OUTCOME'}


def effective_config(config: dict, hotels: int, pilot: bool) -> dict:
    if not 1 <= hotels <= 100:
        raise ValueError('--hotels must be1..100')
    result = deepcopy(config)
    preferred = [1,76,96]
    selected = (preferred + [i for i in range(1,101) if i not in preferred])[:hotels] if hotels < 100 else list(range(1,101))
    result['generation'] = {'is_pilot':pilot,'selected_hotel_indices':selected}
    result['simulation']['config_frozen_for_final_test'] = not pilot
    if pilot:
        end = date(2025,9,15)
        result['calendar_panel']['end'] = end.isoformat()
        result['simulation']['end'] = '2025-09-27T00:00:00+09:00'
        result['simulation']['internal_arrival_demand_end_date'] = (end+timedelta(days=90)).isoformat()
    return result


def _hotel_task(config: dict, index: int, staging_path: str) -> tuple:
    from .features import build_features
    from .simulator import simulate_hotel
    from .validation import validate_hotel
    staging = Path(staging_path)
    rows = []
    source = simulate_hotel(config, index)
    features = build_features(source, config)
    labels = build_labels(source, features, config)
    checks = validate_hotel(source, features, labels, config)
    hotel_id = source['hotel'].get('hotel_id',source['hotel'].get('hotelId'))
    partition = f'hotel={hotel_id}'
    for key in ('bookings','booking_nights'):
        write_parquet(staging/'source'/partition/f'{key}.parquet',source[key])
    write_parquet(staging/'source'/partition/'events.parquet', [event for event in source['events'] if event['event_type'] in PUBLIC_EVENT_TYPES])
    write_parquet(staging/'private_audit'/partition/'process_events.parquet', [event for event in source['events'] if event['event_type'] not in PUBLIC_EVENT_TYPES])
    write_parquet(staging/'private_audit'/partition/'truth.parquet',source['truth'])
    json_dump(staging/'private_audit'/partition/'hotel_profile.json',source['hotel'])
    write_parquet(staging/'audit'/partition/'features.parquet',features)
    write_parquet(staging/'audit'/partition/'labels.parquet',labels)
    if len(features)!=len(labels):
        raise ValueError('Feature/label row count mismatch')
    for feature,label in zip(features,labels):
        if tuple(feature[k] for k in ('hotelId','targetCheckInDate','asOf')) != tuple(label[k] for k in ('hotelId','targetCheckInDate','asOf')):
            raise ValueError('Feature/label join keys mismatch')
        combined = {**feature, **label}
        rows.append({key:combined.get(key) for key in MODEL_COLUMNS})
    summary = {'hotelId':hotel_id,'stats':source['stats'],'validation':checks,
                      'eventCount':len(source['events']),'bookingCount':len(source['bookings'])}
    return rows, summary


def generate(config: dict, output: Path, hotels: int=100, pilot: bool=False, progress=None, workers: int=1) -> dict:
    from .features import build_features
    from .simulator import simulate_hotel
    from .validation import validate_dataset, validate_hotel
    if output.exists():
        raise ValueError('Output exists; select a new --output directory. No overwrite/delete is performed.')
    config = effective_config(config, hotels, pilot)
    generator_files = ('common.py','features.py','labels.py','pipeline.py','simulator.py','storage.py','validation.py')
    source_hashes = {name:sha256(ROOT/'travel_ml'/name) for name in generator_files}
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix='.travel-ml-', dir=output.parent))
    started = time.monotonic()
    rows, summaries = [], []
    try:
        json_dump(staging/'effective_config.json', config)
        json_dump(staging/'experiment_lock.json', {'rulesVersion':config['rules_version'],
                  'featureSetVersion':config['feature_set_version'],'configSha256':json_hash(config),
                  'generationMode':'PILOT_NO_TEST_EVALUATION' if pilot else 'FROZEN_BEFORE_GENERATION',
                  'isPilot':pilot,'seed':config['seed'],'generatorSourceHashes':source_hashes})
        indices = config['generation']['selected_hotel_indices']
        if not 1 <= workers <= 4:
            raise ValueError('--workers must be1..4')
        if workers == 1:
            results = (_hotel_task(config,index,str(staging)) for index in indices)
            for hotel_rows,summary in results:
                rows.extend(hotel_rows);summaries.append(summary)
                if progress:
                    progress({'hotel':summary['hotelId'],'complete':len(summaries),'total':hotels,
                              'bookings':summary['bookingCount'],'events':summary['eventCount'],
                              'elapsedSeconds':round(time.monotonic()-started,2)})
        else:
            with ProcessPoolExecutor(max_workers=workers,mp_context=multiprocessing.get_context('spawn')) as pool:
                futures=[pool.submit(_hotel_task,config,index,str(staging)) for index in indices]
                for future in as_completed(futures):
                    hotel_rows,summary=future.result()
                    rows.extend(hotel_rows);summaries.append(summary)
                    if progress:
                        progress({'hotel':summary['hotelId'],'complete':len(summaries),'total':hotels,
                                  'bookings':summary['bookingCount'],'events':summary['eventCount'],
                                  'elapsedSeconds':round(time.monotonic()-started,2)})
        summaries.sort(key=lambda summary:summary['hotelId'])
        if source_hashes != {name:sha256(ROOT/'travel_ml'/name) for name in generator_files}:
            raise ValueError('Generator source changed while running; regenerate with one consistent code version')
        rows.sort(key=lambda row:(row['targetCheckInDate'],row['hotelId']))
        dataset_checks = validate_dataset(rows,config)
        parts,audit = split_rows(rows,config)
        write_csv(staging/'dataset.csv',rows,MODEL_COLUMNS)
        for name,split in parts.items():
            write_csv(staging/f'{name}.csv',split,MODEL_COLUMNS)
        write_csv(staging/'split_audit.csv',audit,['hotelId','targetCheckInDate','asOf','assignedSplit','status','labelCutoff'])
        report={'isSynthetic':True,'isPilot':pilot,'scope':'Process/observed-source consistency; not real-world accuracy',
                'datasetValidation':dataset_checks,'hotels':summaries,
                'labelCounts':dict(Counter(str(row['target']) if row['target'] is not None else row['labelStatus'] for row in rows)),
                'splitRows':{name:len(value) for name,value in parts.items()},
                'splitAuditCounts':dict(Counter(row['status'] for row in audit))}
        json_dump(staging/'quality_report.json',report)
        json_dump(staging/'feature_columns.json',{'featureSetVersion':config['feature_set_version'],'featureNames':FEATURE_NAMES})
        files={p.relative_to(staging).as_posix():sha256(p) for p in sorted(staging.rglob('*')) if p.is_file()}
        manifest={'isSynthetic':True,'isPilot':pilot,'seed':config['seed'],'rulesVersion':config['rules_version'],
                  'featureSetVersion':config['feature_set_version'],'configSha256':json_hash(config),
                  'calendarRows':len(rows),'hotelCount':hotels,'totalBookings':sum(h['bookingCount'] for h in summaries),
                  'totalEvents':sum(h['eventCount'] for h in summaries),'labelCounts':report['labelCounts'],
                  'splitRows':report['splitRows'],'generatorSourceHashes':source_hashes,'files':files}
        json_dump(staging/'manifest.json',manifest)
        verify_output(staging)
        staging.rename(output)
        return manifest
    except BaseException:
        shutil.rmtree(staging)
        raise
