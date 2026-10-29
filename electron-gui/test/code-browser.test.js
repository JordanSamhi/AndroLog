'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { Backend } = require('../src/backend');
const { CodeBrowserSession } = require('../src/code-browser');
const processTest = { skip: process.platform === 'win32' };

async function fixture(t, mode = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androlog-code-session-'));
  const java = path.join(dir, 'java'), jar = path.join(dir, 'androlog.jar'), apk = path.join(dir, 'app.apk');
  const platforms = path.join(dir, 'platforms');
  await fs.mkdir(path.join(platforms, 'android-36'), { recursive: true });
  await fs.writeFile(path.join(platforms, 'android-36', 'android.jar'), 'platform');
  await fs.writeFile(jar, 'jar'); await fs.writeFile(apk, 'apk'); await fs.writeFile(path.join(dir, 'mode.json'), JSON.stringify(mode));
  await fs.writeFile(java, `#!/usr/bin/env node
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),readline=require('node:readline');
const mode=JSON.parse(fs.readFileSync(path.join(__dirname,'mode.json'),'utf8'));
const journal=path.join(__dirname,'journal.log');
fs.appendFileSync(journal,'start\\n');
const send=(id,result,error)=>process.stdout.write('@@ANDROLOG_CODE '+JSON.stringify(error?{id,error}:{id,result})+'\\n');
const apk=process.argv[process.argv.indexOf('--apk')+1];
const hash=crypto.createHash('sha256').update(fs.readFileSync(apk)).digest('hex');
const input=readline.createInterface({input:process.stdin});
input.on('line',line=>{const request=JSON.parse(line);fs.appendFileSync(journal,request.action+'\\n');
  if(request.action==='crash')return process.exit(8);
  if(request.action==='hang')return;
  if(request.action==='malformed')return process.stdout.write('@@ANDROLOG_CODE nope\\n');
  if(request.action==='stale')return send(request.id,null,'The APK changed while browsing its code. Close the code browser and choose the APK again.');
  setTimeout(()=>send(request.id,{...request,text:'hello é 世界'},request.action==='error'?'Unknown method':null),mode.delay||0);
});
input.on('close',()=>process.exit(0));
if(!mode.hangStartup)setTimeout(()=>send(0,{apkSha256:hash,lazy:true,methods:[{className:'org.example.Main',signature:'<org.example.Main: void run()>'}]}),mode.startupDelay||0);
`);
  await fs.chmod(java, 0o755);
  const backend = new Backend({ jar, tempRoot: dir });
  t.after(async () => { await backend.shutdown(); await fs.rm(dir, { recursive: true, force: true }); });
  return { dir, java, jar, apk, platforms, backend, settings: { java, platforms }, journal: () => fs.readFile(path.join(dir, 'journal.log'), 'utf8') };
}
async function waitUntil(check) {
  const start = Date.now();
  while (!check()) { if (Date.now() - start > 3000) throw new Error('Timed out waiting for fixture process'); await new Promise(resolve => setTimeout(resolve, 10)); }
}

test('one Java session serves the outline and serializes lazy class and variable requests', processTest, async t => {
  const fixtureData = await fixture(t, { delay: 15 }); const { backend, apk, settings } = fixtureData;
  const first = await backend.inspect(apk, settings), second = await backend.inspect(apk, settings);
  assert.equal(first, second); assert.match(first.sessionId, /^[a-f0-9-]{36}$/); assert.equal(backend.active, null); assert.equal(backend.codeBrowser.session.busy, false);
  const [klass, variables] = await Promise.all([backend.inspectClass(apk, settings, 'org.example.Main'), backend.inspectVariables(apk, settings, '<org.example.Main: void run()>')]);
  assert.equal(klass.className, 'org.example.Main'); assert.equal(variables.method, '<org.example.Main: void run()>');
  assert.equal(variables.text, 'hello é 世界');
  assert.equal(await fixtureData.journal(), 'start\nclass\nvariables\n');
  await backend.closeCodeBrowser(); assert.equal(backend.codeBrowser, null); assert.equal(backend.workspaces.size, 0);
});

test('reopening reuses only an unchanged APK and configuration, and rejects stale lazy requests', processTest, async t => {
  const data = await fixture(t); const { backend, apk, settings } = data;
  const old = await backend.inspect(apk, settings);
  await fs.writeFile(apk, 'changed APK');
  await assert.rejects(backend.inspectClass(apk, settings, 'org.example.Main'), /changed/);
  assert.equal(backend.codeBrowser, null);
  const updated = await backend.inspect(apk, settings); assert.notEqual(updated.apkSha256, old.apkSha256);
  assert.notEqual(updated.sessionId, old.sessionId);
  const java2 = path.join(data.dir, 'java2'); await fs.copyFile(data.java, java2); await fs.chmod(java2, 0o755);
  const changedRuntime = await backend.inspect(apk, { ...settings, java: java2 }); assert.notEqual(changedRuntime.sessionId, updated.sessionId);
  assert.equal((await data.journal()).split('\n').filter(value => value === 'start').length, 3);
});

test('cancel during startup rejects the waiting request and permits a fresh session', processTest, async t => {
  const data = await fixture(t, { hangStartup: true }); const { backend, apk, settings } = data;
  const loading = backend.inspect(apk, settings); const rejected = assert.rejects(loading, /cancelled/);
  await waitUntil(() => backend.codeBrowser?.session.child?.pid); backend.cancel(); await rejected;
  await fs.writeFile(path.join(data.dir, 'mode.json'), '{}');
  assert.equal((await backend.inspect(apk, settings)).lazy, true);
});

test('cancelling a pending lazy request also cancels queued work without leaving a child', processTest, async t => {
  const data = await fixture(t, { delay: 1000 }); const { backend, apk, settings } = data;
  await backend.inspect(apk, settings);
  const first = backend.inspectClass(apk, settings, 'org.example.Main'); const firstRejected = assert.rejects(first, /cancelled/);
  const second = backend.inspectVariables(apk, settings, '<org.example.Main: void run()>'); const secondRejected = assert.rejects(second, /cancelled/);
  await waitUntil(() => backend.codeBrowser?.session.inFlight > 0); backend.cancel();
  await Promise.all([firstRejected, secondRejected]); await backend.closeCodeBrowser();
  assert.equal(backend.codeBrowser, null);
});

test('a crashed or malformed worker fails pending work and can be reopened', processTest, async t => {
  const data = await fixture(t); const { backend, apk, settings } = data;
  await backend.inspect(apk, settings);
  await assert.rejects(backend.browse(apk, settings, false, session => session.request('crash', {})), /stopped/);
  assert.equal(backend.codeBrowser, null);
  await backend.inspect(apk, settings);
  await assert.rejects(backend.browse(apk, settings, false, session => session.request('malformed', {})), /invalid response/);
  assert.equal((await backend.inspect(apk, settings)).lazy, true);
});

test('an unavailable method does not close a healthy session or prevent another class from loading', processTest, async t => {
  const data = await fixture(t); const { backend, apk, settings } = data;
  const initial = await backend.inspect(apk, settings);
  await assert.rejects(backend.browse(apk, settings, false, session => session.request('error', {})), /Unknown method/);
  assert.equal((await backend.inspect(apk, settings)).sessionId, initial.sessionId);
  assert.equal((await backend.inspectClass(apk, settings, 'org.example.Main')).className, 'org.example.Main');
  assert.equal((await data.journal()).split('\n').filter(value => value === 'start').length, 1);
});

test('a stale APK error discards the session even when the original APK hash has been restored', processTest, async t => {
  const data = await fixture(t); const { backend, apk, settings } = data;
  const initial = await backend.inspect(apk, settings);
  // The server has observed an intermediate modification; by the next request,
  // the file again has the original bytes, so the backend cache key alone matches.
  await assert.rejects(backend.browse(apk, settings, false, session => session.request('stale', {})), /APK changed while browsing/);
  assert.equal(backend.codeBrowser, null);
  const reopened = await backend.inspect(apk, settings);
  assert.equal(reopened.apkSha256, initial.apkSha256); assert.notEqual(reopened.sessionId, initial.sessionId);
  assert.equal((await backend.inspectClass(apk, settings, 'org.example.Main')).className, 'org.example.Main');
  assert.equal((await data.journal()).split('\n').filter(value => value === 'start').length, 2);
});

test('startup and request timeouts close the worker instead of hanging the GUI', processTest, async t => {
  const data = await fixture(t, { hangStartup: true });
  let session = new CodeBrowserSession({ java: data.java, jar: data.jar, apk: data.apk, platforms: data.platforms, directory: data.dir, timeoutMs: 100 });
  await assert.rejects(session.ready, /timed out/); await session.close();
  await fs.writeFile(path.join(data.dir, 'mode.json'), '{}');
  session = new CodeBrowserSession({ java: data.java, jar: data.jar, apk: data.apk, platforms: data.platforms, directory: data.dir, timeoutMs: 500 });
  t.after(() => session.close()); await session.ready;
  await assert.rejects(session.request('hang', {}), /timed out/); assert.equal(session.closed, true);
});
