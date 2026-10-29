'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { Backend } = require('../src/backend');

const processTest = { skip: process.platform === 'win32', timeout: 8000 };
async function fixture(t, script) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androlog-device-backend-'));
  const jar = path.join(dir, 'androlog.jar'), java = path.join(dir, 'fake-java');
  const platforms = path.join(dir, 'platforms'), apk = path.join(dir, 'source.apk'), root = path.join(dir, 'saved-captures');
  await fs.mkdir(path.join(platforms, 'android-test'), { recursive: true });
  await fs.writeFile(path.join(platforms, 'android-test/android.jar'), 'platform');
  await fs.writeFile(jar, 'backend'); await fs.writeFile(apk, 'original instrumented bytes');
  await fs.writeFile(java, `#!${process.execPath}\n'use strict';\nconst fs = require('node:fs');\nconst args = process.argv.slice(2);\nconst arg = name => args[args.indexOf(name) + 1];\n${script}\n`);
  await fs.chmod(java, 0o755);
  const events = [], waiters = [];
  const backend = new Backend({ jar, tempRoot: dir, emit: event => { events.push(event); for (const waiter of waiters) if (waiter.state === event.state) waiter.resolve(event); } });
  t.after(async () => { await backend.shutdown(); await fs.rm(dir, { recursive: true, force: true }); });
  return { dir, apk, root, backend, settings: { platforms, java }, events, until(state) {
    const event = events.find(event => event.type === 'capture' && event.state === state);
    return event ? Promise.resolve(event) : new Promise(resolve => waiters.push({ state, resolve }));
  } };
}
const recording = `
const log = arg('--output');
fs.writeFileSync(log, 'I/TEST: METHOD=observed\\n');
console.log('@@ANDROLOG_CAPTURE ' + JSON.stringify({state:'recording'}));
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  if (!chunk.includes('\\n')) return;
  fs.appendFileSync(log, 'I/TEST: METHOD=last-record-before-stop\\n');
  console.log('@@ANDROLOG_CAPTURE ' + JSON.stringify({state:'stopping'}));
  process.exit(0);
});
process.stdin.resume();`;

test('device listing preserves ready, unauthorized, offline and no-permissions states', processTest, async t => {
  const f = await fixture(t, `console.log('daemon diagnostic'); console.log('@@ANDROLOG_DEVICES ' + JSON.stringify([{serial:'emulator-5554',name:'Pixel',state:'device'},{serial:'192.168.1.2:5555',name:'Phone',state:'unauthorized'},{serial:'offline-phone',name:'Offline',state:'offline'},{serial:'????????????',name:'USB device',state:'no permissions'}]));`);
  const devices = await f.backend.listDevices(f.settings);
  assert.deepEqual(devices.map(d => d.state), ['device', 'unauthorized', 'offline', 'no permissions']);
  assert.equal(devices[1].serial, '192.168.1.2:5555');
});

test('capture snapshots the installed APK, drains on stop and survives backend shutdown', processTest, async t => {
  const f = await fixture(t, recording);
  const capturing = f.backend.capture(f.apk, f.settings, { device: 'emulator-5554', tag: 'TEST' }, f.root);
  const update = await f.until('recording');
  assert.ok(update.apk.startsWith(f.root));
  assert.equal(f.backend.workspaces.size, 0);
  await fs.writeFile(f.apk, 'a later instrumentation');
  await f.backend.stopCapture();
  await f.backend.stopCapture(); // Repeated clicks cannot send another stop or begin a different operation.
  const session = await capturing;
  assert.equal(session.tag, 'TEST');
  assert.equal(await fs.readFile(session.apk, 'utf8'), 'original instrumented bytes');
  assert.match(await fs.readFile(session.log, 'utf8'), /last-record-before-stop/);
  const metadata = JSON.parse(await fs.readFile(path.join(session.dir, 'session.json')));
  assert.equal(metadata.device, 'emulator-5554');
  await f.backend.shutdown();
  assert.equal(await fs.readFile(session.apk, 'utf8'), 'original instrumented bytes');
  assert.match(await fs.readFile(session.log, 'utf8'), /observed/);
});

test('cancelling preserves partial logs and allows another operation', processTest, async t => {
  const f = await fixture(t, recording);
  const capturing = f.backend.capture(f.apk, f.settings, { device: 'emulator-5554', tag: 'TEST' }, f.root);
  const rejected = assert.rejects(capturing, /Operation cancelled.*Saved session files:/);
  const update = await f.until('recording');
  f.backend.cancel(); await rejected;
  assert.equal(f.backend.active, null);
  assert.equal(await fs.readFile(update.log, 'utf8'), 'I/TEST: METHOD=observed\n');
  await assert.rejects(f.backend.stopCapture(), /No device capture/);
  const next = f.backend.capture(f.apk, f.settings, { device: 'emulator-5554', tag: 'TEST' }, f.root);
  // Each run gets a distinct folder, so wait for its own recording event.
  while (f.events.filter(event => event.type === 'capture' && event.state === 'recording').length < 2) await new Promise(resolve => setTimeout(resolve, 10));
  await f.backend.stopCapture(); assert.notEqual((await next).dir, update.dir);
});

test('capture failure keeps the exact APK and partial log with a useful error', processTest, async t => {
  const f = await fixture(t, `fs.writeFileSync(arg('--output'), 'partial log'); console.error('Error: Device disconnected'); process.exitCode = 1;`);
  await assert.rejects(f.backend.capture(f.apk, f.settings, { device: 'emulator-5554', tag: 'TEST' }, f.root), /Device disconnected.*Saved session files:/);
  const [saved] = await fs.readdir(f.root);
  assert.equal(await fs.readFile(path.join(f.root, saved, 'source.apk'), 'utf8'), 'original instrumented bytes');
  assert.equal(await fs.readFile(path.join(f.root, saved, 'runtime.log'), 'utf8'), 'partial log');
});

test('malformed device/capture responses fail cleanly instead of crashing the app', processTest, async t => {
  const f = await fixture(t, `if(args.includes('--list')) console.log('@@ANDROLOG_DEVICES not-json'); else { console.log('@@ANDROLOG_CAPTURE {"state":"unexpected"}'); setInterval(() => {}, 100); }`);
  await assert.rejects(f.backend.listDevices(f.settings), /invalid device list/);
  await assert.rejects(f.backend.capture(f.apk, f.settings, { device: 'emulator-5554', tag: 'TEST' }, f.root), /invalid capture status.*Saved session files:/);
  assert.equal(f.backend.active, null);
});

test('stop is restricted to recording and backend operations cannot overlap', processTest, async t => {
  const f = await fixture(t, `console.log('@@ANDROLOG_CAPTURE {"state":"installing"}'); setInterval(() => {}, 100);`);
  const capturing = f.backend.capture(f.apk, f.settings, { device: 'emulator-5554', tag: 'TEST' }, f.root);
  const rejected = assert.rejects(capturing, /cancelled/);
  await f.until('installing');
  await assert.rejects(f.backend.stopCapture(), /Wait until the app is recording/);
  await assert.rejects(f.backend.listDevices(f.settings), /Another operation/);
  f.backend.cancel(); await rejected;
});

test('invalid devices and tags are rejected before a session is created', processTest, async t => {
  const f = await fixture(t, 'process.exit(99);');
  await assert.rejects(f.backend.capture(f.apk, f.settings, { device: 'phone; touch bad', tag: 'TEST' }, f.root), /connected device/);
  await assert.rejects(f.backend.capture(f.apk, f.settings, { device: 'phone', tag: 'bad tag' }, f.root), /Logcat tag/);
  await assert.rejects(fs.stat(f.root), { code: 'ENOENT' });
});


test('an unexpectedly successful capture exit does not analyze an incomplete run', processTest, async t => {
  const f = await fixture(t, `fs.writeFileSync(arg('--output'), 'partial log'); console.log('@@ANDROLOG_CAPTURE {"state":"recording"}');`);
  await assert.rejects(f.backend.capture(f.apk, f.settings, { device: 'emulator-5554', tag: 'TEST' }, f.root), /ended before Stop & analyze.*Saved session files:/);
});

test('bounded backend commands time out and release their operation lock', processTest, async t => {
  const f = await fixture(t, 'setInterval(() => {}, 100);');
  await assert.rejects(f.backend.run(f.settings, [], { label: 'Finding devices', timeoutMs: 50 }), /Finding devices timed out/);
  assert.equal(f.backend.active, null);
});


for (const action of ['cancel', 'shutdown']) {
  test(`${action} during APK preparation prevents Java from starting and permits a new test`, processTest, async t => {
    const f = await fixture(t, recording);
    let copied, release;
    const reachedCopy = new Promise(resolve => { copied = resolve; });
    const continueCopy = new Promise(resolve => { release = resolve; });
    const realCopy = fs.copyFile;
    const copyMock = t.mock.method(fs, 'copyFile', async (...args) => {
      await realCopy(...args);
      if (args[0] === f.apk) { copied(args[1]); await continueCopy; }
    });
    const capturing = f.backend.capture(f.apk, f.settings, { device: 'emulator-5554', tag: 'TEST' }, f.root);
    const rejected = assert.rejects(capturing, /Operation cancelled.*Saved session files:/);
    const snapshot = await reachedCopy;
    assert.ok(f.backend.active);
    assert.equal(f.backend.child, null);
    await f.backend[action]();
    release(); await rejected;
    copyMock.mock.restore();
    assert.equal(f.backend.active, null);
    assert.equal(f.events.some(event => event.type === 'start'), false);
    assert.equal(await fs.readFile(snapshot, 'utf8'), 'original instrumented bytes');
    await assert.rejects(fs.stat(path.join(path.dirname(snapshot), 'runtime.log')), { code: 'ENOENT' });
    const next = f.backend.capture(f.apk, f.settings, { device: 'emulator-5554', tag: 'TEST' }, f.root);
    await f.until('recording'); await f.backend.stopCapture();
    assert.notEqual((await next).dir, path.dirname(snapshot));
  });
}

test('cancel immediately after capture is requested is retained across validation awaits', processTest, async t => {
  const f = await fixture(t, recording);
  const capturing = f.backend.capture(f.apk, f.settings, { device: 'emulator-5554', tag: 'TEST' }, f.root);
  f.backend.cancel();
  await assert.rejects(capturing, /Operation cancelled/);
  assert.equal(f.backend.active, null);
  assert.equal(f.events.some(event => event.type === 'start'), false);
  await assert.rejects(fs.stat(f.root), { code: 'ENOENT' });
});
