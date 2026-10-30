#!/usr/bin/env python3
"""Capture two independent runs of the password demo on one device."""
import argparse, datetime, hashlib, json, os, re, shlex, signal, subprocess, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk',default=os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT')))
p.add_argument('--device',required=True)
p.add_argument('--output',type=Path,default=ROOT/'target/sstic2027/password-demo')
a=p.parse_args()
if not a.sdk: p.error('Set ANDROID_HOME or pass --sdk')
adb=[str(Path(a.sdk)/'platform-tools/adb'),'-s',a.device]; out=a.output.resolve(); pkg='com.example.andrologpassword'
def run(*args): return subprocess.check_output([*adb,*args],text=True)
def shell(*args): return run('shell',shlex.join(args))
print(run('install','-r',str(out/'instrumented.apk')),end='')
point = next(p for p in json.loads((out/'points.json').read_text()) if p['label']=='mot de passe décodé')
recovered = None
for name in ('incorrect','correct'):
 value = 'incorrect' if name == 'incorrect' else recovered
 if not value: raise RuntimeError('No decoded value was recovered from the first capture')
 d=out/name;d.mkdir(parents=True,exist_ok=True);shell('am','force-stop',pkg)
 started=datetime.datetime.now(datetime.timezone.utc).isoformat();temp=d/'capture.tmp'
 with temp.open('w') as stream:
  proc=subprocess.Popen([*adb,'logcat','-v','threadtime','-T','1','-s','SSTIC_PASSWORD:V','*:S'],stdout=stream,stderr=subprocess.PIPE)
  try:
   time.sleep(.3)
   result=shell('am','start','-W','-n',pkg+'/.MainActivity','--es','password',value)
   if 'Status: ok' not in result: raise RuntimeError(result)
   pid=shell('pidof',pkg).strip()
   if not pid.isdigit(): raise RuntimeError('Expected one demo process: '+pid)
   time.sleep(1)
   with (d/'screen.png').open('wb') as image: subprocess.run([*adb,'exec-out','screencap','-p'],stdout=image,check=True)
  finally:
   proc.send_signal(signal.SIGINT);_,err=proc.communicate(timeout=10)
 pattern=re.compile(r'^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+'+re.escape(pid)+r'\s+\d+\s+[VDIWEFA]\s+SSTIC_PASSWORD\s*:')
 lines=[line for line in temp.read_text().splitlines(keepends=True) if pattern.match(line)]
 if not lines: raise RuntimeError('No AndroLog records: '+err.decode(errors='replace'))
 captured=[line.split('CUSTOM_VALUE='+point['id']+'|',1)[1].strip() for line in lines if 'CUSTOM_VALUE='+point['id']+'|' in line]
 if len(captured)!=1: raise RuntimeError(f'Expected one decoded password record, got {len(captured)}')
 if name=='incorrect':
  recovered=captured[0]
  if not recovered or recovered==value: raise RuntimeError('Decoded password must be distinct from the incorrect input')
 elif captured[0]!=recovered:
  raise RuntimeError('Decoded password changed between captures')
 log=d/'runtime.log';log.write_text(''.join(lines));temp.unlink()
 metadata={'scenario':name,'testInput':value,'inputOrigin':'captured decoded value from incorrect/runtime.log' if name=='correct' else 'deliberately incorrect test input','device':a.device,'androidRelease':shell('getprop','ro.build.version.release').strip(),'apiLevel':shell('getprop','ro.build.version.sdk').strip(),'pid':int(pid),'startedAt':started,'expected':{'decodedPassword':recovered,'allowed':name=='correct'},'logLines':len(lines),'logSha256':hashlib.sha256(log.read_bytes()).hexdigest(),'instrumentedSha256':hashlib.sha256((out/'instrumented.apk').read_bytes()).hexdigest(),'selection':'Original logcat threadtime lines filtered by PID and exact tag; no global log buffer cleared. Input delivered through a demo-only Intent extra to the same button handler used by the visible UI.'}
 (d/'capture.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
 print(f'{name}: {len(lines)} original records')
shell('am','force-stop',pkg)
