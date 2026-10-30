#!/usr/bin/env python3
"""Verify that the synthetic fixture is absent in cleartext from both APKs."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[4]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=ROOT / 'target/sstic2027/password-demo')
args = parser.parse_args()
needle = 'sstic2027'  # Public synthetic test value, never a real secret.
patterns = [needle.encode('utf-8'), needle.encode('utf-16le'), needle.encode('utf-16be')]
entries = []
for name in ('original.apk', 'instrumented.apk'):
    path = args.output / name
    with ZipFile(path) as archive:
        found = [info.filename for info in archive.infolist()
                 if any(pattern in archive.read(info) for pattern in patterns)]
        assert not found, f'{name}: cleartext found in {found}'
        entries.append({'apk': name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                        'entriesChecked': len(archive.infolist()), 'cleartextMatches': found})
result = {'testValue': needle, 'encodings': ['utf-8', 'utf-16le', 'utf-16be'], 'apks': entries,
          'meaning': 'No literal value in decompressed entries; reversible obfuscation does not prevent static recovery.'}
(args.output / 'plaintext-check.json').write_text(json.dumps(result, indent=2) + '\n')
print('Synthetic value absent from decompressed APK entries in the tested encodings.')
