#!/usr/bin/env python3
import os
import subprocess
import zipfile
from pathlib import Path

root=Path(__file__).resolve().parent
build=root/'build'
jdk=Path(os.environ['Q9_JAVA'])
bt=Path(os.environ['Q9_BUILD_TOOLS'])
android=Path(os.environ['Q9_ANDROID_JAR'])
def run(*args):subprocess.run([str(a) for a in args],check=True)
(build/'test-classes').mkdir(exist_ok=True)
(build/'test-dex').mkdir(exist_ok=True)
run(bt/'aapt2','link','-o',build/'tests.apk','-I',android,'--manifest',root/'tests/AndroidManifest.xml')
run(jdk/'bin/javac','-encoding','UTF-8','-source','8','-target','8','-classpath',str(android)+os.pathsep+str(build/'classes.jar'),'-d',build/'test-classes',root/'tests/Smoke.java')
with zipfile.ZipFile(build/'test-classes.jar','w') as z:
 for f in (build/'test-classes').rglob('*.class'):z.write(f,f.relative_to(build/'test-classes'))
run(jdk/'bin/java','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','23','--lib',android,'--classpath',build/'classes.jar','--output',build/'test-dex',build/'test-classes.jar')
with zipfile.ZipFile(build/'tests.apk','a',zipfile.ZIP_DEFLATED) as z:
 for f in (build/'test-dex').glob('*.dex'):z.write(f,f.name)
run(bt/'zipalign','-f','4',build/'tests.apk',build/'tests-aligned.apk')
