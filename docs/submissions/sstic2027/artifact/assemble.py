#!/usr/bin/env python3
"""Assemble already-built, already-validated artifacts; run from repository root."""
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

root = Path.cwd()
work = root / 'target/sstic2027/artifact'
out = work / 'distribution'
out.mkdir(parents=True, exist_ok=True)
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
app = 'AndroLog-0.1-SSTIC2027-macos-arm64.zip'
jar = root / 'target/androlog-0.1-jar-with-dependencies.jar'
sources = work / 'OpenJDK21U-jdk-sources_21.0.13_9.tar.gz'
assert sha(sources) == 'b5232c9980d8a0f0459676c084c41c6e2c771ed9e785890b03c6a438406ad1ca'
with zipfile.ZipFile(jar) as z:
    assert z.read('config.properties').decode().strip() == 'apksignerPath=\nzipalignPath='
shutil.copy2(root / 'target/electron' / app, out / app)
shutil.copy2(jar, out / 'androlog-0.1.jar')
shutil.copy2(sources, out / sources.name)
shutil.copy2(root / 'LICENSE', out / 'LICENSE-AndroLog.txt')
shutil.copy2(root / 'docs/submissions/sstic2027/artifact/README-fr.md', out / 'README-fr.md')
shutil.copy2(work / 'evidence/validation.json', out / 'VALIDATION.json')

inventory = []
with zipfile.ZipFile(out / 'third-party-sources-and-notices.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for line in (work / 'evidence/runtime-dependencies.txt').read_text().splitlines():
        match = re.match(r'\s+(\S+):(\S+):jar:(\S+):(compile|runtime)', line)
        if not match:
            continue
        group, name, version, scope = match.groups()
        folder = Path.home().joinpath('.m2/repository', *group.split('.'), name, version)
        prefix = f'maven/{group}/{name}/{version}'
        record = {'coordinate': f'{group}:{name}:{version}', 'scope': scope, 'sourcesIncluded': False}
        for suffix in ['pom', 'sources.jar']:
            file = folder / (f'{name}-{version}.{suffix}' if suffix == 'pom' else f'{name}-{version}-{suffix}')
            if file.exists():
                archive.write(file, f'{prefix}/{file.name}')
                if suffix == 'sources.jar':
                    record['sourcesIncluded'] = True
                    record['sourcesSha256'] = sha(file)
        with zipfile.ZipFile(folder / f'{name}-{version}.jar') as dependency:
            record['classCount'] = sum(p.endswith('.class') for p in dependency.namelist())
            for entry in dependency.namelist():
                if not entry.endswith('/') and re.search(r'(^|/)(license|notice|copying|copyright)', entry, re.I):
                    archive.writestr(f'{prefix}/notices/{entry}', dependency.read(entry))
        if not record['sourcesIncluded']:
            record['note'] = ('Metadata-only JAR: no classes.' if record['classCount'] == 0 else 'XMLPull: source archive not published at this Maven coordinate; bundled POM declares Public Domain.')
        inventory.append(record)
    archive.writestr('inventory.json', json.dumps(inventory, indent=2) + '\n')
    archive.write(root / 'electron-gui/node_modules/animejs/LICENSE.md', 'animejs-4.5.0/LICENSE.md')
    resources = work / 'extracted/AndroLog.app/Contents/Resources'
    for name in ['LICENSE.electron.txt', 'LICENSES.chromium.html']:
        archive.write(resources / name, f'electron-45.1.0/{name}')

licenses = out / 'licenses'
licenses.mkdir(exist_ok=True)
for name in ['LICENSE', 'ADDITIONAL_LICENSE_INFO', 'ASSEMBLY_EXCEPTION']:
    shutil.copy2(resources / 'runtime/legal/java.base' / name, licenses / f'OpenJDK-{name}.txt')

provenance = {
    'andrologVersion': '0.1',
    'buildCheckout': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
    'publicFunctionalSourceCommit': 'cfc8f9924c9368d59059262dee599ad0ccc963df',
    'publicSourceUrl': 'https://github.com/JordanSamhi/AndroLog/tree/cfc8f9924c9368d59059262dee599ad0ccc963df',
    'backendSha256': sha(jar),
    'target': 'macOS arm64',
    'electronVersion': '45.1.0',
    'builderVersion': '26.18.0',
    'buildNodeVersion': '26.13.0',
    'backendBuildJdk': 'Oracle JDK 21.0.12.1 (build tool only; not redistributed)',
    'bundledJava': 'Eclipse Temurin OpenJDK 21.0.13+9',
    'javaReleaseUrl': 'https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9',
    'jdkInputSha256': 'bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a',
    'javaSourcesSha256': sha(sources),
    'jlinkModules': ['java.se', 'jdk.unsupported', 'jdk.crypto.ec', 'jdk.zipfs'],
    'signature': 'ad hoc; not notarized; no Developer ID',
    'sdkBundled': False,
    'mavenArtifacts': len(inventory),
    'mavenSourceArchivesIncluded': sum(x['sourcesIncluded'] for x in inventory),
    'reproducibility': 'Source reconstruction supported; byte-identical build not asserted.'
}
(out / 'PROVENANCE.json').write_text(json.dumps(provenance, indent=2) + '\n')
files = sorted(p for p in out.rglob('*') if p.is_file() and p.name != 'SHA256SUMS')
(out / 'SHA256SUMS').write_text(''.join(f'{sha(p)}  {p.relative_to(out).as_posix()}\n' for p in files))
print(json.dumps({'files': [str(p.relative_to(out)) for p in files], 'bytes': sum(p.stat().st_size for p in files)}, indent=2))
