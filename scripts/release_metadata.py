"""Artifact inventory, build identity and SHA-256 without secrets or user data."""
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys

folder = pathlib.Path(sys.argv[1])
config = pathlib.Path('app/build.gradle.kts').read_text()
version = re.search(r'versionName = "([^"]+)"', config).group(1)
metadata = {
    'version': version,
    'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
    'built_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'signing': sys.argv[2],
    'artifacts': {},
}
for path in sorted(folder.iterdir()):
    if path.suffix in {'.apk', '.aab'}:
        target = path.with_name(f'it-adds-up-{version}-{metadata["commit"][:8]}-{sys.argv[2]}{path.suffix}')
        path.rename(target)
        metadata['artifacts'][target.name] = hashlib.sha256(target.read_bytes()).hexdigest()
(folder / 'build.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
(folder / 'SHA256SUMS').write_text(''.join(f'{sha}  {name}\n' for name, sha in metadata['artifacts'].items()))
