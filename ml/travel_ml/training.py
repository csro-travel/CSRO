"""Leakage-aware, fixed-calendar v0.2 training on observed synthetic labels.

Only the explicit feature whitelist reaches LightGBM. Hotel identifiers, labels,
private truth and audit columns are metadata, never inputs. Test metrics are
computed only after checkpoint, thresholds and validation diagnostics freeze.
"""
from __future__ import annotations

import csv
from datetime import date, datetime, time, timedelta
import hashlib
from importlib.metadata import version as package_version
import json
import math
from pathlib import Path
import tempfile
from zoneinfo import ZoneInfo

import lightgbm as lgb
import numpy as np
from sklearn.metrics import average_precision_score, roc_auc_score

SEOUL = ZoneInfo('Asia/Seoul')
FEATURES = (
    'pastFailureRate90d', 'targetConfirmedBookingCount', 'bookingVolumeGrowth7d',
    'reconfirmationOverdueRate7d', 'inventoryUnavailableRate7d', 'isPeakDay',
)
SCHEDULE = {
    'train':('2025-04-01','2025-09-30'),
    'validation':('2025-10-01','2025-10-31'),
    'test':('2025-11-01','2025-12-31'),
}
MATURITY = {
    'train':datetime(2025,9,30,9,tzinfo=SEOUL),
    'validation':datetime(2025,10,31,9,tzinfo=SEOUL),
}
META = ('hotelId','targetCheckInDate','asOf','featureSetVersion',
        'target','labelStatus','labelAvailableAt')


def _sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _canonical_sha(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),
                                     ensure_ascii=False,allow_nan=False).encode()).hexdigest()


def _aware(value):
    result = datetime.fromisoformat(value)
    if result.tzinfo is None or result.utcoffset() is None:
        raise ValueError('timezone is required')
    return result.astimezone(SEOUL)


def _number(value, name, lower, upper=None):
    number = float(value)
    if not math.isfinite(number) or number < lower or (upper is not None and number > upper):
        raise ValueError(f'{name} is outside its finite range')
    return number


def _parse_row(raw, source, line):
    try:
        if any(name not in raw for name in (*META,*FEATURES)):
            raise ValueError('required column missing')
        hotel = raw['hotelId'].strip()
        if not hotel:
            raise ValueError('hotelId is empty')
        target_date = date.fromisoformat(raw['targetCheckInDate'])
        if not date(2025,1,1) <= target_date <= date(2025,12,31):
            raise ValueError('target date must be in the 2025 panel')
        asof = _aware(raw['asOf'])
        expected = datetime.combine(target_date-timedelta(days=1),time(9),SEOUL)
        if asof != expected:
            raise ValueError('asOf must be D-1 09:00 Asia/Seoul')
        if raw['featureSetVersion'] != 'risk-features-0.2':
            raise ValueError('unsupported featureSetVersion')
        count_text = str(raw['targetConfirmedBookingCount']).strip()
        if not count_text.isdigit():
            raise ValueError('targetConfirmedBookingCount must be a nonnegative integer')
        count = int(count_text)
        flag = str(raw['isPeakDay']).strip().lower()
        if flag not in ('true','false','1','0'):
            raise ValueError('isPeakDay must be true/false/1/0')
        values = [
            _number(raw['pastFailureRate90d'],FEATURES[0],0,1), count,
            _number(raw['bookingVolumeGrowth7d'],FEATURES[2],-1,10),
            _number(raw['reconfirmationOverdueRate7d'],FEATURES[3],0,1),
            _number(raw['inventoryUnavailableRate7d'],FEATURES[4],0,1),
            1 if flag in ('true','1') else 0,
        ]
        status = raw['labelStatus']
        label_text = raw['target'].strip()
        observed = raw['labelAvailableAt'].strip()
        if status == 'KNOWN':
            if label_text not in ('0','1') or not observed or count == 0:
                raise ValueError('KNOWN requires a binary target, observation timestamp and nonempty cohort')
            label = int(label_text)
            label_at = _aware(observed)
            if label_at <= asof:
                raise ValueError('outcome must become available after prediction')
        elif status in ('UNKNOWN','NOT_ELIGIBLE'):
            if label_text or observed:
                raise ValueError('UNKNOWN/NOT_ELIGIBLE must leave label and label timestamp blank')
            if (status == 'NOT_ELIGIBLE') != (count == 0):
                raise ValueError('NOT_ELIGIBLE must mean empty cohort; UNKNOWN is nonempty')
            label, label_at = None, None
        else:
            raise ValueError('unsupported labelStatus')
        return {'hotelId':hotel,'date':target_date,'asOf':asof,'values':values,
                'labelStatus':status,'target':label,'labelAvailableAt':label_at,
                'key':(hotel,target_date,asof)}
    except (ValueError,TypeError,KeyError) as exc:
        raise ValueError(f'{source}, CSV line {line}: {exc}') from exc


def read_rows(path):
    path = Path(path)
    with path.open(newline='',encoding='utf-8-sig') as file:
        reader = csv.DictReader(file)
        if reader.fieldnames is None:
            raise ValueError(f'{path}: empty CSV')
        rows = [_parse_row(raw,str(path),line) for line,raw in enumerate(reader,start=2)]
    keys = [row['key'] for row in rows]
    if len(keys) != len(set(keys)):
        raise ValueError(f'{path}: duplicate hotel/date/asOf')
    return sorted(rows,key=lambda row:(row['date'],row['hotelId']))


def _same_row(a,b):
    return all(a[name] == b[name] for name in ('key','values','target','labelStatus','labelAvailableAt'))


def validate_splits(data_dir, config):
    """Recompute expected split membership independently of generated split files."""
    data_dir = Path(data_dir)
    evaluation = config['evaluation']
    if config.get('feature_set_version') != 'risk-features-0.2' or tuple(config['features']['ordered_names']) != FEATURES:
        raise ValueError('config must declare the exact v0.2 feature whitelist and order')
    for name,bounds in SCHEDULE.items():
        if tuple(evaluation[f'{name}_dates']) != bounds:
            raise ValueError('v0.2 calendar split dates changed without a new contract')
    if _aware(evaluation['train_label_available_before']) != MATURITY['train']:
        raise ValueError('train label maturity cutoff changed')
    if _aware(evaluation['validation_label_available_before']) != MATURITY['validation']:
        raise ValueError('validation label maturity cutoff changed')
    extraction = _aware(evaluation['test_label_available_by'])
    panel = read_rows(data_dir/'dataset.csv')
    by_key = {row['key']:row for row in panel}
    selected = {}
    audit = {}
    for name,bounds in SCHEDULE.items():
        first,last = map(date.fromisoformat,bounds)
        population = [row for row in panel if first <= row['date'] <= last]
        expected = []
        counts = {'calendarPanelRows':len(population),'knownIncluded':0,
                  'unknownExcluded':0,'notEligibleExcluded':0,'maturityPurged':0}
        for row in population:
            if row['labelStatus'] == 'NOT_ELIGIBLE':
                counts['notEligibleExcluded'] += 1
            elif row['labelStatus'] == 'UNKNOWN':
                counts['unknownExcluded'] += 1
            elif (name in MATURITY and row['labelAvailableAt'] >= MATURITY[name]) or (
                    name == 'test' and row['labelAvailableAt'] > extraction):
                counts['maturityPurged'] += 1
            else:
                expected.append(row)
                counts['knownIncluded'] += 1
        supplied = read_rows(data_dir/f'{name}.csv')
        if {r['key'] for r in supplied} != {r['key'] for r in expected}:
            raise ValueError(f'{name}.csv does not match fixed-calendar/observed-label/maturity membership')
        if any(not _same_row(row,by_key[row['key']]) for row in supplied):
            raise ValueError(f'{name}.csv differs from dataset.csv feature or label snapshot')
        selected[name] = supplied
        audit[name] = counts
    if {r['target'] for r in selected['train']} != {0,1}:
        raise ValueError('Training needs both observed classes after maturity purge. Do not inject labels; revise and version the simulation using training/pilot evidence.')
    if not selected['validation']:
        raise ValueError('Validation has no mature observed labels; no checkpoint selection is possible')
    return panel,selected,audit


def _xy(rows, indices=None):
    indices = tuple(range(len(FEATURES))) if indices is None else indices
    return (np.asarray([[row['values'][i] for i in indices] for row in rows],dtype=float),
            np.asarray([-1 if row['target'] is None else row['target'] for row in rows],dtype=np.int8))


def rule_scores(rows, reference=False):
    values = []
    for row in rows:
        p,n = row['values'][:2]
        if reference:
            score = p
        elif n == 0:
            score = 0.0
        elif p == 1:
            score = 1.0
        else:
            score = -math.expm1(n*math.log1p(-p))
        values.append(score)
    return np.asarray(values,dtype=float)


def select_threshold(targets, scores):
    """Validation F1 maximum; ties prefer precision, then a larger threshold."""
    y,s = np.asarray(targets,dtype=int),np.asarray(scores,dtype=float)
    if len(y) == 0:
        return {'threshold':1.0,'reason':'no_known_validation_labels'}
    if int(y.sum()) == 0:
        return {'threshold':float(np.nextafter(s.max(),math.inf)),
                'reason':'no_positive_validation_labels; select no positives'}
    order = np.argsort(-s,kind='stable')
    ordered = s[order]
    tp = np.cumsum(y[order])
    ends = np.r_[np.flatnonzero(ordered[:-1]!=ordered[1:]),len(ordered)-1]
    positives = int(y.sum())
    best = None
    for end in ends:
        true_positive = int(tp[end]); predicted = int(end)+1
        precision = true_positive/predicted
        f1 = 2*true_positive/(predicted+positives)
        threshold = float(ordered[end])
        candidate = (f1,precision,threshold)
        if best is None or candidate > best:
            best = candidate
    return {'threshold':best[2],'validationF1':best[0],
            'validationPrecision':best[1],'reason':'validation F1; tie precision, threshold'}


def binary_metrics(targets, scores, threshold):
    y = np.asarray(targets,dtype=int)
    s = np.asarray(scores,dtype=float)
    prediction = s >= threshold
    tp = int(np.sum((y==1)&prediction))
    fp = int(np.sum((y==0)&prediction))
    fn = int(np.sum((y==1)&~prediction))
    tn = int(np.sum((y==0)&~prediction))
    both = len(set(y.tolist())) == 2
    undefined = {}
    if not both:
        undefined.update({'averagePrecision':'requires both observed classes for discrimination assessment',
                          'rocAuc':'requires both observed classes'})
    precision = tp/(tp+fp) if tp+fp else None
    recall = tp/(tp+fn) if tp+fn else None
    f1 = 2*tp/(2*tp+fp+fn) if 2*tp+fp+fn else None
    if precision is None:undefined['precision']='no predicted positives'
    if recall is None:undefined['recall']='no observed positive targets'
    if f1 is None:undefined['f1']='no observed or predicted positives'
    return {'knownRows':len(y),'knownPositives':int(y.sum()),
            'positiveRate':float(y.mean()) if len(y) else None,
            'averagePrecision':float(average_precision_score(y,s)) if both else None,
            'averagePrecisionMethod':'sklearn.average_precision_score; not trapezoidal PR integration',
            'rocAuc':float(roc_auc_score(y,s)) if both else None,
            'precision':precision,'recall':recall,'f1':f1,
            'confusionMatrix':{'tn':tn,'fp':fp,'fn':fn,'tp':tp},
            'threshold':float(threshold),'undefinedReasons':undefined}


def top_k_metrics(rows, scores, k=10):
    """Rank eligible queues including UNKNOWN; preserve their consumed slots."""
    if len(rows) != len(scores) or k < 1:
        raise ValueError('top-K requires aligned scores and a positive budget')
    by_day = {}
    for row,score in zip(rows,scores):
        if row['labelStatus'] != 'NOT_ELIGIBLE':
            by_day.setdefault(row['date'],[]).append((row,float(score)))
    chosen=[]
    selections=[]
    for day,population in sorted(by_day.items()):
        ranked=sorted(population,key=lambda pair:(-pair[1],pair[0]['hotelId']))
        selected=ranked[:k]
        chosen.extend(row for row,_ in selected)
        selections.extend(row['key'] for row,_ in selected)
    known=[row for row in chosen if row['labelStatus']=='KNOWN']
    unknown=sum(row['labelStatus']=='UNKNOWN' for row in chosen)
    tp=sum(row['target']==1 for row in known)
    selected_count=len(chosen)
    population_positive=sum(row['target']==1 for row in rows if row['labelStatus']=='KNOWN')
    eligible_count=sum(len(part) for part in by_day.values())
    eligible_known=sum(row['labelStatus']=='KNOWN' for row in rows)
    result={
        'dailyBudgetK':k,'daysWithEligibleQueue':len(by_day),
        'eligibleRows':eligible_count,'eligibleKnownRows':eligible_known,
        'eligibleLabelCoverage':eligible_known/eligible_count if eligible_count else None,
        'selectedRows':selected_count,'selectedKnownRows':len(known),
        'selectedUnknownRows':unknown,'selectedKnownTruePositives':tp,
        'selectedLabelCoverage':len(known)/selected_count if selected_count else None,
        'knownPrecision':tp/len(known) if known else None,
        'precisionLowerBound':tp/selected_count if selected_count else None,
        'precisionUpperBound':(tp+unknown)/selected_count if selected_count else None,
        'knownPositiveRecall':tp/population_positive if population_positive else None,
        'selectionPolicy':'Rank all eligible including UNKNOWN; score descending then hotelId ascending',
        'boundMeaning':'Unknown selected outcomes may all be negative (lower) or positive (upper); not confidence intervals',
    }
    return result,set(selections)


def _params(config, leaves, child):
    settings = config['evaluation']['lightgbm']
    seed = int(config.get('seed',42))
    return {'objective':'binary','learning_rate':float(settings['learning_rate']),
            'num_leaves':int(leaves),'min_data_in_leaf':int(child),
            'metric':'average_precision','verbosity':-1,'num_threads':1,
            'seed':seed,'bagging_seed':int(settings.get('bagging_seed',seed)),
            'feature_fraction_seed':int(settings.get('feature_fraction_seed',seed)),
            'data_random_seed':int(settings.get('data_random_seed',seed)),
            'deterministic':True,'force_col_wise':True,
            'scale_pos_weight':float(settings.get('scale_pos_weight',1.0))}


def _fit(train_rows, valid_rows, config, leaves, child, indices=None):
    indices=tuple(range(len(FEATURES))) if indices is None else tuple(indices)
    names=[FEATURES[i] for i in indices]
    x,y=_xy(train_rows,indices)
    xv,yv=_xy(valid_rows,indices)
    params=_params(config,leaves,child)
    # AP cannot discriminate a single-class validation population. Keep the
    # declared early-stopping procedure with an explicitly recorded logloss fallback.
    if len(set(yv.tolist()))!=2:
        params['metric']='binary_logloss'
    settings=config['evaluation']['lightgbm']
    booster=lgb.train(params,lgb.Dataset(x,label=y,feature_name=names,free_raw_data=False),
        num_boost_round=int(settings['n_estimators']),
        valid_sets=[lgb.Dataset(xv,label=yv,feature_name=names,reference=None)],
        callbacks=[lgb.early_stopping(int(settings['early_stopping_rounds']),
                   first_metric_only=True,verbose=False),lgb.log_evaluation(0)])
    return booster


def _predict(booster, rows, indices=None):
    if not rows:return np.empty(0,dtype=float)
    x,_=_xy(rows,indices)
    prediction=np.asarray(booster.predict(x,num_iteration=booster.best_iteration,num_threads=1),dtype=float)
    if not np.all(np.isfinite(prediction)) or np.any((prediction<0)|(prediction>1)):
        raise ValueError('model returned invalid binary scores')
    return prediction


def _coverage(rows):
    count=len(rows)
    known=sum(r['labelStatus']=='KNOWN' for r in rows)
    eligible=sum(r['labelStatus']!='NOT_ELIGIBLE' for r in rows)
    return {'calendarRows':count,'eligibleRows':eligible,'knownRows':known,
            'unknownRows':sum(r['labelStatus']=='UNKNOWN' for r in rows),
            'notEligibleRows':count-eligible,'labelCoverage':known/eligible if eligible else None}


def _segment_metrics(population, scores, threshold, k):
    known=[i for i,r in enumerate(population) if r['labelStatus']=='KNOWN']
    result={'coverage':_coverage(population),
            'knownLabelMetrics':binary_metrics([population[i]['target'] for i in known],scores[known],threshold),
            'topK':top_k_metrics(population,scores,k)[0]}
    return result


def _write_csv(path,fieldnames,rows):
    with path.open('w',newline='',encoding='utf-8') as file:
        writer=csv.DictWriter(file,fieldnames=fieldnames)
        writer.writeheader();writer.writerows(rows)


def train_models(data_dir: Path, out_dir: Path, config: dict)->dict:
    """Produce one selected checkpoint and honest validation/test diagnostics.

    Raises FileExistsError for any existing output directory. The supplied split
    files must exactly match independently recomputed mature KNOWN membership.
    """
    data_dir,out_dir=Path(data_dir),Path(out_dir)
    if out_dir.exists():raise FileExistsError(f'Refusing to overwrite {out_dir}')
    panel,splits,audit=validate_splits(data_dir,config)
    training,validation=splits['train'],splits['validation']
    _,yv=_xy(validation)
    candidates=[]
    best=None
    grid=config['evaluation']['lightgbm']
    for leaves in grid['num_leaves_candidates']:
        for child in grid['min_child_samples_candidates']:
            model=_fit(training,validation,config,leaves,child)
            scores=_predict(model,validation)
            ap=float(average_precision_score(yv,scores)) if len(set(yv.tolist()))==2 else None
            result={'numLeaves':int(leaves),'minChildSamples':int(child),
                    'bestIteration':int(model.best_iteration or model.current_iteration()),
                    'validationAveragePrecision':ap,
                    'earlyStoppingMetric':'average_precision' if ap is not None else 'binary_logloss_single_class_fallback'}
            candidates.append(result)
            # Fewer leaves, larger minimum leaf and then fewer rounds = simpler.
            key=(-(ap if ap is not None else -1),int(leaves),-int(child),result['bestIteration'])
            if best is None or key<best[0]:best=(key,model,result,scores)
    if best is None:raise ValueError('No LightGBM candidates configured')
    _,booster,selection,validation_score=best
    validation_scores={
        'lightgbm':validation_score,
        'exposureRule':rule_scores(validation),
        'pastRateReference':rule_scores(validation,reference=True)}
    thresholds={name:select_threshold(yv,scores) for name,scores in validation_scores.items()}
    validation_metrics={name:binary_metrics(yv,scores,thresholds[name]['threshold'])
                        for name,scores in validation_scores.items()}
    # Validation-only interpretation; never tune generator/feature rules to test results.
    importance=[]
    xv,_=_xy(validation)
    base_ap=selection['validationAveragePrecision']
    rng=np.random.default_rng(int(config.get('seed',42)))
    for index,name in enumerate(FEATURES):
        drops=[]
        for repeat in range(5):
            shuffled=xv.copy()
            shuffled[:,index]=rng.permutation(shuffled[:,index])
            scores=np.asarray(booster.predict(shuffled,num_iteration=booster.best_iteration,num_threads=1))
            if base_ap is not None:drops.append(base_ap-float(average_precision_score(yv,scores)))
        importance.append({'feature':name,'repeats':5,'meanAveragePrecisionDrop':float(np.mean(drops)) if drops else None,
                           'stdAveragePrecisionDrop':float(np.std(drops)) if drops else None,
                           'reason':None if drops else 'validation contains one observed class'})
    ablated_indices=tuple(range(1,len(FEATURES)))
    ablated=_fit(training,validation,config,selection['numLeaves'],selection['minChildSamples'],ablated_indices)
    ablated_scores=_predict(ablated,validation,ablated_indices)
    ablation={'removedFeature':FEATURES[0], 'parameters':'same selected leaves/minimum child; train only; validation early stopping',
              'validationAveragePrecision':float(average_precision_score(yv,ablated_scores)) if base_ap is not None else None,
              'fullModelValidationAveragePrecision':base_ap,
              'differenceFullMinusAblated':base_ap-float(average_precision_score(yv,ablated_scores)) if base_ap is not None else None,
              'bestIteration':int(ablated.best_iteration or ablated.current_iteration())}
    frozen_selection={
        'selection':selection,'thresholds':thresholds,'featureNames':list(FEATURES),
        'configSha256':_canonical_sha(config),
        'finalModelPolicy':'Apr-Sep trained checkpoint selected on October; no train+validation refit'}
    frozen_sha=_canonical_sha(frozen_selection)
    # Selection, interpretation and thresholds are now frozen. Only now evaluate test.
    first,last=map(date.fromisoformat,SCHEDULE['test'])
    test_panel=[row for row in panel if first<=row['date']<=last]
    extraction=_aware(config['evaluation']['test_label_available_by'])
    test_eligible=[row for row in test_panel if row['labelStatus']!='NOT_ELIGIBLE']
    # Any delayed KNOWN label not available by extraction must behave as UNKNOWN
    # in ranking/evaluation. Its feature row still occupies its original position.
    evaluation_rows=[]
    for row in test_eligible:
        if row['labelStatus']=='KNOWN' and row['labelAvailableAt']>extraction:
            row={**row,'labelStatus':'UNKNOWN','target':None,'labelAvailableAt':None}
        evaluation_rows.append(row)
    test_scores={'lightgbm':_predict(booster,evaluation_rows),
                 'exposureRule':rule_scores(evaluation_rows),
                 'pastRateReference':rule_scores(evaluation_rows,reference=True)}
    k=int(config['evaluation']['daily_review_budget_k'])
    test_results={}
    selected_keys={}
    for name,scores in test_scores.items():
        known=[i for i,row in enumerate(evaluation_rows) if row['labelStatus']=='KNOWN']
        topk,chosen=top_k_metrics(evaluation_rows,scores,k)
        selected_keys[name]=chosen
        test_results[name]={
          'knownLabelMetrics':binary_metrics([evaluation_rows[i]['target'] for i in known],scores[known],thresholds[name]['threshold']),
          'topK':topk,'byMonth':{},'byCohortSize':{}}
        for month in (11,12):
            ix=[i for i,row in enumerate(evaluation_rows) if row['date'].month==month]
            test_results[name]['byMonth'][f'2025-{month:02d}']=_segment_metrics([evaluation_rows[i] for i in ix],scores[ix],thresholds[name]['threshold'],k)
        for label,condition in [('1',lambda n:n==1),('2-4',lambda n:2<=n<=4),('5+',lambda n:n>=5)]:
            ix=[i for i,row in enumerate(evaluation_rows) if condition(row['values'][1])]
            # Segment metrics retain the globally selected queue rather than
            # granting a new independent K budget to every cohort-size segment.
            group=[evaluation_rows[i] for i in ix]
            group_known=[j for j,row in enumerate(group) if row['labelStatus']=='KNOWN']
            group_scores=scores[ix]
            chosen_rows=[r for r in group if r['key'] in chosen]
            known_chosen=[r for r in chosen_rows if r['labelStatus']=='KNOWN']
            tp=sum(r['target']==1 for r in known_chosen)
            unknown_chosen=sum(r['labelStatus']=='UNKNOWN' for r in chosen_rows)
            test_results[name]['byCohortSize'][label]={
                'coverage':_coverage(group),
                'knownLabelMetrics':binary_metrics([group[j]['target'] for j in group_known],group_scores[group_known],thresholds[name]['threshold']),
                'globalQueueSelections':{'selectedRows':len(chosen_rows),'selectedKnownRows':len(known_chosen),
                    'selectedUnknownRows':unknown_chosen,'knownTruePositives':tp,
                    'precisionLowerBound':tp/len(chosen_rows) if chosen_rows else None,
                    'precisionUpperBound':(tp+unknown_chosen)/len(chosen_rows) if chosen_rows else None}}
    report={
        'dataSource':'PROCESS_SIMULATED','rulesVersion':config.get('rules_version'),
        'realWorldPerformanceValidated':False,
        'interpretation':'Observed labels from simulated process under one fixed recovery policy; not real-world accuracy, calibrated failure probabilities or causal AI benefit.',
        'generalization':'future dates at the same simulated hotels; not unseen-hotel validation',
        'splitAuditIndependentCheck':audit,
        'testPanelCoverage':_coverage(test_panel),'testEvaluatedEligibleCoverage':_coverage(evaluation_rows),
        'selectionFrozenBeforeTestSha256':frozen_sha,
        'selection':selection,'thresholdsSelectedOnValidation':thresholds,
        'thresholdMeaning':'Evaluation thresholds, not Spring operational policy',
        'validation':validation_metrics,'validationPermutationImportance':importance,
        'validationPastRateAblation':ablation,
        'validationImportanceCaveat':'Marginal permutation can break correlated feature structure; no importance rank is enforced.',
        'test':test_results,
        'segmentLimitations':'Hotel capacity, latent profile and failure cause require separately authorized audit joins; unavailable from the model-input CSV and never used as training features.'}
    out_dir.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=out_dir.name+'-pending-',dir=out_dir.parent) as tmp:
        folder=Path(tmp)
        booster.save_model(str(folder/'model.txt'),num_iteration=booster.best_iteration)
        model_sha=_sha(folder/'model.txt')
        input_hashes={name:_sha(data_dir/name) for name in ('dataset.csv','train.csv','validation.csv','test.csv')}
        if (data_dir/'split_audit.csv').exists():input_hashes['split_audit.csv']=_sha(data_dir/'split_audit.csv')
        metadata={
            'artifactFormat':'lightgbm-text-v1','modelVersion':'process-synthetic-lightgbm-0.2-'+model_sha[:12],
            'featureSetVersion':'risk-features-0.2','featureNames':list(FEATURES),
            'dataSource':'PROCESS_SIMULATED','realWorldPerformanceValidated':False,
            'rulesVersion':config.get('rules_version'),'modelSha256':model_sha,
            'configSha256':_canonical_sha(config),'inputFileSha256':input_hashes,
            'inputDatasetManifestSha256':_sha(data_dir/'manifest.json') if (data_dir/'manifest.json').exists() else None,
            'seed':int(config.get('seed',42)),'trainingRows':len(training),
            'validationRows':len(validation),'bestIteration':selection['bestIteration'],
            'parameters':_params(config,selection['numLeaves'],selection['minChildSamples']),
            'selectedValidationAveragePrecision':selection['validationAveragePrecision'],
            'frozenSelection':frozen_selection,'selectionFrozenBeforeTestSha256':frozen_sha,
            'libraryVersions':{lib:package_version(lib) for lib in ('lightgbm','numpy','scikit-learn')},
            'sourceCommit':config.get('source_commit','UNRECORDED_NO_SOURCE_COMMIT_PROVIDED'),
            'inputWhitelistEnforced':True,
        }
        for name,value in [('model_metadata.json',metadata),('evaluation_report.json',report)]:
            (folder/name).write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')
        _write_csv(folder/'search_results.csv',list(candidates[0]),candidates)
        predictions=[]
        for i,row in enumerate(evaluation_rows):
            output={
                'hotelId':row['hotelId'],'targetCheckInDate':row['date'].isoformat(),
                'asOf':row['asOf'].isoformat(),'labelStatus':row['labelStatus'],
                'target':'' if row['target'] is None else row['target'],
                'targetConfirmedBookingCount':row['values'][1]}
            for name,scores in test_scores.items():
                output[name+'Score']=float(scores[i])
                output[name+'PredictedPositive']=str(scores[i]>=thresholds[name]['threshold']).lower()
                output[name+'SelectedTopK']=str(row['key'] in selected_keys[name]).lower()
            predictions.append(output)
        fields=list(predictions[0]) if predictions else [*META,'lightgbmScore','exposureRuleScore','pastRateReferenceScore']
        _write_csv(folder/'test_predictions.csv',fields,predictions)
        def number(value):return 'N/A' if value is None else f'{value:.4f}'
        markdown=[
          '# v0.2 합성 과정 데이터 평가',
          '', '실제 호텔 예측 성능이 아니다. 같은 가상 호텔의 미래 날짜를 고정 복구 정책 아래 평가했다.',
          '', '4~9월 학습 checkpoint를 10월 검증 AP로 선택했다. 학습+검증 재학습은 하지 않았다.',
          '임계값은 검증 F1 최대이며 운영 선별 정책과 별개다. AP는 average precision 방식이다.',
          '', '| 모델 | 테스트 AP | ROC-AUC | Precision | Recall | F1 |',
          '|---|---:|---:|---:|---:|---:|']
        for name,result in test_results.items():
            m=result['knownLabelMetrics']
            markdown.append('| '+name+' | '+' | '.join(number(m[key]) for key in ('averagePrecision','rocAuc','precision','recall','f1'))+' |')
        markdown.extend(['','Top-K는 UNKNOWN도 순위에 포함한다. 확인된 라벨의 precision과 전체 선택 기준 하한·상한·관측률을 JSON에 함께 기록했다.',
                         '미확인은 정상으로 바꾸지 않았으며 단일 클래스에서 정의되지 않는 지표는 null과 사유를 남겼다.',
                         '검증 permutation importance와 과거 장애율 제거 비교는 결과 그대로 기록하고 중요도 순위를 강제하지 않았다.',
                         '',f'검증 선택 동결 SHA256: `{frozen_sha}`',''])
        (folder/'evaluation_report.md').write_text('\n'.join(markdown),encoding='utf-8')
        artifact_files={path.relative_to(folder).as_posix():_sha(path) for path in sorted(folder.rglob('*')) if path.is_file()}
        manifest={'isSynthetic':True,'type':'model','featureSetVersion':'risk-features-0.2',
                  'modelVersion':metadata['modelVersion'],'configSha256':metadata['configSha256'],
                  'files':artifact_files}
        (folder/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,sort_keys=True,indent=2,allow_nan=False)+'\n',encoding='utf-8')
        if out_dir.exists():raise FileExistsError(f'Refusing to overwrite {out_dir}')
        folder.rename(out_dir)
    return report
