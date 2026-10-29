'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { readCustomMessages } = require('../src/backend');
const { preparePlan, savePlan, listPlans, resolveSummary, summarize, addReportPanel } = require('../src/custom-report');
test('point markers distinguish identical messages, persist per build and appear safely in exported reports', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androlog-custom-')); t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const original = { apkSha256: 'a'.repeat(64), points: [
    { method: '<A: void one()>', index: 1, statement: 'return', message: '<script>same</script>' },
    { method: '<A: void two()>', index: 2, statement: 'return', message: '<script>same</script>' }
  ] };
  const plan = preparePlan(original, 'TEST'); await savePlan(dir, plan);
  assert.notEqual(plan.points[0].id, plan.points[1].id);
  assert.equal((await listPlans(dir, original.apkSha256, 'OTHER')).length, 0);
  assert.equal((await listPlans(dir, 'b'.repeat(64), 'TEST')).length, 0);
  const stored = (await listPlans(dir, original.apkSha256, 'TEST'))[0];
  const logPath = path.join(dir, 'test.log'); await fs.writeFile(logPath, `I/TEST( 123): CUSTOM_POINT=${plan.points[0].id}\nTEST: CUSTOM_POINT=${plan.points[0].id}\nOTHER: CUSTOM_POINT=${plan.points[1].id}\n`);
  const log = await readCustomMessages(logPath, 'TEST'); const summary = summarize(stored, log);
  assert.equal(summary.observed, 1); assert.equal(summary.points[0].count, 2); assert.equal(summary.points[1].count, 0);
  const html = addReportPanel('<section class="metrics" id="metrics"></section><script type="application/json" id="report-data">{"appName":"Fixture"}</script>', summary);
  const embedded = JSON.parse(html.match(/id="report-data">([\s\S]*?)<\/script>/)[1]);
  assert.deepEqual(embedded.customPoints, summary.points);
  assert.equal(embedded.appName, 'Fixture');
  assert.ok(html.includes(`href="#custom=${plan.points[0].id}"`));
  assert.match(html, /<h2>Custom logs<\/h2>/); assert.match(html, />Covered<\/span>/); assert.match(html, />Not covered<\/span>/);
  assert.match(html, /&lt;script&gt;same/); assert.doesNotMatch(html, /<script>same/);
  assert.match(html, /&lt;A: void one\(\)&gt; · statement 1/);
  assert.match(html, /\.custom-point.seen\{background:#e2f5e9\}/);
  assert.match(html, /\.custom-point.unseen\{background:#fff0ee\}/);
  assert.doesNotMatch(html, /<code>|2×|1 of 2|<p>/);
});

function fixturePlan() {
  return preparePlan({ apkSha256: 'a'.repeat(64), points: [{ method: '<A: void one()>', index: 1, statement: 'return', message: 'same message' }] }, 'TEST');
}
test('runtime markers identify the custom baseline automatically', () => {
  const first = fixturePlan(), second = fixturePlan();
  const log = { markers: { [second.points[0].id]: 2 }, messages: [{ message: 'same message', count: 2 }] };
  const summary = resolveSummary([first, second], log);
  assert.equal(summary.id, second.id); assert.equal(summary.points[0].count, 2);
});
test('repeated instrumentation of the same custom baseline needs no build selection', () => {
  const first = fixturePlan(), second = fixturePlan();
  const empty = { markers: {}, total: 0 };
  assert.equal(resolveSummary([], empty), null);
  assert.equal(resolveSummary([first, second], empty).points[0].count, 0);
  const mixed = { markers: { [first.points[0].id]: 1, [second.points[0].id]: 2 } };
  const summary = resolveSummary([first, second], mixed);
  assert.equal(summary.points.length, 1); assert.equal(summary.points[0].count, 3); assert.equal(summary.observed, 1);
});
test('different or unknown custom baselines never invent uncovered points', () => {
  const first = fixturePlan(), second = fixturePlan();
  second.points[0].message = 'a different custom log';
  second.points.push({ ...second.points[0], id: 'c'.repeat(32), index: 2, message: 'never recorded' });
  assert.equal(resolveSummary([first, second], { markers: {} }), null);
  assert.equal(resolveSummary([first], { markers: { unknown: 1 } }), null);
  assert.equal(resolveSummary([first], { markers: {}, total: 1, messages: [{ message: 'same message', count: 1 }] }), null);
  const mixed = { markers: { [first.points[0].id]: 1, [second.points[0].id]: 2 } };
  const summary = resolveSummary([first, second], mixed);
  assert.equal(summary.partial, true); assert.equal(summary.points.length, 2);
  assert.ok(summary.points.every(point => point.count > 0));
  assert.ok(summary.points.every(point => point.method === first.points[0].method));
});
