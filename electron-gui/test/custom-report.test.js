'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { preparePlan } = require('../src/custom-report');

test('custom points have distinct embedded markers even when messages are identical', () => {
  const original = { apkSha256: 'a'.repeat(64), points: [
    { method: '<A: void one()>', index: 1, statement: 'return', message: 'same message' },
    { method: '<A: void two()>', index: 2, statement: 'return', message: 'same message' }
  ] };
  const plan = preparePlan(original, 'TEST');
  assert.equal(plan.apkSha256, original.apkSha256); assert.equal(plan.tag, 'TEST');
  assert.notEqual(plan.points[0].id, plan.points[1].id);
  for (let index = 0; index < plan.points.length; index++) {
    assert.match(plan.points[index].id, /^[a-f0-9]{32}$/);
    const { id, ...definition } = plan.points[index]; assert.deepEqual(definition, original.points[index]);
  }
  assert.equal(original.points[0].id, undefined, 'The source selections remain unchanged');
  const next = preparePlan(original, 'TEST');
  assert.notEqual(next.points[0].id, plan.points[0].id, 'Reinstrumentation produces a distinct runtime marker');
});
