'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const mainPath = path.join(__dirname, '../src/main.js');
const realRequire = createRequire(mainPath);

async function mainFixture(t) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androlog-main-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const handlers = new Map(), files = [], saves = [], calls = [];
  let ready, window;
  class Backend {
    async discard() {}
    async inspect(apk) { calls.push({ inspect: apk }); return { methods: [] }; }
    async instrument(apk, settings, options, plan) {
      calls.push({ instrument: apk, options, plan });
      const output = path.join(dir, 'working.apk'); await fs.writeFile(output, 'instrumented');
      return { dir, input: apk, output };
    }
    async analyze(apk, log, settings, options) {
      calls.push({ apk, log, options });
      return { dir, html: path.join(dir, 'report.html'), report: { instrumentedInput: true, coverageSummary: {}, custom: { summary: { points: [] } } } };
    }
  }
  const electron = {
    app: { isPackaged: false, whenReady: () => ({ then: fn => { ready = fn(); } }), getPath: () => dir, setAboutPanelOptions() {}, on() {} },
    protocol: { registerSchemesAsPrivileged() {}, handle() {} },
    ipcMain: { handle: (name, fn) => handlers.set(name, fn) },
    dialog: {
      showOpenDialog: async () => ({ canceled: false, filePaths: [files.shift()] }),
      showSaveDialog: async () => ({ canceled: false, filePath: saves.shift() }),
      showMessageBox: async () => ({ response: 1 })
    },
    BrowserWindow: class {
      constructor() { window = this; this.webContents = { mainFrame: {}, setWindowOpenHandler() {}, on() {} }; }
      on() {} once() {} async loadURL() {}
    }
  };
  const source = await fs.readFile(mainPath, 'utf8');
  const context = { __dirname: path.dirname(mainPath), process: { ...process, env: {} }, URL, require(name) {
    if (name === 'electron') return electron;
    if (name === './backend') return { ...realRequire(name), Backend };
    return realRequire(name);
  } };
  vm.runInNewContext(source, context, { filename: mainPath }); await ready;
  return { dir, files, saves, calls, invoke: (name, ...args) => handlers.get(name)({ sender: window.webContents, senderFrame: window.webContents.mainFrame }, ...args) };
}

test('main process keeps the two APK inputs separate and needs no local custom catalog', async t => {
  const { dir, files, invoke, calls } = await mainFixture(t);
  const original = path.join(dir, 'original.apk'), tested = path.join(dir, 'instrumented.apk'), log = path.join(dir, 'runtime.log');
  files.push(original); await invoke('select:apk');
  files.push(log); await invoke('select:log');
  await assert.rejects(invoke('coverage:analyze', { tag: 'REMOTE' }), /instrumented APK/);
  files.push(tested); await invoke('select:coverage-apk');
  const report = await invoke('coverage:analyze', { tag: 'REMOTE' });
  assert.equal(report.instrumentedInput, true);
  assert.deepEqual(calls[0], { apk: tested, log, options: { tag: 'REMOTE' } });
  await invoke('code:inspect'); assert.equal(calls[1].inspect, original);
  await assert.rejects(fs.stat(path.join(dir, 'custom-plans')), { code: 'ENOENT' });
});

test('saving an instrumented APK selects that file for coverage and preserves the original', async t => {
  const { dir, files, saves, invoke, calls } = await mainFixture(t);
  const original = path.join(dir, 'original.apk'), saved = path.join(dir, 'saved.apk'), log = path.join(dir, 'runtime.log');
  await fs.writeFile(original, 'original'); await fs.writeFile(log, '');
  files.push(original); await invoke('select:apk'); files.push(log); await invoke('select:log');
  await invoke('apk:instrument', { tag: 'ANDROLOG' }, null); saves.push(saved);
  assert.equal(await invoke('apk:save'), saved);
  await invoke('coverage:analyze', { tag: 'ANDROLOG' });
  assert.equal(calls.at(-1).apk, saved);
  assert.equal(await fs.readFile(original, 'utf8'), 'original');
  assert.equal(await fs.readFile(saved, 'utf8'), 'instrumented');
});
