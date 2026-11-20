"""Separate, exploratory XGBoost comparison against an immutable v0.2 baseline.

This module never regenerates labels, retrains LightGBM, modifies the reference
artifact, or changes the six-feature contract. The old holdout has already been
seen for LightGBM; this follow-up is not an independent new final test.
"""
from __future__ import annotations

import argparse
import csv
from copy import deepcopy
from datetime import date
from fractions import Fraction
from importlib.metadata import version as package_version
import json
import math
from pathlib import Path
import platform
import subprocess
import tempfile
import time

import lightgbm as lgb
import numpy as np
from sklearn.metrics import average_precision_score

from .common import ROOT, json_dump, json_hash, sha256
from .storage import verify_output
from .training import (
    FEATURES, MATURITY, SCHEDULE, _aware, _coverage, _segment_metrics, _xy,
    binary_metrics, read_rows, rule_scores, select_threshold, top_k_metrics,
    validate_splits,
)

PROTOCOL_PATH = ROOT/'config/xgboost_comparison_v0.2.json'
CSV_NAMES = ('dataset.csv','train.csv','validation.csv','test.csv','split_audit.csv')
CORE_NAMES = ('__main__.py','reporting.py','training.py')
EXPOSURE_RULE_NUMERICS = (
    'Exact rational evaluation of binary64 float p and integer n for '
    '1-(1-p)**n, followed by one float rounding; frozen threshold unchanged. '
    'No libm log1p/expm1, ULP tolerance, or threshold retuning.'
)
FOLLOWUP_NOTICE = (
    'EXPLORATORY_FOLLOWUP_NOT_NEW_HOLDOUT: LightGBM test outcomes were already '
    'seen before this comparison was proposed. No real-world performance, '
    'independent final validation, calibrated probability, or causal benefit is established.'
)


def _read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def _unit_scope(protocol):
    return (protocol.get('scope')=='UNIT_TEST_FIXTURE_ONLY'
            and str(protocol.get('experiment_version','')).startswith('unit-test-'))


def validate_protocol(protocol):
    """Reject a quietly expanded search or altered production comparison rule."""
    if protocol.get('seed')!=42 or protocol.get('feature_set_version')!='risk-features-0.2':
        raise ValueError('Comparison requires seed 42 and risk-features-0.2')
    if protocol.get('data_rules_version')!='0.2.0':
        raise ValueError('Unsupported data-rules version')
    settings=protocol['xgboost']
    if settings.get('version')!='3.4.1' or settings.get('package')!='xgboost-cpu':
        raise ValueError('Protocol requires xgboost-cpu==3.4.1')
    required={
        'objective':'binary:logistic','tree_method':'hist','device':'cpu',
        'eta':0.03,'seed':42,'nthread':1,'subsample':1.0,'colsample_bytree':1.0,
        'colsample_bylevel':1.0,'colsample_bynode':1.0,'lambda':1.0,
        'alpha':0.0,'gamma':0.0,'scale_pos_weight':1.0,
        'disable_default_eval_metric':1,'verbosity':0,
    }
    if settings.get('params')!=required:
        raise ValueError('XGBoost fixed parameters differ from the declared comparison')
    if settings.get('early_stopping_metric')!='sklearn.average_precision_score':
        raise ValueError('Use sklearn AP custom metric, not XGBoost aucpr')
    if not _unit_scope(protocol):
        if (protocol.get('experiment_version')!='xgboost-comparison-0.2.0'
            or settings.get('max_depth_candidates')!=[3,4,5]
            or settings.get('min_child_weight_candidates')!=[1,5]
            or settings.get('num_boost_round')!=2000
            or settings.get('early_stopping_rounds')!=50):
            raise ValueError('Production grid/rounds changed without a separate protocol')
    else:
        if (not settings.get('max_depth_candidates') or not settings.get('min_child_weight_candidates')
            or settings.get('num_boost_round',0)<1 or settings.get('early_stopping_rounds',0)<1):
            raise ValueError('Unit fixture needs a nonempty positive training configuration')
    ev=protocol['evaluation']
    for name,bounds in SCHEDULE.items():
        if tuple(ev[f'{name}_dates'])!=bounds:
            raise ValueError('Comparison dates differ from fixed v0.2 calendar')
    if (ev.get('daily_review_budget_k')!=10
        or ev.get('test_outcomes_already_seen_for_lightgbm') is not True
        or ev.get('interpretation')!='EXPLORATORY_FOLLOWUP_NOT_NEW_HOLDOUT'):
        raise ValueError('Follow-up interpretation and daily budget must be explicit')
    selection=protocol['selection']
    if (selection.get('metric')!='validation_average_precision'
        or selection.get('tie_break')!=['lower_max_depth','higher_min_child_weight']
        or selection.get('threshold')!='validation F1; tie precision, threshold'):
        raise ValueError('Comparison must select only by declared validation criteria')
    importance=protocol['feature_importance']
    if importance.get('permutation_repeats')!=5 or importance.get('seed')!=42:
        raise ValueError('Permutation importance requires five validation-only repeats, seed 42')
    return protocol


def _assert_runtime(protocol):
    import xgboost as xgb
    expected=protocol['xgboost']['version']
    if (xgb.__version__!=expected
        or package_version(protocol['xgboost']['package'])!=expected):
        raise ValueError('Install the exact xgboost-cpu version in requirements-xgboost.txt')
    return xgb


def _check_source_hashes(metadata,protocol):
    expected=metadata.get('trainingSourceHashes')
    if not isinstance(expected,dict) or set(expected)!=set(CORE_NAMES):
        raise ValueError('Reference metadata must identify the three unchanged training core modules')
    actual={name:sha256(ROOT/'travel_ml'/name) for name in CORE_NAMES}
    if actual!=expected:
        raise ValueError('Current LightGBM/core source differs from reference trainingSourceHashes')
    commit=protocol.get('baseline_git_commit','')
    if not commit or any(char not in '0123456789abcdef' for char in commit) or len(commit)!=40:
        raise ValueError('baseline_git_commit must be a full Git SHA')
    # A supplied fixture may use the same checked-out core with its own tiny data.
    # Production additionally binds those modules to the declared original commit.
    if not _unit_scope(protocol):
        for name,digest in actual.items():
            result=subprocess.run(['git','-C',str(ROOT.parent),'show',f'{commit}:ml/travel_ml/{name}'],
                                  capture_output=True,check=False)
            if result.returncode:
                raise ValueError('Declared baseline commit is unavailable in this checkout')
            import hashlib
            if hashlib.sha256(result.stdout).hexdigest()!=digest:
                raise ValueError('Current reference core differs from baseline Git commit')
    return actual


def _check_train_validation(data_dir,config,metadata):
    """Read only train/validation labels before the holdout selection barrier."""
    if (config.get('feature_set_version')!='risk-features-0.2'
        or tuple(config['features']['ordered_names'])!=FEATURES):
        raise ValueError('Effective config does not contain the six ordered v0.2 features')
    for name,bounds in SCHEDULE.items():
        if tuple(config['evaluation'][f'{name}_dates'])!=bounds:
            raise ValueError('Effective config calendar differs from v0.2')
    if (_aware(config['evaluation']['train_label_available_before'])!=MATURITY['train']
        or _aware(config['evaluation']['validation_label_available_before'])!=MATURITY['validation']):
        raise ValueError('Effective config label-maturity boundaries differ from v0.2')
    parts={}
    for name in ('train','validation'):
        rows=read_rows(data_dir/f'{name}.csv')
        first,last=map(date.fromisoformat,SCHEDULE[name])
        if not rows or any(row['labelStatus']!='KNOWN' or row['target'] not in (0,1)
            or not first<=row['date']<=last or row['labelAvailableAt']>=MATURITY[name]
            for row in rows):
            raise ValueError(f'{name} requires mature KNOWN 0/1 rows in its fixed calendar')
        if {row['target'] for row in rows}!={0,1}:
            raise ValueError(f'{name} needs both observed classes for AP model comparison')
        meta_key='trainingRows' if name=='train' else 'validationRows'
        if len(rows)!=metadata.get(meta_key):
            raise ValueError(f'{name} row count differs from reference metadata')
        parts[name]=rows
    return parts


def _lgb_scores(model,rows):
    if not rows:return np.empty(0,dtype=float)
    matrix,_=_xy(rows)
    scores=np.asarray(model.predict(matrix,num_iteration=model.current_iteration(),num_threads=1),dtype=float)
    if not np.all(np.isfinite(scores)) or np.any((scores<0)|(scores>1)):
        raise ValueError('Reference LightGBM returned invalid binary scores')
    return scores


def exact_exposure_scores(rows):
    """Evaluate the unchanged exposure rule independently of platform libm.

    Parsed p is an exact binary64 rational; n is the integer frozen-cohort size.
    Integer/Fraction arithmetic evaluates 1-(1-p)**n exactly, then conversion
    rounds only once to float. This avoids log1p/expm1 cross-platform ULP
    differences crossing an already frozen threshold. Empty cohorts score zero.
    """
    scores=[]
    for row in rows:
        p,n=row['values'][:2]
        if (isinstance(p,(bool,np.bool_)) or not isinstance(p,(int,float,np.floating))
            or not math.isfinite(float(p)) or not 0<=p<=1):
            raise ValueError('Exposure rule p must be finite and in [0,1]')
        if isinstance(n,(bool,np.bool_)) or not isinstance(n,(int,np.integer)) or n<0:
            raise ValueError('Exposure rule n must be a nonnegative integer cohort size')
        scores.append(float(1-(1-Fraction.from_float(float(p)))**int(n)))
    return np.asarray(scores,dtype=float)


def _reference_validation(model,rows,metadata):
    _,targets=_xy(rows)
    scores={'lightgbm':_lgb_scores(model,rows),'exposureRule':exact_exposure_scores(rows),
            'pastRateReference':rule_scores(rows,reference=True)}
    thresholds=metadata['frozenSelection']['thresholds']
    if set(thresholds)!=set(scores):
        raise ValueError('Reference frozen thresholds must cover LightGBM and both rules')
    metrics={}
    for name,values in scores.items():
        frozen=thresholds[name]
        threshold=frozen.get('threshold')
        if not isinstance(threshold,(int,float)) or not math.isfinite(threshold):
            raise ValueError('Reference threshold must be finite')
        metrics[name]=binary_metrics(targets,values,threshold)
        for source_key,metric_key in [('validationF1','f1'),('validationPrecision','precision')]:
            if source_key in frozen and not math.isclose(frozen[source_key],metrics[name][metric_key],rel_tol=0,abs_tol=1e-12):
                raise ValueError(f'Reference {name} validation threshold metrics did not reproduce')
    expected=metadata.get('selectedValidationAveragePrecision')
    if not isinstance(expected,(int,float)) or not math.isclose(
        expected,metrics['lightgbm']['averagePrecision'],rel_tol=0,abs_tol=1e-12):
        raise ValueError('Reference validation AP did not reproduce from frozen checkpoint')
    return scores,metrics


def validate_comparison_inputs(data_dir:Path,reference_model_dir:Path,protocol:dict)->dict:
    """Preflight byte/provenance checks, then parse train/validation only.

    Full-generation manifests are preserved as receipts; missing source event
    partitions are not silently treated as present in a CSV review copy.
    """
    validate_protocol(protocol)
    data_dir,reference_model_dir=Path(data_dir),Path(reference_model_dir)
    expected=protocol['expected_reference']
    expected_manifest=expected.get('reference_model_manifest_sha256')
    if expected_manifest is not None and sha256(reference_model_dir/'manifest.json')!=expected_manifest:
        raise ValueError('Reference model manifest differs from the separately pinned baseline')
    reference_manifest=verify_output(reference_model_dir)
    if reference_manifest.get('isSynthetic') is not True or reference_manifest.get('type')!='model':
        raise ValueError('Reference artifact is not a verified synthetic model directory')
    metadata=_read_json(reference_model_dir/'model_metadata.json')
    if (metadata.get('artifactFormat')!='lightgbm-text-v1'
        or metadata.get('featureNames')!=list(FEATURES)
        or metadata.get('featureSetVersion')!='risk-features-0.2'
        or metadata.get('realWorldPerformanceValidated') is not False):
        raise ValueError('Reference metadata/model contract mismatch')
    actual_model_sha=sha256(reference_model_dir/'model.txt')
    if (actual_model_sha!=metadata.get('modelSha256')
        or actual_model_sha!=expected.get('model_sha256')
        or metadata.get('modelVersion')!=expected.get('model_version')):
        raise ValueError('Reference model checksum/version differs from the declared baseline')
    core_hashes=_check_source_hashes(metadata,protocol)
    config=_read_json(data_dir/'effective_config.json')
    config_sha=json_hash(config)
    if config_sha!=metadata.get('configSha256') or config_sha!=expected.get('data_config_sha256'):
        raise ValueError('Effective data config checksum differs from the immutable baseline')
    if (not _unit_scope(protocol) and (config.get('generation',{}).get('is_pilot') is not False
        or config.get('simulation',{}).get('config_frozen_for_final_test') is not True)):
        raise ValueError('Comparison requires the original full frozen experiment, not a pilot')
    full_manifest_path=data_dir/'full_run_manifest.json'
    full_manifest_sha=sha256(full_manifest_path)
    if full_manifest_sha!=expected.get('full_dataset_manifest_sha256'):
        raise ValueError('Preserved full-run manifest checksum differs from the declared baseline')
    for name in ('inputDatasetManifestSha256','datasetManifestSha256'):
        if name in metadata and metadata[name]!=full_manifest_sha:
            raise ValueError('Reference model is bound to a different full-run dataset manifest')
    if metadata.get('inputDatasetManifestSha256')!=full_manifest_sha:
        raise ValueError('Reference model lacks the required input dataset manifest binding')
    full_manifest=_read_json(full_manifest_path)
    if full_manifest.get('configSha256')!=config_sha or full_manifest.get('isSynthetic') is not True:
        raise ValueError('Full-run manifest data provenance/config binding mismatch')
    if set(metadata.get('inputFileSha256',{}))!=set(CSV_NAMES):
        raise ValueError('Reference must bind exactly the five review CSV hashes')
    input_hashes={name:sha256(data_dir/name) for name in CSV_NAMES}
    for name,digest in input_hashes.items():
        if digest!=metadata['inputFileSha256'][name] or digest!=full_manifest.get('files',{}).get(name):
            raise ValueError(f'CSV checksum differs from reference/full-run manifest: {name}')
    if sha256(data_dir/'effective_config.json')!=full_manifest.get('files',{}).get('effective_config.json'):
        raise ValueError('Effective config file bytes differ from full-run manifest')
    if json_hash(metadata.get('frozenSelection'))!=metadata.get('selectionFrozenBeforeTestSha256'):
        raise ValueError('Reference validation selection receipt was modified')
    model=lgb.Booster(model_file=str(reference_model_dir/'model.txt'))
    if (model.feature_name()!=list(FEATURES) or model.num_feature()!=len(FEATURES)
        or model.params.get('objective')!='binary'
        or model.current_iteration()!=metadata.get('bestIteration')):
        raise ValueError('Reference model internal features/objective/checkpoint mismatch')
    parts=_check_train_validation(data_dir,config,metadata)
    validation_scores,validation_metrics=_reference_validation(model,parts['validation'],metadata)
    return {
        'config':config,'configSha256':config_sha,'inputFileSha256':input_hashes,
        'comparisonSourceSha256':sha256(Path(__file__)),
        'protocolSha256':json_hash(protocol),'protocolObject':protocol,'protocolSnapshot':deepcopy(protocol),
        'fullRunManifestSha256':full_manifest_sha,'referenceMetadata':metadata,
        'referenceManifestSha256':sha256(reference_model_dir/'manifest.json'),
        'referenceModel':model,'referenceValidationScores':validation_scores,
        'referenceValidationMetrics':validation_metrics,'trainingSourceHashes':core_hashes,
        'parts':parts,'inputScope':'CSV_REVIEW_COPY_ONLY_NOT_FULL_GENERATION',
        'sourceReceiptMeaning':'Original full-run manifest hash verified; unavailable raw source partitions are not verified by this CSV-only comparison.',
    }


def _dmatrix(xgb,rows,include_labels=True):
    matrix,targets=_xy(rows)
    return xgb.DMatrix(matrix,label=targets if include_labels else None,feature_names=list(FEATURES),nthread=1)


def _xgb_predict(model,rows):
    import xgboost as xgb
    if not rows:return np.empty(0,dtype=float)
    scores=np.asarray(model.predict(_dmatrix(xgb,rows,include_labels=False)),dtype=float)
    if not np.all(np.isfinite(scores)) or np.any((scores<0)|(scores>1)):
        raise ValueError('XGBoost returned invalid binary scores')
    return scores


def freeze_validation_selection(training_rows,validation_rows,protocol):
    """Return (saved-best booster, frozen-selection fields, six search rows).

    This helper receives no dataset or test path. It cannot inspect test labels.
    A clearly named unit-test fixture may use a shorter explicit grid; the
    production CLI enforces the declared six-candidate 2000/50 protocol.
    """
    validate_protocol(protocol)
    xgb=_assert_runtime(protocol)
    _,targets=_xy(validation_rows)
    if {r['target'] for r in training_rows}!={0,1} or set(targets.tolist())!={0,1}:
        raise ValueError('Training and validation need both observed classes for AP comparison')
    settings=protocol['xgboost']
    training=_dmatrix(xgb,training_rows)
    validation=_dmatrix(xgb,validation_rows)
    def custom_ap(prediction,matrix):
        return 'average_precision',float(average_precision_score(matrix.get_label(),prediction))
    results=[]
    best=None
    for depth in settings['max_depth_candidates']:
        for child in settings['min_child_weight_candidates']:
            start=time.monotonic()
            params={**settings['params'],'max_depth':int(depth),'min_child_weight':float(child)}
            callback=xgb.callback.EarlyStopping(
                rounds=int(settings['early_stopping_rounds']),metric_name='average_precision',
                data_name='validation',maximize=True,save_best=True)
            model=xgb.train(params,training,num_boost_round=int(settings['num_boost_round']),
                            evals=[(validation,'validation')],custom_metric=custom_ap,
                            callbacks=[callback],verbose_eval=False)
            scores=np.asarray(model.predict(validation),dtype=float)
            ap=float(average_precision_score(targets,scores))
            row={'maxDepth':int(depth),'minChildWeight':float(child),
                 'bestIterationZeroBased':int(model.best_iteration),
                 'boostedRounds':int(model.num_boosted_rounds()),
                 'validationAveragePrecision':ap,'fitSeconds':time.monotonic()-start,
                 'earlyStoppingMetric':'sklearn.average_precision_score',
                 'callbackBestScore':float(model.best_score)}
            results.append(row)
            key=(-ap,int(depth),-float(child))
            if best is None or key<best[0]:best=(key,model,row,scores)
    if best is None:raise ValueError('Comparison grid is empty')
    _,model,selected,scores=best
    threshold=select_threshold(targets,scores)
    selection={
        'selectedParameters':{'max_depth':selected['maxDepth'],'min_child_weight':selected['minChildWeight']},
        'selectedModelParams':{**settings['params'],'max_depth':selected['maxDepth'],
                               'min_child_weight':selected['minChildWeight']},
        'bestIterationZeroBased':selected['bestIterationZeroBased'],
        'boostedRounds':selected['boostedRounds'],
        'validationAveragePrecision':selected['validationAveragePrecision'],
        'threshold':threshold,'featureNames':list(FEATURES),
        'selectionPolicy':'October AP; ties lower max_depth then higher min_child_weight',
        'finalModelPolicy':'April-September checkpoint; no training+validation refit',
        'earlyStoppingMetric':'sklearn.average_precision_score custom_metric; maximize=True; save_best=True',
    }
    return model,selection,results


def _permutation_importance(model,rows,protocol):
    import xgboost as xgb
    matrix,targets=_xy(rows)
    baseline=float(average_precision_score(targets,_xgb_predict(model,rows)))
    rng=np.random.default_rng(protocol['feature_importance']['seed'])
    result=[]
    for i,name in enumerate(FEATURES):
        drops=[]
        for _ in range(protocol['feature_importance']['permutation_repeats']):
            changed=matrix.copy();changed[:,i]=rng.permutation(changed[:,i])
            scores=model.predict(xgb.DMatrix(changed,feature_names=list(FEATURES),nthread=1))
            drops.append(baseline-float(average_precision_score(targets,scores)))
        result.append({'feature':name,'meanAveragePrecisionDrop':float(np.mean(drops)),
                       'stdAveragePrecisionDrop':float(np.std(drops)),
                       'averagePrecisionDrops':drops,'repeats':len(drops)})
    return result


def _write_csv(path,rows,fields=None):
    fields=fields or (list(rows[0]) if rows else [])
    with Path(path).open('w',newline='',encoding='utf-8') as file:
        writer=csv.DictWriter(file,fieldnames=fields);writer.writeheader();writer.writerows(rows)


def _write_and_sync_json(path,value):
    import os
    json_dump(path,value)
    with Path(path).open('r+b') as file:os.fsync(file.fileno())


def _unchanged_inputs(data_dir,reference_model_dir,context):
    if sha256(Path(__file__))!=context['comparisonSourceSha256']:
        raise ValueError('Comparison module source changed during comparison')
    if {name:sha256(ROOT/'travel_ml'/name) for name in CORE_NAMES}!=context['trainingSourceHashes']:
        raise ValueError('Reference core source changed during comparison')
    if json_hash(context['protocolObject'])!=context['protocolSha256']:
        raise ValueError('Comparison protocol changed during comparison')
    for name,digest in context['inputFileSha256'].items():
        if sha256(data_dir/name)!=digest:raise ValueError(f'Input changed during comparison: {name}')
    if (sha256(data_dir/'full_run_manifest.json')!=context['fullRunManifestSha256']
        or json_hash(_read_json(data_dir/'effective_config.json'))!=context['configSha256']):
        raise ValueError('Input provenance/config changed during comparison')
    if sha256(reference_model_dir/'manifest.json')!=context['referenceManifestSha256']:
        raise ValueError('Reference manifest changed during comparison')
    verify_output(reference_model_dir)


def compare_models(data_dir:Path,reference_model_dir:Path,out_dir:Path,protocol:dict|None=None)->dict:
    """Run a separate comparison; output is atomic and never overwrites a path."""
    started=time.monotonic()
    data_dir,reference_model_dir,out_dir=map(Path,(data_dir,reference_model_dir,out_dir))
    if out_dir.exists():raise FileExistsError(f'Refusing to overwrite {out_dir}')
    resolved=out_dir.resolve()
    if any(resolved.is_relative_to(root.resolve()) or root.resolve().is_relative_to(resolved)
           for root in (data_dir,reference_model_dir)):
        raise ValueError('Output must be separate from input and reference artifact directories')
    protocol=validate_protocol(_read_json(PROTOCOL_PATH) if protocol is None else protocol)
    if not _unit_scope(protocol) and protocol.get('scope')=='UNIT_TEST_FIXTURE_ONLY':
        raise ValueError('Unit-test scope must use an explicitly separate experiment name')
    context=validate_comparison_inputs(data_dir,reference_model_dir,protocol)
    _assert_runtime(protocol)
    preflight_seconds=time.monotonic()-started
    training=context['parts']['train'];validation=context['parts']['validation']
    training_start=time.monotonic()
    xgb_model,selection,search=freeze_validation_selection(training,validation,protocol)
    training_seconds=time.monotonic()-training_start
    validation_start=time.monotonic()
    importance=_permutation_importance(xgb_model,validation,protocol)
    _,validation_targets=_xy(validation)
    xgb_validation=binary_metrics(validation_targets,_xgb_predict(xgb_model,validation),selection['threshold']['threshold'])
    # Model-class comparison follows validation AP only; no test-based promotion.
    reference_ap=context['referenceValidationMetrics']['lightgbm']['averagePrecision']
    validation_recommendation=('xgboost' if selection['validationAveragePrecision']>reference_ap else 'lightgbm')
    frozen={
        **selection,'protocolSha256':context['protocolSha256'],'dataConfigSha256':context['configSha256'],
        'comparisonSourceSha256':context['comparisonSourceSha256'],
        'inputFileSha256':context['inputFileSha256'],'fullRunManifestSha256':context['fullRunManifestSha256'],
        'referenceModelSha256':context['referenceMetadata']['modelSha256'],
        'referenceFrozenThresholds':context['referenceMetadata']['frozenSelection']['thresholds'],
        'validationComparisonRecommendation':validation_recommendation,
        'recommendationPolicy':'Higher October AP between XGBoost and existing LightGBM; tie retains reference LightGBM; never revise using test results',
        'testLabelsMaterialized':False,'comparisonInterpretation':FOLLOWUP_NOTICE,
    }
    validation_seconds=time.monotonic()-validation_start
    _unchanged_inputs(data_dir,reference_model_dir,context)
    out_dir.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=out_dir.name+'-pending-',dir=out_dir.parent) as temporary:
        folder=Path(temporary)
        model_path=folder/'xgboost_model.json'
        xgb_model.save_model(model_path)
        # These two receipts are durably written before dataset/test label parsing.
        import os
        with model_path.open('r+b') as file:os.fsync(file.fileno())
        import xgboost as xgb
        reloaded=xgb.Booster(params={'nthread':1})
        reloaded.load_model(model_path)
        if (reloaded.feature_names!=list(FEATURES)
            or reloaded.num_boosted_rounds()!=selection['boostedRounds']
            or int(reloaded.best_iteration)!=selection['bestIterationZeroBased']
            or not np.array_equal(_xgb_predict(reloaded,validation),_xgb_predict(xgb_model,validation))):
            raise ValueError('Saved XGBoost checkpoint failed exact validation reload verification')
        xgb_model=reloaded
        frozen['savedCheckpointReloadVerifiedOnValidation']=True
        frozen['modelSha256']=sha256(model_path)
        _write_and_sync_json(folder/'selection_frozen.json',frozen)
        frozen_sha=sha256(folder/'selection_frozen.json')
        holdout_start=time.monotonic()
        panel,splits,split_audit=validate_splits(data_dir,context['config'])
        if [row['key'] for row in splits['train']]!=[row['key'] for row in training] or [row['key'] for row in splits['validation']]!=[row['key'] for row in validation]:
            raise ValueError('Post-freeze full membership check disagrees with preflight train/validation')
        first,last=map(date.fromisoformat,SCHEDULE['test'])
        test_panel=[row for row in panel if first<=row['date']<=last]
        extraction=_aware(context['config']['evaluation']['test_label_available_by'])
        eligible=[]
        for row in test_panel:
            if row['labelStatus']=='NOT_ELIGIBLE':continue
            if row['labelStatus']=='KNOWN' and row['labelAvailableAt']>extraction:
                row={**row,'labelStatus':'UNKNOWN','target':None,'labelAvailableAt':None}
            eligible.append(row)
        test_scores={
            'xgboost':_xgb_predict(xgb_model,eligible),
            'lightgbm':_lgb_scores(context['referenceModel'],eligible),
            'exposureRule':exact_exposure_scores(eligible),
            'pastRateReference':rule_scores(eligible,reference=True)}
        thresholds={'xgboost':selection['threshold'],**context['referenceMetadata']['frozenSelection']['thresholds']}
        k=protocol['evaluation']['daily_review_budget_k']
        comparisons={};chosen_keys={}
        for name,scores in test_scores.items():
            known=[i for i,row in enumerate(eligible) if row['labelStatus']=='KNOWN']
            topk,chosen=top_k_metrics(eligible,scores,k)
            chosen_keys[name]=chosen
            comparisons[name]={
                'knownLabelMetrics':binary_metrics([eligible[i]['target'] for i in known],scores[known],thresholds[name]['threshold']),
                'topK':topk,'byMonth':{}}
            for month in (11,12):
                ix=[i for i,row in enumerate(eligible) if row['date'].month==month]
                comparisons[name]['byMonth'][f'2025-{month:02d}']=_segment_metrics(
                    [eligible[i] for i in ix],scores[ix],thresholds[name]['threshold'],k)
        validation_metrics={'xgboost':xgb_validation,**context['referenceValidationMetrics']}
        report={
            'experimentVersion':protocol['experiment_version'],'dataSource':'PROCESS_SIMULATED',
            'isSynthetic':True,'realWorldPerformanceValidated':False,
            'independentFinalHoldout':False,'interpretation':FOLLOWUP_NOTICE,
            'baselineGitCommit':protocol['baseline_git_commit'],
            'inputScope':context['inputScope'],'sourceReceiptMeaning':context['sourceReceiptMeaning'],
            'featureNames':list(FEATURES),'selection':selection,
            'selectionFrozenFileSha256':frozen_sha,'selectionFilesWrittenBeforeTestLabelsMaterialized':True,
            'validationComparisonRecommendation':validation_recommendation,
            'recommendationWasNotChangedAfterTest':True,
            'validation':validation_metrics,'thresholdsFrozenOnValidation':thresholds,
            'referenceValidationMetricsReproduced':True,
            'exposureRuleNumerics':EXPOSURE_RULE_NUMERICS,
            'splitAuditIndependentCheck':split_audit,
            'testCalendarPanelCoverage':_coverage(test_panel),
            'testEligibleQueueCoverage':_coverage(eligible),
            'test':comparisons,'validationPermutationImportance':importance,
            'permutationCaveat':'Marginal permutation may break correlated feature structure; no rank is enforced.',
            'averagePrecisionDefinition':'sklearn.average_precision_score, not XGBoost aucpr or trapezoidal PR-AUC',
            'thresholdMeaning':'Evaluation only; not a Spring operating threshold',
            'runtimeSeconds':{'preflight':preflight_seconds,'candidateTraining':training_seconds,
                'validationDiagnostics':validation_seconds,'postFreezeHoldoutValidationAndScoring':time.monotonic()-holdout_start,
                'total':time.monotonic()-started}}
        predictions=[]
        for i,row in enumerate(eligible):
            record={'hotelId':row['hotelId'],'targetCheckInDate':row['date'].isoformat(),
                    'asOf':row['asOf'].isoformat(),'labelStatus':row['labelStatus'],
                    'target':'' if row['target'] is None else row['target'],
                    'targetConfirmedBookingCount':row['values'][1]}
            for name,scores in test_scores.items():
                record[name+'Score']=float(scores[i])
                record[name+'PredictedPositive']=str(scores[i]>=thresholds[name]['threshold']).lower()
                record[name+'SelectedTopK']=str(row['key'] in chosen_keys[name]).lower()
            predictions.append(record)
        _write_csv(folder/'search_results.csv',search)
        _write_csv(folder/'test_predictions.csv',predictions)
        json_dump(folder/'protocol.json',context['protocolSnapshot'])
        json_dump(folder/'validation_permutation_importance.json',importance)
        json_dump(folder/'evaluation_report.json',report)
        metadata={
            'artifactFormat':'xgboost-json-v1','modelVersion':'process-synthetic-xgboost-compare-0.2-'+frozen['modelSha256'][:12],
            'featureSetVersion':'risk-features-0.2','featureNames':list(FEATURES),
            'isSynthetic':True,'dataSource':'PROCESS_SIMULATED','realWorldPerformanceValidated':False,
            'independentFinalHoldout':False,'interpretation':FOLLOWUP_NOTICE,
            'exposureRuleNumerics':EXPOSURE_RULE_NUMERICS,
            'baselineGitCommit':protocol['baseline_git_commit'],'modelSha256':frozen['modelSha256'],
            'protocolSha256':context['protocolSha256'],'dataConfigSha256':context['configSha256'],
            'inputFileSha256':context['inputFileSha256'],'fullRunManifestSha256':context['fullRunManifestSha256'],
            'referenceModelSha256':context['referenceMetadata']['modelSha256'],
            'referenceManifestSha256':context['referenceManifestSha256'],
            'trainingSourceHashes':context['trainingSourceHashes'],
            'comparisonSourceSha256':context['comparisonSourceSha256'],
            'selectionFrozenFileSha256':frozen_sha,'inputScope':context['inputScope'],
            'sourceReceiptMeaning':context['sourceReceiptMeaning'],
            'selection':selection,'validationComparisonRecommendation':validation_recommendation,
            'libraryVersions':{name:package_version(name) for name in ('xgboost-cpu','lightgbm','numpy','scikit-learn')},
            'pythonVersion':platform.python_version(),'platform':platform.platform(),
            'runtimeSeconds':report['runtimeSeconds']}
        json_dump(folder/'model_metadata.json',metadata)
        def metric(value):return 'N/A' if value is None else f'{value:.6f}'
        lines=['# XGBoost v0.2 후속 탐색 비교','',
            '기존 LightGBM 테스트 결과를 본 뒤 제안된 비교다. 독립 최종 검증이나 실제 호텔 성능 검증이 아니다.',
            '동일한 여섯 특성·CSV·라벨·달력 구간을 사용하며 LightGBM은 재학습하지 않았다.',
            '후보와 모델 클래스 비교 권고는 10월 검증 AP만으로 고정했고 테스트 결과로 변경하지 않았다.',
            '임계값은 검증 F1 기준이며 기존 모델/규칙은 원래 동결 값을 그대로 썼다.',
            '', '| 모델 | 검증 AP | 테스트 AP | ROC-AUC | Precision | Recall | F1 |',
            '|---|---:|---:|---:|---:|---:|---:|']
        for name,value in comparisons.items():
            m=value['knownLabelMetrics']
            lines.append('| '+name+' | '+metric(validation_metrics[name]['averagePrecision'])+' | '+
                         ' | '.join(metric(m[key]) for key in ('averagePrecision','rocAuc','precision','recall','f1'))+' |')
        lines.extend(['','AP는 sklearn average precision이며 XGBoost aucpr와 혼용하지 않는다.',
            'Top-K는 UNKNOWN이 소비한 순위도 유지한다. 관측률·알려진 정답의 precision·전체 선택 기준 하한/상한을 JSON에 함께 기록했다.',
            'CSV 검토 복사본의 바이트와 원래 전체 실행 manifest 결합만 확인했다. 포함되지 않은 원천 이벤트 전체를 다시 검증한 것은 아니다.',
            f'',f'검증 기준 비교 권고: `{validation_recommendation}`. 자동 모델 교체나 배포는 수행하지 않았다.',
            '',f'선택 동결 파일 SHA256: `{frozen_sha}`',''])
        (folder/'evaluation_report.md').write_text('\n'.join(lines),encoding='utf-8')
        _unchanged_inputs(data_dir,reference_model_dir,context)
        files={path.relative_to(folder).as_posix():sha256(path) for path in sorted(folder.rglob('*')) if path.is_file()}
        json_dump(folder/'manifest.json',{'isSynthetic':True,'type':'xgboost-comparison',
            'experimentVersion':protocol['experiment_version'],'modelVersion':metadata['modelVersion'],
            'protocolSha256':context['protocolSha256'],'files':files})
        verify_output(folder)
        if out_dir.exists():raise FileExistsError(f'Refusing to overwrite {out_dir}')
        folder.rename(out_dir)
    return report


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,required=True)
    parser.add_argument('--reference-model',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--protocol',type=Path,default=PROTOCOL_PATH)
    args=parser.parse_args(argv)
    try:
        protocol=_read_json(args.protocol)
        if _unit_scope(protocol):
            parser.error('UNIT_TEST_FIXTURE_ONLY protocols are not permitted by the production CLI')
        report=compare_models(args.input,args.reference_model,args.output,protocol)
    except (ValueError,OSError,KeyError,ImportError) as exc:
        import sys
        print(f'Error: {exc}',file=sys.stderr)
        return 1
    print(json.dumps({'output':str(args.output),'interpretation':report['interpretation'],
                      'validationComparisonRecommendation':report['validationComparisonRecommendation'],
                      'runtimeSeconds':report['runtimeSeconds']},ensure_ascii=False,indent=2))
    return 0


if __name__=='__main__':
    raise SystemExit(main())
