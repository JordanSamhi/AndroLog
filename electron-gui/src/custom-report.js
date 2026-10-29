'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { validatePlan } = require('./backend');
const ID = /^[a-f0-9]{32}$/;

function preparePlan(plan, tag) {
  validatePlan(plan);
  return { id: crypto.randomBytes(16).toString('hex'), tag, createdAt: new Date().toISOString(), apkSha256: plan.apkSha256,
    points: plan.points.map(point => ({ ...point, id: crypto.randomBytes(16).toString('hex') })) };
}
async function savePlan(dir, plan) {
  await fs.mkdir(dir, { recursive: true, mode: 0o700 });
  await fs.writeFile(path.join(dir, `${plan.id}.json`), JSON.stringify(plan), { flag: 'wx', mode: 0o600 });
}
async function listPlans(dir, apkSha256, tag) {
  let files; try { files = await fs.readdir(dir); } catch (error) { if (error.code === 'ENOENT') return []; throw error; }
  const plans = [];
  for (const file of files) {
    if (!/^[a-f0-9]{32}\.json$/.test(file)) continue;
    try {
      const item = JSON.parse(await fs.readFile(path.join(dir, file), 'utf8'));
      validatePlan(item);
      if (!ID.test(item.id) || file !== `${item.id}.json` || !item.points.every(point => ID.test(point.id))) continue;
      if (item.apkSha256 === apkSha256 && item.tag === tag) plans.push(item);
    } catch { /* Ignore invalid local records. */ }
  }
  return plans.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}
// Point markers are unique to a build, unlike user-authored message text.
function selectPlan(plans, log, planId) {
  if (planId) {
    const plan = plans.find(item => item.id === planId);
    if (!plan) throw new Error('The selected custom log build does not match this APK and tag.');
    return { plan, needsPlanSelection: false };
  }
  const matches = plans.filter(plan => plan.points.some(point => (log.markers?.[point.id] || 0) > 0));
  if (matches.length === 1) return { plan: matches[0], needsPlanSelection: false };
  if (matches.length === 0 && plans.length === 1) return { plan: plans[0], needsPlanSelection: false };
  return { plan: null, needsPlanSelection: matches.length > 1 || plans.length > 1 };
}
function summarize(plan, log) {
  if (!plan) return null;
  const points = plan.points.map(point => ({ ...point, count: log.markers[point.id] || 0 }));
  return { id: plan.id, createdAt: plan.createdAt, points, observed: points.filter(point => point.count > 0).length };
}
function escape(value) { return String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function addReportPanel(html, summary) {
  if (!summary) return html;
  const dataElement = /(<script\b[^>]*\bid="report-data"[^>]*>)([\s\S]*?)(<\/script>)/;
  const match = dataElement.exec(html);
  if (!match) throw new Error('Could not attach custom log locations to the report.');
  const data = JSON.parse(match[2]); data.customPoints = summary.points;
  const json = JSON.stringify(data).replace(/[<>&\u2028\u2029]/g, c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'));
  html = html.replace(dataElement, (_match, open, _data, close) => open + json + close);
  const rows = summary.points.map(point => `<li><a href="#custom=${encodeURIComponent(point.id)}" data-custom-point="${escape(point.id)}" class="custom-point ${point.count ? 'seen' : 'unseen'}"><div><strong>${escape(point.message)}</strong><small>${escape(point.method)} · statement ${escape(point.index)}</small></div><span title="${point.count ? 'A matching point marker was found in the supplied log.' : 'No matching point marker was found in the supplied log.'}">${point.count ? 'Covered' : 'Not covered'}</span></a></li>`).join('');
  const panel = `<style>.custom-points{margin:0 0 20px;padding:17px 20px;background:white;border:1px solid #dce4e9;border-radius:12px}.custom-points h2{font-size:16px;color:#172e43;margin:0 0 12px}.custom-points ul{padding:0;margin:0;list-style:none}.custom-point{color:inherit;text-decoration:none;display:flex;justify-content:space-between;gap:14px;padding:12px;border-radius:8px;margin-top:7px;overflow-wrap:anywhere}.custom-point:focus-visible{outline:3px solid #087c70;outline-offset:2px}.custom-point:hover{box-shadow:inset 0 0 0 1px #a4b8ae}.custom-point.seen{background:#e2f5e9}.custom-point.unseen{background:#fff0ee}.custom-point small{display:block;color:#536879;font-size:11px;margin-top:4px}.custom-point span{white-space:nowrap;font-size:12px;font-weight:700;color:#087c70}.custom-point.unseen span{color:#a53e40}</style><section class="custom-points" aria-label="Custom logs"><h2>Custom logs</h2><ul>${rows}</ul></section>`;
  const anchor = '<section class="metrics" id="metrics"';
  if (!html.includes(anchor)) throw new Error('Could not add custom points to the report.');
  return html.replace(anchor, () => panel + anchor);
}
module.exports = { preparePlan, savePlan, listPlans, selectPlan, summarize, addReportPanel };
