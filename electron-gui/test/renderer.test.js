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
