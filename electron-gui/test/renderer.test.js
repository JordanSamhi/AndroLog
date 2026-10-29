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
    settings: async () => ({ platforms: '/sdk/platforms', buildTools: '/sdk/build-tools/36' }),
    chooseApk: async () => '/apps/original.apk', chooseLog: async () => '/logs/runtime.log',
    readCustom: async () => ({total: 2, omitted: 0, messages: [{message: 'reached this point', count: 2}]}),
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
test('coverage requires both inputs and displays matched statistics without inventing zero totals', async t => {
  const { dom, doc } = createUi(); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick(); assert.equal(doc.getElementById('analyze').disabled, true);
  doc.getElementById('choose-log').click(); await tick(); assert.equal(doc.getElementById('analyze').disabled, false);
  assert.match(doc.getElementById('messages-list').textContent, /reached this point/);
  doc.getElementById('analyze').click(); await tick();
  const cards = [...doc.querySelectorAll('.stat')];
  assert.match(cards[1].textContent, /25\.0%/); assert.match(cards[1].textContent, /1 of 4 observed/);
  assert.match(cards[4].textContent, /Nothing to cover/); assert.equal(doc.getElementById('report-frame').hidden, false);
  doc.querySelector('[data-probe="1"]').click(); assert.equal(doc.getElementById('report-frame').hidden, true);
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

test('the visible Logcat tags stay in sync between instrumentation and coverage', async t => {
  const { dom, window, doc } = createUi(); t.after(() => dom.window.close()); await tick();
  const instrumentTag = doc.getElementById('log-tag'), coverageTag = doc.getElementById('coverage-tag');
  assert.equal(instrumentTag.closest('details'), null);
  assert.equal(coverageTag.closest('details'), null);
  instrumentTag.value = 'TEST_TAG'; instrumentTag.dispatchEvent(new window.Event('input', { bubbles: true }));
  assert.equal(coverageTag.value, 'TEST_TAG');
  coverageTag.value = 'ANOTHER_TAG'; coverageTag.dispatchEvent(new window.Event('input', { bubbles: true }));
  assert.equal(instrumentTag.value, 'ANOTHER_TAG');
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
  const { dom, window, doc } = createUi({ platform: 'darwin', saveApk: async () => "/tmp/Jordan's app/app.apk" }); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick();
  const tag = doc.getElementById('log-tag'); tag.value = 'MY_TAG'; tag.dispatchEvent(new window.Event('input', { bubbles: true }));
  doc.getElementById('instrument').click(); await tick();
  assert.equal(doc.getElementById('next-steps').hidden, true);
  tag.value = 'DIFFERENT'; tag.dispatchEvent(new window.Event('input', { bubbles: true }));
  let revealed = false; doc.getElementById('next-steps').scrollIntoView = () => { revealed = true; };
  doc.getElementById('save-apk').click(); await tick();
  assert.equal(doc.getElementById('next-steps').hidden, false);
  assert.equal(revealed, true);
  assert.equal(doc.getElementById('install-command').textContent, "adb install -r '/tmp/Jordan'\\''s app/app.apk'");
  assert.equal(doc.getElementById('logcat-command').textContent, "adb logcat -v threadtime -s 'MY_TAG:V' > androlog-runtime.log");
});
test('Windows instructions quote the APK for PowerShell and save UTF-8 logcat bytes', async t => {
  const { dom, doc } = createUi({ platform: 'win32', saveApk: async () => "C:\\Users\\O'Brien\\app.apk" }); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick(); doc.getElementById('instrument').click(); await tick();
  doc.getElementById('save-apk').click(); await tick();
  assert.match(doc.getElementById('terminal-instructions').textContent, /PowerShell/);
  assert.equal(doc.getElementById('install-command').textContent, "adb install -r 'C:\\Users\\O''Brien\\app.apk'");
  assert.equal(doc.getElementById('logcat-command').textContent, 'cmd /c "adb logcat -v threadtime -s ANDROLOG:V > androlog-runtime.log"');
});

test('custom-only APK instructions do not claim coverage probes were enabled', async t => {
  const { dom, window, doc } = createUi(); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick();
  doc.getElementById('open-custom').click(); await tick(); doc.querySelector('.code-line').click();
  const field = doc.getElementById('point-message'); field.value = 'here'; field.dispatchEvent(new window.Event('input', { bubbles: true }));
  doc.getElementById('code-apply').click(); doc.querySelectorAll('[data-probe]').forEach(input => { input.checked = false; });
  doc.getElementById('instrument').click(); await tick(); doc.getElementById('save-apk').click(); await tick();
  assert.match(doc.getElementById('next-coverage').textContent, /custom points/);
  assert.match(doc.getElementById('next-coverage').textContent, /analyze the run/);
});

test('custom code explorer groups methods under classes and searches method names', async t => {
  const { dom, window, doc } = createUi({ inspectCode: async () => ({ apkSha256: 'a'.repeat(64), methods: [
    { className: 'org.example.Main', signature: '<org.example.Main: void first()>', statements: [{ index: 1, text: 'return', selectable: true }] },
    { className: 'org.example.Main', signature: '<org.example.Main: void second()>', statements: [{ index: 1, text: 'return', selectable: true }] },
    { className: 'org.other.Other', signature: '<org.other.Other: void third()>', statements: [] }
  ] }) }); t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick(); doc.getElementById('open-custom').click(); await tick();
  assert.equal(doc.querySelectorAll('.tree-package').length, 2);
  assert.equal(doc.querySelectorAll('.tree-class')[0].querySelectorAll('.tree-method').length, 2);
  doc.querySelectorAll('.tree-method')[1].click();
  assert.match(doc.getElementById('code-class').textContent, /Main/);
  const search = doc.getElementById('class-search'); search.value = 'third'; search.dispatchEvent(new window.Event('input'));
  assert.equal(doc.querySelectorAll('.tree-method').length, 1); assert.match(doc.querySelector('.tree-method').textContent, /third/);
});

test('custom points show observed and not observed separately and select the build for analysis', async t => {
  const builds = [{ id: 'a', createdAt: '2026-10-29T08:00:00Z', count: 2 }]; let analyzedPlan;
  const summary = { observed: 1, points: [{ message: 'same', method: '<A: void a()>', index: 1, statement: 'return', count: 3 }, { message: 'same', method: '<A: void b()>', index: 2, statement: 'return', count: 0 }] };
  const { dom, doc } = createUi({ customPlans: async () => builds, readCustom: async (_tag, id) => ({ total: 3, messages: [], summary: id ? summary : null }), analyze: async (_options, id) => { analyzedPlan = id; return { coverageSummary: {} }; } });
  t.after(() => dom.window.close());
  doc.getElementById('choose-apk').click(); await tick(); doc.getElementById('choose-log').click(); await tick();
  assert.match(doc.getElementById('messages-summary').textContent, /1 of 2/);
  assert.match(doc.querySelector('.point-row.observed').textContent, /Observed · 3×/);
  assert.match(doc.querySelector('.point-row.unobserved').textContent, /Not observed/);
  doc.getElementById('analyze').click(); await tick(); assert.equal(analyzedPlan, 'a');
});
