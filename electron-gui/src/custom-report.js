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
function summarize(plan, log) {
  if (!plan) return null;
  const points = plan.points.map(point => ({ ...point, count: log.markers[point.id] || 0 }));
  return { id: plan.id, createdAt: plan.createdAt, points, observed: points.filter(point => point.count > 0).length };
}
function escape(value) { return String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function addReportPanel(html, summary) {
  if (!summary) return html;
  const rows = summary.points.map(point => `<li class="custom-point ${point.count ? 'seen' : 'unseen'}"><div><strong>${escape(point.message)}</strong><small>${escape(point.method)} · statement ${point.index}</small><code>${escape(point.statement)}</code></div><span>${point.count ? `Observed · ${point.count}×` : 'Not observed'}</span></li>`).join('');
  const panel = `<style>.custom-points{margin:0 0 20px;padding:17px 20px;background:white;border:1px solid #dce4e9;border-radius:12px}.custom-points summary{cursor:pointer;color:#172e43;font-weight:700}.custom-points p{color:#536879;font-size:12px;margin:8px 0}.custom-points ul{padding:0;list-style:none}.custom-point{display:flex;justify-content:space-between;gap:14px;padding:12px;border-radius:8px;margin-top:7px;background:#f3f6f8}.custom-point.seen{background:#e2f5e9}.custom-point small,.custom-point code{display:block;overflow-wrap:anywhere;color:#536879;font-size:11px}.custom-point span{white-space:nowrap;font-size:12px;font-weight:700;color:#087c70}.custom-point.unseen span{color:#637683}</style><details class="custom-points" open><summary>Custom log points · ${summary.observed} of ${summary.points.length} observed</summary><p>Not observed means no matching point marker was found in this log. Verify that it came from the selected instrumented build; incomplete logs can miss execution.</p><ul>${rows}</ul></details>`;
  const anchor = '<section class="metrics" id="metrics"';
  if (!html.includes(anchor)) throw new Error('Could not add custom points to the report.');
  return html.replace(anchor, panel + anchor);
}
module.exports = { preparePlan, savePlan, listPlans, summarize, addReportPanel };
