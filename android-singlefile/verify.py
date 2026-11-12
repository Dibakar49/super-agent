"""CI-only harness: the delivered feature is agentstudio.kt, not another Gradle project."""
from pathlib import Path
import re, shutil, json, hashlib,sys
ROOT=Path(__file__).resolve().parents[1]
DEST=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT/'.agentstudio-validation'
DEST.mkdir(parents=True,exist_ok=True)
for name in ['build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat']:
 shutil.copy2(ROOT/'android'/name,DEST/name)
shutil.copytree(ROOT/'android/gradle',DEST/'gradle',dirs_exist_ok=True)
for module in ['app','core']:
 (DEST/module).mkdir(exist_ok=True);shutil.copy2(ROOT/'android'/module/'build.gradle.kts',DEST/module/'build.gradle.kts')
source=(ROOT/'android-singlefile/agentstudio.kt').read_text()
target=DEST/'app/src/main/kotlin/ai/openbot/agentstudio/agentstudio.kt';target.parent.mkdir(parents=True,exist_ok=True);target.write_text(source)
imports=[line for line in source.splitlines() if line.startswith('import ai.openbot.agentstudio.AS_')]
original={p.stem:p for base in ['android/app/src/main/kotlin','android/core/src/main/kotlin'] for p in (ROOT/base).rglob('*.kt')}
fq={}
for line in imports:
 path=line[len('import '):];scope,name=path.rsplit('.',1);stem=scope.rsplit('.',1)[1][3:]
 pkg=re.search(r'^package ([^\n]+)',original[stem].read_text(),re.M)[1]
 fq[pkg+'.'+name]=path
for kind in ['core','app']:
 for p in (ROOT/f'android/{kind}/src/test/kotlin').rglob('*.kt'):
  if p.name=='LocalAppJourneyTest.kt':continue # Intentionally replaced agent UI has its own journeys below.
  s=p.read_text();s=re.sub(r'^import ai\.openbot\.[^\n]*\n','',s,flags=re.M)
  for old,new in sorted(fq.items(),key=lambda x:-len(x[0])):s=re.sub(r'\b'+r'\s*\.\s*'.join(re.escape(part) for part in old.split('.'))+r'\b',new,s)
  pos=s.index('\n',s.index('package '))+1;s=s[:pos]+'\n'+'\n'.join(imports)+'\n'+s[pos:]
  for old,new in [('"openbot.db"','"agentstudio.db"'),('"model-settings"','"agentstudio-model-settings"'),('"transcription-settings"','"agentstudio-transcription-settings"'),('"connection-secrets"','"agentstudio-connection-secrets"'),('"attachments"','"agentstudio/attachments"'),('"dictation"','"agentstudio/dictation"'),('"models/local.task"','"agentstudio/models/local.task"')]:s=s.replace(old,new)
  s=s.replace('"attachments/','"agentstudio/attachments/').replace('"dictation/','"agentstudio/dictation/')
  s=s.replace('"openbot-','"agentstudio-').replace('"openbot"','"agentstudio"')
  s=s.replace('AgentGate.mutex','ai.openbot.agentstudio.AgentStudioProfiles.gate(context)')
  for prefix in ['approval:','question:','reply:']:s=s.replace('"'+prefix,'"agentstudio:legacy:'+prefix)
  s=s.replace('"ai.openbot.android.MainActivity"','"ai.openbot.agentstudio.AS_MainActivity$MainActivity"')
  s=s.replace('AS_MainActivity$MainActivity','AS_MainActivity\\$MainActivity')
  s=s.replace('\"ai.openbot.agentstudio.AS_MainActivity.MainActivity\"','\"ai.openbot.agentstudio.AS_MainActivity\\$MainActivity\"')
  s=s.replace('\"/dictation/\"','\"/agentstudio/dictation/\"').replace('\"/attachments/\"','\"/agentstudio/attachments/\"')
  target=DEST/'app/src/test/kotlin'/p.relative_to(ROOT/f'android/{kind}/src/test/kotlin');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(s)
for rel in ['android/core/src/main/resources','android/core/src/test/resources']:
 shutil.copytree(ROOT/rel,DEST/'app/src/test/resources',dirs_exist_ok=True)
# Test fixtures represent the embedding host's own Application and Activity.
fixture=ROOT/'android-singlefile/validation'
shutil.copytree(fixture/'main',DEST/'app/src/main',dirs_exist_ok=True)
shutil.copytree(fixture/'test',DEST/'app/src/test',dirs_exist_ok=True)
# Main production runtime is one source plus the host's minimal fixture; no feature resources.
assert not (DEST/'app/src/main/res').exists()
assert not (DEST/'core/src/main').exists()
print(DEST)

files=[]
for rel in ['android/app/src/main','android/core/src/main','android/app/src/test','android/core/src/test','android/licenses']:
 files += [p for p in (ROOT/rel).rglob('*') if p.is_file()]
files.append(ROOT/'LICENSE')
(DEST/'app/src/test/resources/source-fidelity.json').write_text(json.dumps({p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in files},indent=2))
