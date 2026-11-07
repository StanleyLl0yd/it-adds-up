"""Artifact inventory, build identity and SHA-256 without secrets or user data."""
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import zipfile


def verify_package(path):
    required = (
        {"AndroidManifest.xml", "classes.dex"}
        if path.suffix == ".apk"
        else {"BundleConfig.pb", "base/manifest/AndroidManifest.xml", "base/dex/classes.dex"}
    )
    blocked_extensions = (".jks", ".keystore", ".p12", ".sqlite", ".db")
    with zipfile.ZipFile(path) as package:
        entries = set(package.namelist())
        if package.testzip() or not required.issubset(entries):
            raise ValueError(f"Incomplete or damaged Android package: {path.name}")
        for entry in entries:
            name = pathlib.PurePosixPath(entry).name.lower()
            if name.startswith(".env") or name == "local.properties" or name.endswith(blocked_extensions):
                raise ValueError(f"Unexpected private file in {path.name}: {entry}")
            if name.endswith(".pem") and b"PRIVATE KEY-----" in package.read(entry):
                raise ValueError(f"Private key in {path.name}: {entry}")


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
        verify_package(path)
        target = path.with_name(f'it-adds-up-{version}-{metadata["commit"][:8]}-{sys.argv[2]}{path.suffix}')
        path.rename(target)
        metadata['artifacts'][target.name] = hashlib.sha256(target.read_bytes()).hexdigest()
(folder / 'build.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
(folder / 'SHA256SUMS').write_text(''.join(f'{sha}  {name}\n' for name, sha in metadata['artifacts'].items()))
