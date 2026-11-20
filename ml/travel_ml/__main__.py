from __future__ import annotations
import argparse
from datetime import date, datetime, time, timedelta
import json
from pathlib import Path
import sys

from .common import FEATURE_NAMES, ROOT, SEOUL, json_hash, load_config, parse_time, sha256
from .storage import verify_output


def predict(model_dir: Path, request_path: Path) -> dict:
    import lightgbm as lgb
    from jsonschema import Draft202012Validator
    import numpy as np
    manifest=verify_output(model_dir)
    meta=json.loads((model_dir/'model_metadata.json').read_text(encoding='utf-8'))
    request=json.loads(request_path.read_text(encoding='utf-8-sig'))
    if not isinstance(request,dict):
        raise ValueError('Risk request must be a JSON object')
    required={'hotelId','targetCheckInDate','asOf','featureSetVersion','features'}
    if set(request)!=required:
        raise ValueError('Risk request must contain exactly hotelId, targetCheckInDate, asOf, featureSetVersion and features')
    if any(not isinstance(request[key],str) or not request[key].strip() for key in required-{'features'}):
        raise ValueError('Risk request identifiers, date, timestamp and version must be nonempty strings')
    if not isinstance(request['features'],dict):
        raise ValueError('features must be a JSON object')
    json.dumps(request,allow_nan=False)
    if not isinstance(request.get('hotelId'),str) or not request['hotelId'].strip():
        raise ValueError('hotelId must be a nonempty string')
    target_day=date.fromisoformat(request['targetCheckInDate'])
    expected_asof=datetime.combine(target_day-timedelta(days=1),time(9),SEOUL)
    if parse_time(request['asOf'])!=expected_asof:
        raise ValueError('asOf must match D-1 09:00 Asia/Seoul')
    if request.get('featureSetVersion')!='risk-features-0.2' or meta['featureSetVersion']!=request['featureSetVersion']:
        raise ValueError('Feature version mismatch')
    schema=json.loads((ROOT/'schemas/features-v0.2.schema.json').read_text(encoding='utf-8'))
    errors=list(Draft202012Validator(schema).iter_errors(request['features']))
    if errors:
        raise ValueError('Invalid six-feature request: '+errors[0].message)
    if list(meta['featureNames'])!=list(FEATURE_NAMES) or sha256(model_dir/'model.txt')!=meta['modelSha256']:
        raise ValueError('Model feature order or hash mismatch')
    model=lgb.Booster(model_file=str(model_dir/'model.txt'))
    if model.feature_name()!=list(FEATURE_NAMES):
        raise ValueError('Model internal feature order differs from metadata')
    score=float(model.predict(np.asarray([[float(request['features'][key]) for key in FEATURE_NAMES]],dtype=float),num_threads=1)[0])
    if not 0 <= score <= 1:
        raise ValueError('Model returned an invalid risk score')
    return {'hotelId':request['hotelId'],'targetCheckInDate':request['targetCheckInDate'],'asOf':request['asOf'],
            'riskScore':score,'modelVersion':meta['modelVersion'],'featureSetVersion':meta['featureSetVersion']}


def main() -> int:
    parser=argparse.ArgumentParser(description='여행봄 process-based synthetic ML v0.2, offline only')
    sub=parser.add_subparsers(dest='command',required=True)
    generate=sub.add_parser('generate',help='simulate source events and derive point-in-time datasets')
    generate.add_argument('--config',type=Path,default=ROOT/'config/simulator_v0.2.json')
    generate.add_argument('--output',type=Path,default=Path('generated'))
    generate.add_argument('--hotels',type=int)
    generate.add_argument('--workers',type=int,default=1,help='parallel hotel shards,1..4; default1 for low memory')
    generate.add_argument('--pilot',action='store_true',help='stop before holdout and use representative small subset')
    train=sub.add_parser('train',help='select on October validation, then evaluate frozen holdout once')
    train.add_argument('--input',type=Path,required=True)
    train.add_argument('--output',type=Path,required=True)
    verify=sub.add_parser('verify',help='check generated/model artifact hashes')
    verify.add_argument('--input',type=Path,required=True)
    infer=sub.add_parser('predict',help='CLI inference; no HTTP server or business DB')
    infer.add_argument('--model',type=Path,required=True)
    infer.add_argument('--request',type=Path,required=True)
    args=parser.parse_args()
    try:
        if args.command=='generate':
            from .pipeline import generate as generate_dataset
            config=load_config(args.config)
            hotels=args.hotels if args.hotels is not None else 3 if args.pilot else 100
            result=generate_dataset(config,args.output,hotels,args.pilot,
                progress=lambda status:print(json.dumps(status,ensure_ascii=False),flush=True),workers=args.workers)
            from .provenance import record_runtime
            record_runtime(args.output)
            print(json.dumps({key:result[key] for key in ('isPilot','hotelCount','calendarRows','totalBookings','totalEvents','labelCounts','splitRows')},ensure_ascii=False,indent=2))
        elif args.command=='verify':
            verify_output(args.input)
            print('Artifact checksums: passed')
        elif args.command=='train':
            manifest=verify_output(args.input)
            config=json.loads((args.input/'effective_config.json').read_text(encoding='utf-8'))
            lock=json.loads((args.input/'experiment_lock.json').read_text(encoding='utf-8'))
            if manifest['isPilot'] or lock['isPilot'] or not config['simulation']['config_frozen_for_final_test']:
                raise ValueError('Pilot data cannot be used for the final model experiment; generate a full frozen run')
            if config['generation']['selected_hotel_indices']!=list(range(1,101)):
                raise ValueError('Final model experiment requires all100 hotels')
            if json_hash(config)!=lock['configSha256'] or manifest['configSha256']!=lock['configSha256']:
                raise ValueError('Experiment configuration changed after freeze')
            from .training import train_models
            report=train_models(args.input,args.output,config)
            from .reporting import add_audit_slices
            add_audit_slices(args.input,args.output)
            print(json.dumps({'modelOutput':str(args.output.resolve()),'selection':report['selection'],
                              'test':{name:value['knownLabelMetrics'] for name,value in report['test'].items()},
                              'auditSupplement':'audit_slices.json'},ensure_ascii=False,indent=2,allow_nan=False))
        else:
            print(json.dumps(predict(args.model,args.request),ensure_ascii=False,indent=2))
        return 0
    except (ValueError,OSError,KeyError) as exc:
        print(f'Error: {exc}',file=sys.stderr)
        return 1

if __name__=='__main__':
    raise SystemExit(main())
