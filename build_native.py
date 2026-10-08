#!/usr/bin/env python3
"""Compila una APK Android real con JDK 17 y herramientas oficiales del SDK.

No requiere Gradle ni bibliotecas externas. Q9_JAVA y Q9_SDK apuntan al JDK y
SDK; Q9_ANDROID_JAR y Q9_BUILD_TOOLS permiten seleccionar rutas separadas.
La firma privada se recibe mediante Q9_KEYSTORE, Q9_ALIAS y Q9_STOREPASS.
"""
import argparse
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--unsigned', action='store_true')
    args = p.parse_args()
    jdk = Path(os.environ.get('Q9_JAVA', os.environ.get('JAVA_HOME', '')))
    sdk = Path(os.environ.get('Q9_SDK', os.environ.get('ANDROID_HOME', '')))
    bt = Path(os.environ.get('Q9_BUILD_TOOLS', str(sdk / 'build-tools' / '35.0.0')))
    android = Path(os.environ.get('Q9_ANDROID_JAR', str(sdk / 'platforms' / 'android-35' / 'android.jar')))
    build = ROOT / 'build'
    if build.exists():
        shutil.rmtree(build)
    for sub in ['resources', 'generated', 'classes', 'dex']:
        (build / sub).mkdir(parents=True)
    src = ROOT / 'app/src/main'
    run(bt / 'aapt2', 'compile', '--dir', src / 'res', '-o', build / 'resources')
    res = sorted((build / 'resources').glob('*.flat'))
    apk = build / 'unsigned.apk'
    asset_flags = ['-A', src / 'assets'] if (src / 'assets').exists() else []
    run(bt / 'aapt2', 'link', '-o', apk, '-I', android, '--manifest', src / 'AndroidManifest.xml', '--java', build / 'generated', *asset_flags, *res)
    sources = sorted((src / 'java').rglob('*.java')) + sorted((build / 'generated').rglob('*.java'))
    run(jdk / 'bin/javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', android, '-d', build / 'classes', *sources)
    jar = build / 'classes.jar'
    with zipfile.ZipFile(jar, 'w') as z:
        for f in (build / 'classes').rglob('*.class'):
            z.write(f, f.relative_to(build / 'classes'))
    run(jdk / 'bin/java', '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '23', '--lib', android, '--output', build / 'dex', jar)
    with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
        for f in (build / 'dex').glob('*.dex'):
            z.write(f, f.name)
    aligned = build / 'aligned.apk'
    run(bt / 'zipalign', '-f', '4', apk, aligned)
    output = build / 'Movimientos_Q9_independiente_v0_3_0.apk'
    if args.unsigned:
        shutil.copyfile(aligned, output)
    else:
        keystore = os.environ['Q9_KEYSTORE']
        alias = os.environ['Q9_ALIAS']
        run(jdk / 'bin/java', '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', keystore, '--ks-key-alias', alias,
            '--ks-pass', 'env:Q9_STOREPASS', '--key-pass', 'env:Q9_STOREPASS', '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--out', output, aligned)
        run(jdk / 'bin/java', '-jar', bt / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', output)
    run(bt / 'aapt2', 'dump', 'badging', output)
    print(output)


if __name__ == '__main__':
    main()
