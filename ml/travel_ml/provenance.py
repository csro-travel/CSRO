"""Post-generation runtime record; never alters simulated events or labels."""
from importlib.metadata import version
import platform
from pathlib import Path
import json

from .common import ROOT,json_dump,sha256
from .storage import verify_output


def record_runtime(root: Path) -> dict:
    manifest=verify_output(root)
    environment={'python':platform.python_version(),'platform':platform.system(),
        'packages':{name:version(name) for name in ('numpy','pandas','pyarrow','lightgbm','scikit-learn','jsonschema')},
        'sourceVersioning':'Generator module SHA-256 in experiment_lock; no unrecorded Git commit is claimed.',
        'orchestrationSourceHashes':{name:sha256(ROOT/'travel_ml'/name) for name in ('__main__.py','provenance.py')}}
    json_dump(root/'runtime_environment.json',environment)
    manifest['runtimeEnvironment']=environment
    manifest['files']={p.relative_to(root).as_posix():sha256(p) for p in sorted(root.rglob('*')) if p.is_file() and p.relative_to(root).as_posix()!='manifest.json'}
    json_dump(root/'manifest.json',manifest)
    verify_output(root)
    return environment
