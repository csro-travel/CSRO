"""Post-selection audit slices; private state never repairs observed targets."""
from __future__ import annotations
from collections import defaultdict
import json
from pathlib import Path

import pandas as pd

from .common import ROOT, json_dump, sha256
from .storage import verify_output
from .training import binary_metrics


def validate_audit_source(data_dir: Path, model_dir: Path) -> dict:
    data_manifest=verify_output(data_dir)
    verify_output(model_dir)
    metadata=json.loads((model_dir/'model_metadata.json').read_text(encoding='utf-8'))
    if metadata['configSha256']!=data_manifest.get('configSha256'):
        raise ValueError('Audit data configuration differs from fitted model')
    for name,digest in metadata['inputFileSha256'].items():
        if sha256(data_dir/name)!=digest:
            raise ValueError(f'Audit data differs from fitted model input: {name}')
    expected=metadata.get('inputDatasetManifestSha256') or metadata.get('datasetManifestSha256')
    if expected and expected!=sha256(data_dir/'manifest.json'):
        raise ValueError('Audit source manifest differs from fitted model source')
    return metadata


def add_audit_slices(data_dir: Path, model_dir: Path) -> dict:
    metadata=validate_audit_source(data_dir,model_dir)
    if (model_dir/'audit_slices.json').exists():
        raise ValueError('Audit supplement already exists')
    predictions=pd.read_csv(model_dir/'test_predictions.csv',keep_default_na=False).to_dict('records')
    thresholds=metadata['frozenSelection']['thresholds']
    by_hotel=defaultdict(list)
    for row in predictions:by_hotel[row['hotelId']].append(row)
    enriched=[]
    source_hashes={}
    for hotel_id,predicted in sorted(by_hotel.items()):
        private=data_dir/'private_audit'/f'hotel={hotel_id}'
        audit=data_dir/'audit'/f'hotel={hotel_id}'
        profile=json.loads((private/'hotel_profile.json').read_text(encoding='utf-8'))
        source_hashes[f'{hotel_id}/hotel_profile.json']=sha256(private/'hotel_profile.json')
        source_hashes[f'{hotel_id}/truth.parquet']=sha256(private/'truth.parquet')
        source_hashes[f'{hotel_id}/features.parquet']=sha256(audit/'features.parquet')
        truths={row['booking_id']:row for row in pd.read_parquet(private/'truth.parquet').to_dict('records')}
        features={row['targetCheckInDate']:row for row in pd.read_parquet(audit/'features.parquet').to_dict('records')}
        for row in predicted:
            cohort=json.loads(features[row['targetCheckInDate']]['cohortBookingIds'])
            outcomes=[truths[bid] for bid in cohort]
            causes=sorted({outcome['reason'] for outcome in outcomes if outcome.get('truth_label')==1})
            capacity=profile['room_capacity']
            known_count=sum(outcome.get('observed_label') in (0,1) for outcome in outcomes)
            observability='fully_observed' if known_count==len(cohort) else 'partly_observed' if known_count else 'none_observed'
            enriched.append({**row,'hotelSize':'12-19' if capacity<20 else '20-29' if capacity<30 else '30-40',
                'simulatorProfile':profile['private_profile'],
                'mechanism':' | '.join(causes) if causes else 'NO_SIMULATED_FAILURE',
                'bookingEvidenceCoverage':observability})
    result={'scope':'Post-selection audit only. Private simulator state defines diagnostic groups, never targets or model inputs.',
            'realWorldPerformanceValidated':False,'sourceFileSha256':source_hashes,
            'selectionFrozenBeforeTestSha256':metadata['selectionFrozenBeforeTestSha256'],'groups':{}}
    for dimension in ('hotelSize','simulatorProfile','mechanism','bookingEvidenceCoverage'):
        groups={}
        for value in sorted({row[dimension] for row in enriched}):
            population=[row for row in enriched if row[dimension]==value]
            known=[row for row in population if row['labelStatus']=='KNOWN']
            group={'eligibleRows':len(population),'knownRows':len(known),'unknownRows':len(population)-len(known),
                   'labelCoverage':len(known)/len(population),'models':{}}
            for name in ('lightgbm','exposureRule','pastRateReference'):
                selected=[row for row in population if str(row[name+'SelectedTopK']).lower()=='true']
                selected_known=[row for row in selected if row['labelStatus']=='KNOWN']
                tp=sum(int(row['target'])==1 for row in selected_known)
                unknown=len(selected)-len(selected_known)
                group['models'][name]={
                    'knownLabelMetrics':binary_metrics([int(row['target']) for row in known],
                        [float(row[name+'Score']) for row in known],thresholds[name]['threshold']),
                    'globalQueueSelections':{'selectedRows':len(selected),'selectedKnownRows':len(selected_known),
                        'selectedUnknownRows':unknown,'knownTruePositives':tp,
                        'precisionLowerBound':tp/len(selected) if selected else None,
                        'precisionUpperBound':(tp+unknown)/len(selected) if selected else None}}
            groups[value]=group
        result['groups'][dimension]=groups
    json_dump(model_dir/'audit_slices.json',result)
    metadata['trainingSourceHashes']={name:sha256(ROOT/'travel_ml'/name) for name in ('training.py','reporting.py','__main__.py')}
    metadata['datasetManifestSha256']=sha256(data_dir/'manifest.json')
    json_dump(model_dir/'model_metadata.json',metadata)
    report=json.loads((model_dir/'evaluation_report.json').read_text(encoding='utf-8'))
    report['auditSupplement']='audit_slices.json'
    report['segmentLimitations']='Private audit joins are supplied in audit_slices.json after checkpoint/threshold freeze. They define diagnostic groups only and do not supply missing labels.'
    json_dump(model_dir/'evaluation_report.json',report)
    manifest=json.loads((model_dir/'manifest.json').read_text(encoding='utf-8'))
    manifest['files']={p.relative_to(model_dir).as_posix():sha256(p) for p in sorted(model_dir.rglob('*')) if p.is_file() and p.name!='manifest.json'}
    json_dump(model_dir/'manifest.json',manifest)
    verify_output(model_dir)
    return result
