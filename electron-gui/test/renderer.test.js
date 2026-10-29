'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const html = fs.readFileSync(path.join(__dirname, '../src/index.html'), 'utf8');
const script = fs.readFileSync(path.join(__dirname, '../src/renderer.js'), 'utf8');
const stylesheet = fs.readFileSync(path.join(__dirname, '../src/style.css'), 'utf8');
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
function createUi(overrides = {}) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://androlog.invalid/' });
  const { window } = dom; window.TextEncoder = TextEncoder;
  window.HTMLElement.prototype.scrollTo = function () {};
  window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  window.HTMLDialogElement.prototype.close = function () { this.open = false; };
  const calls = [];
  const api = {
    settings: async () => ({ platforms: '/sdk/platforms', buildTools: '/sdk/build-tools/36', andrologVersion: '0.1', appVersion: '0.1.3' }),
    chooseApk: async () => '/apps/original.apk', chooseCoverageApk: async () => '/apps/instrumented.apk', chooseLog: async () => '/logs/runtime.log',
    chooseSetting: async () => null, saveSettings: async value => value, cancel: async () => {}, onUpdate: () => {},
    listDevices: async () => [{ serial: 'emulator-5554', name: 'Pixel', state: 'device' }], stopDeviceTest: async () => {},
    inspectCode: async () => ({ apkSha256: 'a'.repeat(64), methods: [{ className: 'org.example.Main', signature: '<org.example.Main: void run()>', statements: [{ index: 1, text: 'return', selectable: true }] }] }),
    instrument: async (opts, plan) => { calls.push({ opts, plan }); return { name: 'original.apk' }; },
    saveApk: async () => '/apps/saved.apk', analyze: async () => ({ coverageSummary: { methods: { covered: 1, total: 4 }, services: { covered: 0, total: 0 } } }), saveReport: async () => '/tmp/report.html', ...overrides
  };
  const style = window.document.createElement('style'); style.textContent = stylesheet; window.document.head.appendChild(style);
  window.androlog = api; window.eval(script);
  return { dom, window, doc: window.document, calls };
}
test('workspace begins without fabricated coverage and keeps controls disabled', async t => {
  const { dom, doc } = createUi(); t.after(() => dom.window.close()); await tick();
  assert.equal(doc.getElementById('app-version').textContent, 'Version 0.1');
  assert.equal(doc.title, 'AndroLog · 0.1');
  assert.equal(doc.getElementById('instrument').disabled, true);
  assert.equal(doc.getElementById('analyze').disabled, true);
  assert.equal(doc.querySelectorAll('.stat').length, 7);
  assert.match(doc.querySelector('.stat').textContent, /Awaiting analysis/);
  doc.querySelector('[data-page="coverage"]').click(); assert.equal(doc.getElementById('page-coverage').hidden, false);
  assert.equal(doc.getElementById('tab-coverage').getAttribute('aria-selected'), 'true');
  doc.getElementById('tab-coverage').focus();
  doc.getElementById('tab-coverage').dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
  assert.equal(doc.getElementById('tab-explore').getAttribute('aria-selected'), 'true');
});
test('a custom message is bound to an exact selected statement and sent to the Java job', async t => {
  const { dom, window, doc, calls } = createUi(); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick();
  assert.equal(doc.getElementById('instrument').disabled, false);
  doc.getElementById('open-custom').click(); await tick();
  assert.equal(doc.getElementById('code-dialog').open, true);
  doc.querySelector('.code-line').click();
  const input = doc.getElementById('point-message'); input.value = 'reached this point'; input.dispatchEvent(new window.Event('input', { bubbles: true }));
  doc.getElementById('code-apply').click(); assert.match(doc.getElementById('custom-count').textContent, /1 log point/);
  doc.getElementById('instrument').click(); await tick();
  assert.equal(calls.length, 1); assert.equal(calls[0].plan.points[0].method, '<org.example.Main: void run()>');
  assert.equal(calls[0].plan.points[0].index, 1); assert.equal(calls[0].plan.points[0].message, 'reached this point');
  assert.equal(doc.getElementById('save-apk').hidden, false);
});
test('coverage requires an instrumented APK and log, independently of the original APK', async t => {
  const { dom, window, doc } = createUi(); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick();
  doc.getElementById('choose-log').click(); await tick();
  assert.equal(doc.getElementById('analyze').disabled, true);
  assert.equal(doc.getElementById('coverage-apk').textContent, 'No APK selected');
  doc.getElementById('coverage-choose-apk').click(); await tick();
  assert.equal(doc.getElementById('analyze').disabled, false);
  assert.equal(doc.getElementById('apk-name').textContent, 'original.apk');
  assert.equal(doc.getElementById('messages-panel').hidden, true);
  doc.getElementById('analyze').click(); await tick();
  const cards = [...doc.querySelectorAll('.stat')];
  assert.match(cards[1].textContent, /25\.0%/); assert.match(cards[1].textContent, /1 of 4 observed/);
  assert.match(cards[4].textContent, /Nothing to cover/);
  assert.match(cards[0].textContent, /No probes found/);
  assert.equal(doc.getElementById('report-frame').hidden, false);
  doc.querySelector('[data-probe="1"]').click();
  assert.equal(doc.getElementById('report-frame').hidden, false, 'Instrumentation settings do not change the analyzed APK');
  doc.getElementById('coverage-tag').value = 'OTHER'; doc.getElementById('coverage-tag').dispatchEvent(new window.Event('input'));
  assert.equal(doc.getElementById('report-frame').hidden, true);
});

test('the class and statement panes remain independently scrollable in the dialog', async t => {
  const { dom, window, doc } = createUi(); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick();
  doc.getElementById('open-custom').click(); await tick();
  const codePane = window.getComputedStyle(doc.getElementById('code-lines'));
  const classPane = window.getComputedStyle(doc.getElementById('classes'));
  assert.equal(codePane.overflow, 'auto'); assert.equal(codePane.minHeight, '0');
  assert.equal(classPane.overflow, 'auto'); assert.equal(classPane.minHeight, '0');
  assert.equal(window.getComputedStyle(doc.querySelector('.code-main')).minHeight, '0');
});

test('instrumentation and coverage tags can describe different APKs', async t => {
  const { dom, window, doc } = createUi(); t.after(() => dom.window.close()); await tick();
  const instrumentTag = doc.getElementById('log-tag'), coverageTag = doc.getElementById('coverage-tag');
  assert.equal(instrumentTag.closest('details'), null); assert.equal(coverageTag.closest('details'), null);
  instrumentTag.value = 'NEW_APP'; instrumentTag.dispatchEvent(new window.Event('input', { bubbles: true }));
  assert.equal(coverageTag.value, 'ANDROLOG');
  coverageTag.value = 'OLD_APP'; coverageTag.dispatchEvent(new window.Event('input', { bubbles: true }));
  assert.equal(instrumentTag.value, 'NEW_APP');
});

test('instrumentation stages and completion drive the package illustration', async t => {
  let update, finish;
  const { dom, window, doc } = createUi({
    onUpdate: handler => { update = handler; },
    instrument: () => new Promise(resolve => { finish = resolve; })
  });
  t.after(() => dom.window.close());
  const stages = []; window.andrologAnimation = { setStage: (...args) => stages.push(args) };
  doc.getElementById('choose-apk').click(); await tick(); stages.length = 0;
  doc.getElementById('instrument').click(); await tick();
  update({ type: 'stage', stage: 2 });
  assert.ok(stages.some(([stage]) => stage === 2));
  finish({ name: 'original.apk' }); await tick();
  assert.equal(stages.at(-1)[0], 5);
});

test('navigation lives in the left pane and remains keyboard accessible', t => {
  const { dom, doc } = createUi(); t.after(() => dom.window.close());
  const menu = doc.querySelector('.sidebar [role="tablist"]'); assert.ok(menu);
  assert.equal(menu.querySelectorAll('[role="tab"]').length, 3);
  assert.equal(doc.querySelector('.topline [role="tablist"]'), null);
  doc.getElementById('tab-instrument').focus();
  doc.getElementById('tab-instrument').dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
  assert.equal(doc.getElementById('tab-coverage').getAttribute('aria-selected'), 'true');
});
test('saved APK instructions use the saved path and the original instrumentation tag', async t => {
  const copied = [];
  const { dom, window, doc } = createUi({ platform: 'darwin', saveApk: async () => "/tmp/Jordan's app/app.apk", copyText: async text => copied.push(text) }); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick();
  const tag = doc.getElementById('log-tag'); tag.value = 'MY_TAG'; tag.dispatchEvent(new window.Event('input', { bubbles: true }));
  doc.getElementById('instrument').click(); await tick();
  assert.equal(doc.getElementById('next-steps').hidden, true);
  tag.value = 'DIFFERENT'; tag.dispatchEvent(new window.Event('input', { bubbles: true }));
  let revealed = false; doc.getElementById('next-steps').scrollIntoView = () => { revealed = true; };
  doc.getElementById('save-apk').click(); await tick();
  assert.equal(doc.getElementById('next-steps').hidden, false);
  assert.equal(revealed, true);
  assert.equal(doc.getElementById('coverage-apk').textContent, 'app.apk');
  assert.equal(doc.getElementById('coverage-tag').value, 'MY_TAG');
  assert.equal(doc.getElementById('apk-name').textContent, 'original.apk');
  assert.equal(doc.getElementById('install-command').textContent, "adb install -r '/tmp/Jordan'\\''s app/app.apk'");
  assert.equal(doc.getElementById('logcat-command').textContent, "adb logcat -v threadtime -s 'MY_TAG:V' > androlog-runtime.log");
  for (const button of doc.querySelectorAll('[data-copy-command]')) { button.click(); await tick(); assert.equal(button.disabled, false); }
  assert.deepEqual(copied, [doc.getElementById('install-command').textContent, doc.getElementById('logcat-command').textContent]);
  assert.match(doc.getElementById('toast').textContent, /Command copied/);
});
test('Windows instructions quote the APK for PowerShell and save UTF-8 logcat bytes', async t => {
  const copied = [];
  const { dom, doc } = createUi({ platform: 'win32', saveApk: async () => "C:\\Users\\O'Brien\\app.apk", copyText: async text => copied.push(text) }); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick(); doc.getElementById('instrument').click(); await tick();
  doc.getElementById('save-apk').click(); await tick();
  assert.match(doc.getElementById('terminal-instructions').textContent, /PowerShell/);
  assert.equal(doc.getElementById('install-command').textContent, "adb install -r 'C:\\Users\\O''Brien\\app.apk'");
  assert.equal(doc.getElementById('logcat-command').textContent, 'cmd /c "adb logcat -v threadtime -s ANDROLOG:V > androlog-runtime.log"');
  for (const button of doc.querySelectorAll('[data-copy-command]')) { button.click(); await tick(); }
  assert.deepEqual(copied, [doc.getElementById('install-command').textContent, doc.getElementById('logcat-command').textContent]);
});

test('custom-only APK instructions do not claim coverage probes were enabled', async t => {
  const { dom, window, doc } = createUi(); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick();
  doc.getElementById('open-custom').click(); await tick(); doc.querySelector('.code-line').click();
  const field = doc.getElementById('point-message'); field.value = 'here'; field.dispatchEvent(new window.Event('input', { bubbles: true }));
  doc.getElementById('code-apply').click(); doc.querySelectorAll('[data-probe]').forEach(input => { input.checked = false; });
  doc.getElementById('instrument').click(); await tick(); doc.getElementById('save-apk').click(); await tick();
  assert.match(doc.getElementById('next-coverage').textContent, /custom points/);
  assert.match(doc.getElementById('next-coverage').textContent, /Analyze the run/);
});

test('custom code explorer groups methods under classes and searches method names', async t => {
  const { dom, window, doc } = createUi({ inspectCode: async () => ({ apkSha256: 'a'.repeat(64), methods: [
    { className: 'org.example.Main', signature: '<org.example.Main: void first()>', statements: [{ index: 1, text: 'return', selectable: true }] },
    { className: 'org.example.Main', signature: '<org.example.Main: void second()>', statements: [{ index: 1, text: 'return', selectable: true }] },
    { className: 'org.other.Other', signature: '<org.other.Other: void third()>', statements: [] }
  ] }) }); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick(); doc.getElementById('open-custom').click(); await tick();
  assert.equal(doc.querySelectorAll('#classes > .tree-package').length, 1);
  assert.equal(doc.querySelector('#classes > .tree-package > summary').textContent, 'org');
  assert.equal(doc.querySelectorAll('[data-package="org"] > .tree-children > .tree-package').length, 2);
  assert.equal(doc.querySelector('[data-package="org.example"] > summary').textContent, 'example');
  assert.equal(doc.querySelectorAll('.tree-class')[0].querySelectorAll('.tree-method').length, 2);
  doc.querySelectorAll('.tree-method')[1].click();
  assert.match(doc.getElementById('code-class').textContent, /Main/);
  const search = doc.getElementById('class-search'); search.value = 'third'; search.dispatchEvent(new window.Event('input'));
  assert.equal(doc.querySelectorAll('.tree-method').length, 1); assert.match(doc.querySelector('.tree-method').textContent, /third/);
});

function customFixture() {
  return { observed: 1, points: [
    { id: 'first', message: 'same message', method: '<A: void run()>', index: 1, statement: 'return', count: 2, status: 'covered' },
    { id: 'second', message: 'same message', method: '<A: void run(int)>', index: 4, statement: 'return', count: 0, status: 'uncovered' }
  ] };
}
async function selectRun(doc) {
  doc.getElementById('coverage-choose-apk').click(); await tick();
  doc.getElementById('choose-log').click(); await tick();
}
test('portable custom coverage comes from the APK report without any local point catalog', async t => {
  let analysisArguments;
  const summary = customFixture();
  const { dom, doc } = createUi({ analyze: async (...args) => { analysisArguments = args; return { coverageSummary: {}, custom: { total: 2, summary } }; } });
  t.after(() => dom.window.close());
  await selectRun(doc);
  assert.equal(doc.getElementById('messages-panel').hidden, true);
  assert.equal(doc.getElementById('custom-build-picker'), null);
  doc.getElementById('analyze').click(); await tick();
  assert.match(doc.querySelector('.point-row.observed').textContent, /Covered/);
  assert.match(doc.querySelector('.point-row.unobserved').textContent, /Not covered/);
  assert.deepEqual(JSON.parse(JSON.stringify(analysisArguments)), [{ tag: 'ANDROLOG' }]);
  assert.match(doc.getElementById('coverage-meta').textContent, /instrumented\.apk/);
  assert.equal(doc.getElementById('instrument').disabled, true, 'No original APK is needed for coverage');
});

test('analysis progress appears while working and follows the reported stages', async t => {
  let update, finish;
  const { dom, window, doc } = createUi({ onUpdate: handler => { update = handler; }, analyze: () => new Promise(resolve => { finish = resolve; }) });
  t.after(() => dom.window.close()); const calls = []; window.andrologAnimation = { setAnalysis: (...args) => calls.push(args) };
  await selectRun(doc); doc.getElementById('analyze').click(); await tick();
  assert.equal(doc.getElementById('analysis-progress').hidden, false);
  update({ type: 'stage', stage: 3 }); assert.match(doc.getElementById('analysis-title').textContent, /Matching/);
  assert.ok(calls.some(([stage, active]) => stage === 3 && active));
  finish({ coverageSummary: {} }); await tick();
  assert.equal(doc.getElementById('analysis-progress').hidden, true); assert.deepEqual(calls.at(-1), [0, false]);
});

test('an empty run can show every custom point as unobserved using only the APK', async t => {
  const summary = customFixture(); summary.observed = 0;
  for (const point of summary.points) { point.count = 0; point.status = 'uncovered'; }
  const { dom, doc } = createUi({ analyze: async () => ({ coverageSummary: {}, custom: { total: 0, messages: [], summary } }) });
  t.after(() => dom.window.close());
  doc.getElementById('choose-log').click(); await tick(); doc.getElementById('coverage-choose-apk').click(); await tick();
  assert.equal(doc.querySelectorAll('.point-row').length, 0);
  doc.getElementById('analyze').click(); await tick();
  assert.equal(doc.querySelectorAll('.point-row.unobserved').length, 2);
});

test('changing the original APK preserves coverage; changing the tested APK clears it', async t => {
  const { dom, doc } = createUi({ analyze: async () => ({ coverageSummary: {}, custom: { summary: customFixture() } }) });
  t.after(() => dom.window.close()); await selectRun(doc);
  doc.getElementById('analyze').click(); await tick();
  doc.getElementById('choose-apk').click(); await tick();
  assert.equal(doc.getElementById('report-frame').hidden, false);
  assert.equal(doc.querySelectorAll('.point-row').length, 2);
  doc.getElementById('coverage-choose-apk').click(); await tick();
  assert.equal(doc.getElementById('report-frame').hidden, true);
  assert.equal(doc.querySelectorAll('.point-row').length, 0);
});

test('legacy ambiguous custom observations are unconfirmed rather than falsely red', async t => {
  const summary = customFixture(); summary.points[1].count = null; summary.points[1].status = 'unknown';
  const { dom, doc } = createUi({ analyze: async () => ({ coverageSummary: {}, custom: { summary, notice: 'Some runtime markers are not present in this APK.' } }) });
  t.after(() => dom.window.close()); await selectRun(doc); doc.getElementById('analyze').click(); await tick();
  assert.equal(doc.querySelectorAll('.point-row.unobserved').length, 0);
  assert.match(doc.querySelector('.point-row.unknown').textContent, /Unconfirmed/);
  assert.match(doc.getElementById('messages-summary').textContent, /Some runtime markers/);
});

test('custom points jump to their exact report locations without analyzing again', async t => {
  let analyses = 0;
  const { dom, window, doc } = createUi({ analyze: async () => { analyses++; return { coverageSummary: {}, custom: { summary: customFixture() } }; } });
  t.after(() => dom.window.close()); await selectRun(doc); doc.getElementById('analyze').click(); await tick();
  doc.querySelectorAll('.custom-point-link')[1].click();
  assert.equal(analyses, 1); assert.equal(doc.getElementById('page-explore').hidden, false);
  const received = []; const frame = doc.getElementById('report-frame'); frame.contentWindow.postMessage = value => received.push(value);
  frame.dispatchEvent(new window.Event('load'));
  assert.equal(received[0].type, 'androlog.selectCustomPoint'); assert.equal(received[0].pointId, 'second');
  doc.querySelectorAll('.custom-point-link')[0].click();
  assert.equal(analyses, 1); assert.equal(received.at(-1).pointId, 'first');
});
test('failed reanalysis removes stale custom coverage and reports the backend error', async t => {
  let analyses = 0;
  const { dom, doc } = createUi({ analyze: async () => { if (analyses++) throw Error('APK and log do not match'); return { coverageSummary: {}, custom: { summary: customFixture() } }; } });
  t.after(() => dom.window.close()); await selectRun(doc); doc.getElementById('analyze').click(); await tick();
  assert.equal(doc.querySelectorAll('.point-row').length, 2);
  doc.getElementById('analyze').click(); await tick();
  assert.equal(doc.getElementById('report-frame').hidden, true); assert.equal(doc.querySelectorAll('.point-row').length, 0);
  assert.match(doc.getElementById('toast').textContent, /APK and log do not match/);
});

async function instrumentForDevice(doc) {
  doc.getElementById('choose-apk').click(); await tick();
  doc.getElementById('instrument').click(); await tick();
  doc.getElementById('test-device').click(); await tick();
}
test('device testing is offered after instrumentation and only ready devices can start', async t => {
  const { dom, doc } = createUi({ listDevices: async () => [
    { serial: 'phone', name: 'My phone', state: 'unauthorized' },
    { serial: 'offline-emulator', name: 'Tablet', state: 'offline' },
    { serial: 'emulator-5554', name: 'Pixel', state: 'device' }
  ] });
  t.after(() => dom.window.close());
  assert.equal(doc.getElementById('test-device').hidden, true);
  await instrumentForDevice(doc);
  assert.equal(doc.getElementById('test-device').hidden, false);
  assert.equal(doc.getElementById('device-dialog').open, true);
  const select = doc.getElementById('device-select');
  assert.equal(select.value, 'emulator-5554');
  assert.equal(select.options[0].disabled, true); assert.match(select.options[0].textContent, /authorization needed/);
  assert.equal(select.options[1].disabled, true);
  assert.equal(doc.getElementById('device-start').disabled, false);
  doc.getElementById('device-cancel').click();
  assert.equal(doc.getElementById('device-dialog').open, false);
  doc.getElementById('choose-apk').click(); await tick();
  assert.equal(doc.getElementById('test-device').hidden, true);
});
test('empty and failed device scans give recoverable instructions', async t => {
  let scan = 0;
  const { dom, doc } = createUi({ listDevices: async () => {
    if (scan++ === 0) return [];
    if (scan === 2) throw new Error('Cannot find adb. Install Android SDK Platform Tools.');
    return [{ serial: 'phone', name: 'Phone', state: 'device' }];
  } });
  t.after(() => dom.window.close()); await instrumentForDevice(doc);
  assert.equal(doc.getElementById('device-start').disabled, true);
  assert.match(doc.getElementById('device-hint').textContent, /USB debugging/);
  doc.getElementById('device-refresh').click(); await tick();
  assert.match(doc.getElementById('device-error').textContent, /Cannot find adb/);
  assert.equal(doc.getElementById('device-start').disabled, true);
  doc.getElementById('device-refresh').click(); await tick();
  assert.equal(doc.getElementById('device-error').hidden, true);
  assert.equal(doc.getElementById('device-select').value, 'phone');
  assert.equal(doc.getElementById('device-start').disabled, false);
});
test('Stop & analyze uses the captured APK, log and tag and unlocks the dashboard', async t => {
  let update, finish, selected, stops = 0, analyses = 0, analyzedTag;
  const { dom, window, doc } = createUi({
    onUpdate: callback => { update = callback; },
    startDeviceTest: value => { selected = value; return new Promise(resolve => { finish = resolve; }); },
    stopDeviceTest: async () => { stops++; finish({ apk: '/sessions/run/app.apk', log: '/sessions/run/runtime.log', tag: 'CAPTURED' }); },
    analyze: async opts => { analyses++; analyzedTag = opts.tag; return { coverageSummary: { methods: { covered: 2, total: 4 } } }; }
  });
  t.after(() => dom.window.close()); await instrumentForDevice(doc);
  let interval, clears = 0, now = 100000;
  window.Date.now = () => now;
  window.setInterval = callback => { interval = callback; return 45; };
  window.clearInterval = id => { if (id === 45) clears++; };
  doc.getElementById('device-start').click(); await tick();
  assert.equal(selected.device, 'emulator-5554');
  assert.equal(doc.getElementById('choose-apk').disabled, true);
  assert.equal(doc.getElementById('device-close').disabled, true);
  assert.equal(doc.getElementById('device-stop').disabled, true);
  const escape = new window.Event('cancel', { cancelable: true }); doc.getElementById('device-dialog').dispatchEvent(escape);
  assert.equal(escape.defaultPrevented, true);
  update({ type: 'capture', state: 'installing' }); assert.match(doc.getElementById('device-status').textContent, /Installing/);
  update({ type: 'capture', state: 'recording' });
  assert.equal(doc.getElementById('device-stop').disabled, false);
  now += 65000; interval(); assert.equal(doc.getElementById('device-elapsed').textContent, '01:05');
  assert.equal(analyses, 0);
  doc.getElementById('device-stop').click(); await tick(); await tick();
  assert.equal(stops, 1); assert.equal(analyses, 1); assert.equal(analyzedTag, 'CAPTURED');
  assert.equal(doc.getElementById('device-dialog').open, false);
  assert.equal(doc.getElementById('coverage-apk').title, '/sessions/run/app.apk');
  assert.equal(doc.getElementById('log-name').title, '/sessions/run/runtime.log');
  assert.match(doc.getElementById('operation-log').textContent, /APK: \/sessions\/run\/app.apk/);
  assert.match(doc.getElementById('operation-log').textContent, /Runtime log: \/sessions\/run\/runtime.log/);
  assert.equal(doc.getElementById('page-coverage').hidden, false);
  assert.match(doc.querySelectorAll('.stat')[1].textContent, /50.0%/);
  assert.equal(doc.getElementById('choose-apk').disabled, false);
  assert.ok(clears >= 1);
});
test('capture failure preserves its error and permits retry without analyzing a partial run', async t => {
  let update, fail, analyses = 0;
  const { dom, doc } = createUi({
    onUpdate: callback => { update = callback; },
    startDeviceTest: () => new Promise((_resolve, reject) => { fail = reject; }),
    analyze: async () => { analyses++; return { coverageSummary: {} }; }
  });
  t.after(() => dom.window.close()); await instrumentForDevice(doc);
  doc.getElementById('device-start').click(); await tick();
  update({ type: 'capture', state: 'recording' });
  fail(new Error('Device disconnected. Saved session: /sessions/partial')); await tick();
  assert.equal(analyses, 0);
  assert.equal(doc.getElementById('device-dialog').open, true);
  assert.match(doc.getElementById('device-error').textContent, /Saved session: \/sessions\/partial/);
  assert.equal(doc.getElementById('device-start').disabled, false);
  assert.equal(doc.getElementById('device-stop').hidden, true);
  assert.equal(doc.getElementById('device-close').disabled, false);
  assert.equal(doc.getElementById('choose-apk').disabled, false);
});
test('cancelling a test stops capture without opening analysis', async t => {
  let fail, cancelled = 0, analyses = 0;
  const { dom, doc } = createUi({
    startDeviceTest: () => new Promise((_resolve, reject) => { fail = reject; }),
    cancel: async () => { cancelled++; fail(new Error('Test cancelled. Saved session: /sessions/cancelled')); },
    analyze: async () => { analyses++; return { coverageSummary: {} }; }
  });
  t.after(() => dom.window.close()); await instrumentForDevice(doc);
  doc.getElementById('device-start').click(); await tick(); doc.getElementById('device-cancel').click(); await tick();
  assert.equal(cancelled, 1); assert.equal(analyses, 0);
  assert.equal(doc.getElementById('device-start').disabled, false);
  assert.match(doc.getElementById('device-error').textContent, /Test cancelled/);
});
test('a successful capture returned after a late cancel still synchronizes the selected run', async t => {
  let finish, cancelled = 0, analyses = 0, analyzedTag;
  const { dom, doc } = createUi({
    startDeviceTest: () => new Promise(resolve => { finish = resolve; }),
    cancel: async () => { cancelled++; },
    analyze: async opts => { analyses++; analyzedTag = opts.tag; return { coverageSummary: {} }; }
  });
  t.after(() => dom.window.close()); await selectRun(doc); await instrumentForDevice(doc);
  doc.getElementById('device-start').click(); await tick();
  doc.getElementById('device-cancel').click(); await tick();
  finish({ apk: '/sessions/finished/app.apk', log: '/sessions/finished/runtime.log', tag: 'FINISHED' }); await tick();
  assert.equal(cancelled, 1); assert.equal(analyses, 0);
  assert.equal(doc.getElementById('device-dialog').open, false);
  assert.equal(doc.getElementById('page-coverage').hidden, false);
  assert.equal(doc.getElementById('coverage-apk').title, '/sessions/finished/app.apk');
  assert.equal(doc.getElementById('log-name').title, '/sessions/finished/runtime.log');
  assert.equal(doc.getElementById('coverage-tag').value, 'FINISHED');
  assert.match(doc.getElementById('operation-log').textContent, /Runtime log: \/sessions\/finished\/runtime.log/);
  assert.equal(doc.getElementById('analyze').disabled, false);
  doc.getElementById('analyze').click(); await tick();
  assert.equal(analyses, 1); assert.equal(analyzedTag, 'FINISHED');
});

function variableCatalog() {
  return { apkSha256: 'a'.repeat(64), methods: [{ className: 'org.example.Main', signature: '<org.example.Main: void run()>', statements: [
    { index: 1, text: 'i0 = 3', selectable: true, locals: [] },
    { index: 2, text: 'i1 = i0 + 1', selectable: true, locals: [{ name: 'i0', type: 'int' }, { name: 'z0', type: 'boolean' }, { name: '$r1', type: 'java.lang.String' }, { name: 'object', type: 'java.lang.Object' }, { name: 'character', type: 'char' }] },
    { index: 3, text: 'return', selectable: true, locals: [{ name: 'i1', type: 'int' }] }
  ] }] };
}
async function openVariableEditor(doc) {
  doc.getElementById('choose-apk').click(); await tick(); doc.getElementById('open-custom').click(); await tick();
}
function enterMessage(window, doc, message) {
  const input = doc.getElementById('point-message'); input.value = message; input.dispatchEvent(new window.Event('input', { bubbles: true })); return input;
}
function insertVariable(doc, name) {
  doc.getElementById('insert-variable').click();
  [...doc.querySelectorAll('.variable-choice')].find(button => button.dataset.variable === name).click();
}
test('Insert variable offers only initialized supported locals at the selected point', async t => {
  const { dom, doc } = createUi({ inspectCode: async () => variableCatalog() }); t.after(() => dom.window.close());
  await openVariableEditor(doc);
  assert.equal(doc.getElementById('insert-variable').disabled, true);
  doc.querySelectorAll('.code-line')[0].click();
  assert.equal(doc.getElementById('insert-variable').disabled, true); assert.match(doc.getElementById('variable-hint').textContent, /No initialized/);
  doc.querySelectorAll('.code-line')[1].click(); doc.getElementById('insert-variable').click();
  assert.equal(doc.getElementById('variable-picker').hidden, false);
  assert.deepEqual([...doc.querySelectorAll('.variable-choice')].map(button => button.dataset.variable), ['i0', 'z0', '$r1', 'object']);
  assert.match(doc.querySelectorAll('.variable-choice')[1].textContent, /boolean/); assert.match(doc.querySelectorAll('.variable-choice')[2].textContent, /String/);
  assert.match(doc.querySelectorAll('.variable-choice')[3].textContent, /java\.lang\.Object · uses toString\(\)/);
  doc.querySelectorAll('.code-line')[2].click(); assert.equal(doc.getElementById('variable-picker').hidden, true);
  doc.getElementById('insert-variable').click();
  assert.deepEqual([...doc.querySelectorAll('.variable-choice')].map(button => button.dataset.variable), ['i1']);
});
test('object and array variables keep their exact types and expose toString behavior only after availability', async t => {
  const types = ['e.a.h', 'a', 'exemple.Élément', 'int[]', 'char[]', 'java.lang.String[]'];
  const locals = types.map((type, index) => ({ name: `$r${index}`, type }));
  const catalog = { apkSha256: 'a'.repeat(64), methods: [{ className: 'org.example.Main', signature: '<org.example.Main: void run()>', locals, statements: [
    { index: 1, text: 'specialinvoke $r0.<e.a.h: void <init>()>()', selectable: true, localMask: '' },
    { index: 2, text: 'return', selectable: true, localMask: Buffer.from([63]).toString('base64') }
  ] }] };
  const { dom, doc, calls } = createUi({ inspectCode: async () => catalog }); t.after(() => dom.window.close());
  await openVariableEditor(doc);
  doc.querySelectorAll('.code-line')[0].click();
  assert.equal(doc.getElementById('insert-variable').disabled, true);
  assert.equal(doc.getElementById('variable-hint').textContent, 'No initialized supported local is available here.');
  doc.querySelectorAll('.code-line')[1].click(); doc.getElementById('insert-variable').click();
  assert.deepEqual([...doc.querySelectorAll('.variable-choice small')].map(label => label.textContent), types.map(type => `${type} · uses toString()`));
  doc.querySelector('.variable-choice[data-variable="$r0"]').click();
  doc.getElementById('code-apply').click(); doc.getElementById('open-custom').click(); await tick();
  doc.querySelectorAll('.code-line')[1].click(); assert.equal(doc.getElementById('point-message').value, '{$r0}');
  insertVariable(doc, '$r3');
  doc.getElementById('code-apply').click(); doc.getElementById('instrument').click(); await tick();
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0].plan.points[0].variables)), [{ name: '$r0', type: 'e.a.h' }, { name: '$r3', type: 'int[]' }]);
});
test('invalid reference metadata is excluded from the variable picker', async t => {
  const catalog = variableCatalog();
  catalog.methods[0].statements[1].locals = ['char', 'void', 'void[]', 'null_type', '[]', 'pkg..Type', 'pkg/Type', 'pkg\nType', 'pkg\x7fType', 'pkg.<Type>', 'a' + '[]'.repeat(256), 'a'.repeat(1025)].map((type, index) => ({ name: `value${index}`, type }));
  const { dom, doc } = createUi({ inspectCode: async () => catalog }); t.after(() => dom.window.close());
  await openVariableEditor(doc); doc.querySelectorAll('.code-line')[1].click();
  assert.equal(doc.getElementById('insert-variable').disabled, true);
});
test('compact variable masks use the selected method dictionary across byte boundaries', async t => {
  const dictionary = Array.from({ length: 10 }, (_, i) => ({ name: `i${i}`, type: 'int' }));
  dictionary[7] = { name: 'enabled', type: 'boolean' }; dictionary[8] = { name: 'title', type: 'java.lang.String' };
  const catalog = { apkSha256: 'a'.repeat(64), methods: [
    { className: 'org.example.Main', signature: '<org.example.Main: void run()>', locals: dictionary, statements: [
      { index: 1, text: 'i0 = 3', selectable: true, localMask: '', locals: [{ name: 'stale', type: 'int' }] },
      { index: 2, text: 'return', selectable: true, localMask: Buffer.from([0x81, 0x01]).toString('base64') }
    ] },
    { className: 'org.example.Main', signature: '<org.example.Main: void second()>', locals: [{ name: 'other', type: 'long' }], statements: [
      { index: 1, text: 'return', selectable: true, localMask: 'AQ==' }
    ] }
  ] };
  const { dom, doc, calls } = createUi({ inspectCode: async () => catalog }); t.after(() => dom.window.close());
  await openVariableEditor(doc);
  doc.querySelectorAll('.code-line')[0].click(); assert.equal(doc.getElementById('insert-variable').disabled, true);
  doc.querySelectorAll('.code-line')[1].click(); doc.getElementById('insert-variable').click();
  assert.deepEqual([...doc.querySelectorAll('.variable-choice')].map(button => button.dataset.variable), ['i0', 'enabled', 'title']);
  doc.querySelector('.variable-choice[data-variable="title"]').click();
  doc.querySelectorAll('.code-line')[2].click(); doc.getElementById('insert-variable').click();
  assert.deepEqual([...doc.querySelectorAll('.variable-choice')].map(button => button.dataset.variable), ['other']);
  doc.querySelector('.variable-choice').click();
  doc.getElementById('code-apply').click(); doc.getElementById('instrument').click(); await tick();
  const points = JSON.parse(JSON.stringify(calls[0].plan.points));
  assert.equal(points[0].message, '{title}'); assert.deepEqual(points[0].variables, [{ name: 'title', type: 'java.lang.String' }]);
  assert.equal(points[1].message, '{other}'); assert.deepEqual(points[1].variables, [{ name: 'other', type: 'long' }]);
  assert.equal(catalog.methods[0].statements[1].locals, undefined, 'Browsing does not expand the entire catalog');
});
test('invalid compact variable masks do not fall back to stale variable lists', async t => {
  const catalog = variableCatalog(); catalog.methods[0].statements[1].localMask = 'invalid%';
  const { dom, doc } = createUi({ inspectCode: async () => catalog }); t.after(() => dom.window.close());
  await openVariableEditor(doc); doc.querySelectorAll('.code-line')[1].click();
  assert.equal(doc.getElementById('insert-variable').disabled, true);
});
function pendingResult() {
  let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject };
}
function lazyCatalog(hash = 'a') {
  return { apkSha256: hash.repeat(64), lazy: true, methods: [
    { className: 'org.example.Main', signature: '<org.example.Main: void first()>' },
    { className: 'org.example.Main', signature: '<org.example.Main: void second()>' },
    { className: 'org.other.Other', signature: '<org.other.Other: void run()>' }
  ] };
}
function lazyClass(name, marker = 'return') {
  return { className: name, methods: lazyCatalog().methods.filter(method => method.className === name).map(method => ({ ...method, statements: [
    { index: 1, text: `${marker} 1`, selectable: true }, { index: 2, text: `${marker} 2`, selectable: true }
  ] })) };
}
function lazyVariables(signature, name = 'value') {
  return { signature, locals: [{ name, type: 'int' }], statements: [{ index: 1, localMask: 'AQ==' }, { index: 2, localMask: 'AQ==' }] };
}
function chooseClass(doc, name) { [...doc.querySelectorAll('.class-item')].find(button => button.textContent === name).click(); }
test('lazy browsing loads only opened classes and selected methods and caches them across reopening', async t => {
  const variables = pendingResult(), classCalls = [], variableCalls = [];
  const { dom, window, doc, calls } = createUi({
    inspectCode: async () => lazyCatalog(),
    inspectClass: async name => { classCalls.push(name); return lazyClass(name); },
    inspectVariables: signature => { variableCalls.push(signature); return variables.promise; }
  }); t.after(() => dom.window.close());
  await openVariableEditor(doc);
  assert.equal(doc.getElementById('code-class').textContent, 'Choose a class');
  assert.deepEqual(classCalls, []); assert.deepEqual(variableCalls, []);
  assert.equal(doc.querySelectorAll('.tree-method').length, 0, 'Closed classes do not build every method button');
  chooseClass(doc, 'Main'); await tick();
  assert.deepEqual(classCalls, ['org.example.Main']); assert.equal(doc.querySelectorAll('.code-line').length, 4);
  assert.equal(doc.querySelectorAll('.tree-method').length, 2); assert.deepEqual(variableCalls, []);
  doc.querySelectorAll('.code-line')[0].click(); await tick();
  assert.equal(doc.getElementById('point-message').disabled, false);
  assert.match(doc.getElementById('variable-hint').textContent, /Reading available variables/);
  enterMessage(window, doc, 'first'); doc.querySelectorAll('.code-line')[1].click(); await tick();
  const input = enterMessage(window, doc, 'selected: ');
  assert.equal(variableCalls.length, 1, 'Two points in the same method share one pending analysis');
  variables.resolve(lazyVariables(variableCalls[0])); await tick();
  assert.equal(input.value, 'selected: '); insertVariable(doc, 'value');
  doc.getElementById('code-apply').click(); doc.getElementById('open-custom').click(); await tick();
  chooseClass(doc, 'Main'); await tick(); doc.querySelectorAll('.code-line')[1].click(); await tick();
  assert.equal(classCalls.length, 1); assert.equal(variableCalls.length, 1);
  assert.equal(doc.getElementById('point-message').value, 'selected: {value}');
  doc.getElementById('code-apply').click(); doc.getElementById('instrument').click(); await tick();
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0].plan.points[1].variables)), [{ name: 'value', type: 'int' }]);
});
test('late lazy class and variable responses cannot replace a newer selection', async t => {
  const main = pendingResult(), first = pendingResult(), second = pendingResult();
  const { dom, doc } = createUi({
    inspectCode: async () => lazyCatalog(),
    inspectClass: name => name === 'org.example.Main' ? main.promise : Promise.resolve(lazyClass(name, 'other')),
    inspectVariables: signature => signature.includes('first') ? first.promise : second.promise
  }); t.after(() => dom.window.close());
  await openVariableEditor(doc); chooseClass(doc, 'Main'); await tick(); chooseClass(doc, 'Other'); await tick();
  main.resolve(lazyClass('org.example.Main', 'main')); await tick();
  assert.equal(doc.getElementById('code-class').textContent, 'org.other.Other'); assert.match(doc.querySelector('.code-line').textContent, /other/);
  chooseClass(doc, 'Main'); await tick();
  doc.querySelectorAll('.code-line')[0].click(); await tick(); doc.querySelectorAll('.code-line')[2].click(); await tick();
  second.resolve(lazyVariables('<org.example.Main: void second()>', 'secondValue')); await tick();
  first.resolve(lazyVariables('<org.example.Main: void first()>', 'firstValue')); await tick();
  doc.getElementById('insert-variable').click();
  assert.deepEqual([...doc.querySelectorAll('.variable-choice')].map(button => button.dataset.variable), ['secondValue']);
});
test('a new inspection session invalidates class and variable caches even for the same APK hash', async t => {
  let session = 'first', classCalls = 0, variableCalls = 0;
  const { dom, doc } = createUi({
    inspectCode: async () => ({ ...lazyCatalog(), sessionId: session }),
    inspectClass: async name => { classCalls++; return lazyClass(name, session); },
    inspectVariables: async signature => { variableCalls++; return lazyVariables(signature, session); }
  }); t.after(() => dom.window.close());
  await openVariableEditor(doc); chooseClass(doc, 'Main'); await tick(); doc.querySelector('.code-line').click(); await tick();
  doc.getElementById('code-cancel').click(); session = 'second'; doc.getElementById('open-custom').click(); await tick();
  chooseClass(doc, 'Main'); await tick(); doc.querySelector('.code-line').click(); await tick(); doc.getElementById('insert-variable').click();
  assert.match(doc.querySelector('.code-line').textContent, /second/);
  assert.deepEqual([...doc.querySelectorAll('.variable-choice')].map(button => button.dataset.variable), ['second']);
  assert.equal(classCalls, 2); assert.equal(variableCalls, 2);
});
test('lazy class and variable failures can be retried without blocking custom text', async t => {
  let classCalls = 0, variableCalls = 0;
  const { dom, window, doc } = createUi({
    inspectCode: async () => lazyCatalog(),
    inspectClass: async name => { if (++classCalls === 1) throw Error('Read failed'); return lazyClass(name); },
    inspectVariables: async signature => { if (++variableCalls === 1) throw Error('Read failed'); return lazyVariables(signature); }
  }); t.after(() => dom.window.close());
  await openVariableEditor(doc); chooseClass(doc, 'Main'); await tick();
  assert.match(doc.getElementById('code-lines').textContent, /Read failed/);
  [...doc.querySelectorAll('#code-lines button')].find(button => button.textContent === 'Try again').click(); await tick();
  doc.querySelector('.code-line').click(); await tick();
  assert.match(doc.getElementById('variable-hint').textContent, /Read failed/);
  assert.match(doc.getElementById('variable-hint').textContent, /Select this statement again to retry/);
  enterMessage(window, doc, 'still usable'); doc.querySelector('.code-line').click(); await tick();
  assert.equal(doc.getElementById('point-message').value, 'still usable'); assert.equal(doc.getElementById('insert-variable').disabled, false);
  assert.equal(classCalls, 2); assert.equal(variableCalls, 2);
});
test('closing lazy browsing suppresses late UI updates and changing APK invalidates cached code', async t => {
  const oldClass = pendingResult(); let hash = 'a', classCalls = 0;
  const { dom, window, doc } = createUi({
    inspectCode: async () => lazyCatalog(hash),
    inspectClass: async name => { if (++classCalls === 1) return oldClass.promise; return lazyClass(name, 'new APK'); },
    inspectVariables: async signature => lazyVariables(signature)
  }); t.after(() => dom.window.close());
  await openVariableEditor(doc); chooseClass(doc, 'Main'); await tick();
  const cancel = new window.Event('cancel', { cancelable: true }); doc.getElementById('code-dialog').dispatchEvent(cancel);
  assert.equal(cancel.defaultPrevented, true); assert.equal(doc.getElementById('code-dialog').open, false);
  hash = 'b'; doc.getElementById('choose-apk').click(); await tick(); doc.getElementById('open-custom').click(); await tick();
  chooseClass(doc, 'Main'); await tick(); oldClass.resolve(lazyClass('org.example.Main', 'old APK')); await tick();
  assert.match(doc.querySelector('.code-line').textContent, /new APK/);
  assert.equal(classCalls, 2);
});
test('variable insertion replaces the selected text at the cursor and sends typed bindings', async t => {
  const { dom, window, doc, calls } = createUi({ inspectCode: async () => variableCatalog() }); t.after(() => dom.window.close());
  await openVariableEditor(doc); doc.querySelectorAll('.code-line')[1].click();
  const input = enterMessage(window, doc, 'score = xx end'); input.setSelectionRange(8, 10); insertVariable(doc, 'i0');
  assert.equal(input.value, 'score = {i0} end'); assert.equal(input.selectionStart, 12); assert.equal(doc.activeElement, input);
  input.setSelectionRange(input.value.length, input.value.length); insertVariable(doc, 'z0'); insertVariable(doc, '$r1');
  assert.equal(input.value, 'score = {i0} end{z0}{$r1}');
  doc.getElementById('code-apply').click(); doc.getElementById('instrument').click(); await tick();
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0].plan.points[0].variables)), [{ name: 'i0', type: 'int' }, { name: 'z0', type: 'boolean' }, { name: '$r1', type: 'java.lang.String' }]);
});
test('typed variable bindings survive point changes and reopening while Cancel preserves applied points', async t => {
  const { dom, window, doc, calls } = createUi({ inspectCode: async () => variableCatalog() }); t.after(() => dom.window.close());
  await openVariableEditor(doc); doc.querySelectorAll('.code-line')[1].click(); insertVariable(doc, 'i0');
  doc.querySelectorAll('.code-line')[2].click(); enterMessage(window, doc, 'last');
  doc.querySelectorAll('.code-line')[1].click(); assert.equal(doc.getElementById('point-message').value, '{i0}');
  doc.getElementById('code-apply').click(); doc.getElementById('open-custom').click(); await tick();
  doc.querySelectorAll('.code-line')[1].click(); assert.equal(doc.getElementById('point-message').value, '{i0}');
  doc.getElementById('remove-point').click(); doc.getElementById('code-cancel').click();
  doc.getElementById('instrument').click(); await tick();
  assert.deepEqual(JSON.parse(JSON.stringify(calls[0].plan.points[0].variables)), [{ name: 'i0', type: 'int' }]);
  assert.equal(calls[0].plan.points[0].message, '{i0}');
});
test('removing the last variable token prunes its binding and manually typed braces stay literal', async t => {
  const { dom, window, doc, calls } = createUi({ inspectCode: async () => variableCatalog() }); t.after(() => dom.window.close());
  await openVariableEditor(doc); doc.querySelectorAll('.code-line')[1].click(); insertVariable(doc, 'i0'); insertVariable(doc, 'i0');
  enterMessage(window, doc, 'one {i0}'); doc.getElementById('code-apply').click(); doc.getElementById('instrument').click(); await tick();
  assert.equal(calls[0].plan.points[0].variables.length, 1);
  doc.getElementById('open-custom').click(); await tick(); doc.querySelectorAll('.code-line')[1].click();
  enterMessage(window, doc, 'no token'); enterMessage(window, doc, 'literal {i0}');
  doc.getElementById('code-apply').click(); doc.getElementById('instrument').click(); await tick();
  assert.equal(calls[1].plan.points[0].message, 'literal {i0}'); assert.equal(calls[1].plan.points[0].variables, undefined);
});
test('Escape closes the variable picker without dismissing the code editor', async t => {
  const { dom, window, doc } = createUi({ inspectCode: async () => variableCatalog() }); t.after(() => dom.window.close());
  await openVariableEditor(doc); doc.querySelectorAll('.code-line')[1].click(); doc.getElementById('insert-variable').click();
  const event = new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
  doc.querySelector('.variable-choice').dispatchEvent(event);
  assert.equal(event.defaultPrevented, true); assert.equal(doc.getElementById('variable-picker').hidden, true); assert.equal(doc.getElementById('code-dialog').open, true);
  assert.equal(doc.activeElement, doc.getElementById('insert-variable'));
});
test('coverage shows the latest actual variable message as inert text and preserves the code link', async t => {
  const samples = ['score=1', '<img src=x onerror="window.bad=true"> score=2'];
  const { dom, window, doc } = createUi({ analyze: async () => ({ coverageSummary: {}, custom: { summary: { points: [{ id: 'variables', message: 'score={i0}', method: '<A: void run()>', index: 2, status: 'covered', count: 2, samples }] } } }) });
  t.after(() => dom.window.close()); await selectRun(doc); doc.getElementById('analyze').click(); await tick();
  const sample = doc.querySelector('.point-sample'); assert.equal(sample.textContent, 'Latest: ' + samples.at(-1));
  assert.equal(sample.querySelector('img'), null); assert.equal(window.bad, undefined);
  assert.match(doc.querySelector('.point-status').textContent, /Covered/); doc.querySelector('.custom-point-link').click();
  assert.equal(doc.getElementById('page-explore').hidden, false);
});
