'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { readCustomMessages } = require('../src/backend');
const { preparePlan, savePlan, listPlans, summarize, addReportPanel } = require('../src/custom-report');
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
  const html = addReportPanel('<section class="metrics" id="metrics"></section>', summary);
  assert.match(html, /1 of 2 observed/); assert.match(html, /Not observed/); assert.match(html, /&lt;script&gt;same/); assert.doesNotMatch(html, /<script>same/);
});
