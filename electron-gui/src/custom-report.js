'use strict';
const crypto = require('node:crypto');
const { validatePlan } = require('./backend');

function preparePlan(plan, tag) {
  validatePlan(plan);
  return { id: crypto.randomBytes(16).toString('hex'), tag, createdAt: new Date().toISOString(), apkSha256: plan.apkSha256,
    points: plan.points.map(point => ({ ...point, id: crypto.randomBytes(16).toString('hex') })) };
}
module.exports = { preparePlan };
