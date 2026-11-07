#!/usr/bin/env python3
"""Offline Java 25 build against the exact Leaf/plugin dependency lock; no server process is started."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / 'scripts'))
from editions import EDITIONS, edition_entries, write_jar

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--server', type=Path, default=Path('/server/26.2'), help='Read-only dependency source')
parser.add_argument('--skip-tests', action='store_true')
args = parser.parse_args()
classpath = os.environ.get('DIALOGMENU_LOCAL_CLASSPATH')
if not classpath:
    paths = []
    for item in json.loads((root / 'dependencies.lock.json').read_text()):
        path = args.server / item['path']
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise SystemExit(f'Missing or mismatched locked dependency: {path}')
        paths.append(str(path))
    classpath = os.pathsep.join(paths)
# A fresh directory avoids packaging classes left by removed/renamed sources.
import tempfile
build = root / 'build'
build.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='compile-', dir=build) as temporary:
    classes = Path(temporary) / 'classes'
    classes.mkdir()
    sources = sorted((root / 'src/main/java').rglob('*.java'))
    subprocess.run(['javac', '--release', '25', '-encoding', 'UTF-8', '-cp', classpath,
                    '-d', str(classes), *map(str, sources)], check=True)
    if not args.skip_tests:
        tests = sorted((root / 'src/test/java').rglob('*.java'))
        test_classes = Path(temporary) / 'tests'
        test_classes.mkdir()
        runtime = os.pathsep.join([str(classes), str(test_classes), str(root / 'src/test/resources'),
                                  str(root / 'src/main/resources'), classpath])
        subprocess.run(['javac', '--release', '25', '-encoding', 'UTF-8', '-cp', runtime,
                        '-d', str(test_classes), *map(str, tests)], check=True)
        subprocess.run(['java', '-Djava.awt.headless=true', '-cp', runtime,
                        'dev.dialogmenu.RegressionTests', str(root)], check=True)
        subprocess.run(['java', '-Djava.awt.headless=true', '-cp', runtime,
                        'dev.dialogmenu.BindingTests'], check=True)
    version = re.search(r'^version=(.+)$', (root / 'gradle.properties').read_text(), re.M)[1]
    entries = {'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\r\n\r\n'}
    for base in (classes, root / 'src/main/resources'):
        for path in sorted(base.rglob('*')):
            if path.is_file():
                data = path.read_bytes()
                if path.relative_to(base).as_posix() == 'plugin.yml':
                    data = data.replace(b'${version}', version.encode())
                entries[path.relative_to(base).as_posix()] = data
    for edition in EDITIONS:
        output = build / f'DialogMenu-{edition}-{version}.jar'
        write_jar(output, edition_entries(entries, edition))
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        output.with_suffix('.jar.sha256').write_text(f'{digest}  {output.name}\n')
        if not args.skip_tests:
            subprocess.run(['java', '-Djava.awt.headless=true',
                            f'-Ddialogmenu.rgba={str(edition == "native").lower()}', '-cp',
                            os.pathsep.join([str(test_classes), str(output), classpath]),
                            'dev.dialogmenu.EditionTests', edition,
                            str(build / f'edition-checks/{edition}')], check=True)
        print(f'Built {output}\nSHA256 {digest}')
