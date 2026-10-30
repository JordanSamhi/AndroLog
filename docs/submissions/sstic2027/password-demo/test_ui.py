#!/usr/bin/env python3
"""Exercise the visible password field and button on a chosen test device."""
import argparse, datetime, hashlib, json, os, re, shlex, subprocess
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[4]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk',default=os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT')))
p.add_argument('--device',required=True)
p.add_argument('--output',type=Path,default=ROOT/'target/sstic2027/password-demo')
a=p.parse_args()
if not a.sdk: p.error('Set ANDROID_HOME or pass --sdk')
adb=[str(Path(a.sdk)/'platform-tools/adb'),'-s',a.device];pkg='com.example.andrologpassword';dump='/sdcard/androlog-password-test-ui.xml'
def run(*x): return subprocess.check_output([*adb,*x],text=True)
def shell(*x): return run('shell',shlex.join(x))
def get_ui():
 shell('uiautomator','dump',dump)
 return ET.fromstring(shell('cat',dump))
def center(bounds):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',bounds));return str((x1+x2)//2),str((y1+y2)//2)
results=[]
try:
 for input_value,expected in [('incorrect','Accès refusé'),('sstic2027','Accès autorisé : secret de démonstration')]:
  shell('am','force-stop',pkg)
  launch=shell('am','start','-W','-n',pkg+'/.MainActivity')
  if 'Status: ok' not in launch: raise RuntimeError(launch)
  tree=get_ui(); field=tree.find('.//node[@class="android.widget.EditText"]'); button=tree.find('.//node[@class="android.widget.Button"]')
  if field is None or button is None: raise RuntimeError('UI field or button missing')
  shell('input','tap',*center(field.attrib['bounds']))
  shell('input','text',input_value)
  shell('input','keyevent','KEYCODE_BACK')
  shell('input','tap',*center(button.attrib['bounds']))
  after=get_ui(); rendered=[node.attrib.get('text','') for node in after.iter('node')]
  if expected not in rendered: raise AssertionError(f'{input_value}: expected visible {expected!r}, got {rendered!r}')
  results.append({'input':input_value,'visibleResult':expected,'passed':True})
  print(input_value, '->', expected)
finally:
 shell('am','force-stop',pkg)
 shell('rm','-f',dump)
output={'validatedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'test':'EditText input and button tap, fresh app process for each scenario','androidRelease':shell('getprop','ro.build.version.release').strip(),'apiLevel':shell('getprop','ro.build.version.sdk').strip(),'instrumentedSha256':hashlib.sha256((a.output/'instrumented.apk').read_bytes()).hexdigest(),'results':results}
(a.output/'ui-validation.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
