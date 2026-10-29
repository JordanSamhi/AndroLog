'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const html = fs.readFileSync(path.join(__dirname, '../src/index.html'), 'utf8');
const script = fs.readFileSync(path.join(__dirname, '../src/animation.js'), 'utf8');
function setup(reduced = false) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://androlog.invalid/' });
  const calls = [];
  dom.window.matchMedia = () => ({ matches: reduced });
  dom.window.anime = { utils: { remove: el => calls.push({ action: 'remove', id: el.id }) }, animate: (el, options) => { calls.push({ action: 'animate', id: el.id, options }); return { cancel: () => calls.push({ action: 'cancel', id: el.id }) }; } };
  dom.window.eval(script); calls.length = 0;
  return { dom, calls, animation: dom.window.andrologAnimation };
}
test('compact scanner follows stages and stops when finished', t => {
  const { dom, calls, animation } = setup(); t.after(() => dom.window.close());
  animation.setStage(2);
  assert.equal(dom.window.document.getElementById('apk-stage-label').textContent, 'Adding logs');
  assert.equal(calls.filter(c => c.action === 'animate').length, 1);
  assert.equal(calls.find(c => c.action === 'animate').id, 'apk-scan');
  assert.equal(calls.find(c => c.action === 'animate').options.loop, true);
  calls.length = 0; animation.setStage(5);
  assert.ok(calls.some(c => c.action === 'cancel'));
  assert.ok(!calls.some(c => c.action === 'animate'));
  assert.equal(dom.window.document.getElementById('apk-complete').style.opacity, '1');
  assert.equal(dom.window.document.getElementById('apk-scan').style.opacity, '0');
});
test('reduced motion shows a static scanner without starting animations', t => {
  const { dom, calls, animation } = setup(true); t.after(() => dom.window.close());
  animation.setStage(2);
  assert.equal(calls.filter(c => c.action === 'animate').length, 0);
  assert.equal(dom.window.document.getElementById('apk-stage-label').textContent, 'Adding logs');
});
test('the locally installed Anime.js bundle drives the scanner', async t => {
  const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true, url: 'https://androlog.invalid/' });
  t.after(() => dom.window.close()); dom.window.matchMedia = () => ({ matches: false });
  const bundle = path.join(path.dirname(require.resolve('animejs/package.json')), 'dist/bundles/anime.umd.min.js');
  dom.window.eval(fs.readFileSync(bundle, 'utf8')); dom.window.eval(script); dom.window.andrologAnimation.setStage(2);
  await new Promise(resolve => setTimeout(resolve, 500));
  assert.match(dom.window.document.getElementById('apk-scan').style.transform, /translateY/);
  dom.window.andrologAnimation.setStage(0, true);
  assert.equal(dom.window.document.getElementById('apk-scan').style.opacity, '0');
});

test('analysis animates the real stage and cancels its loops on finish', t => {
  const { dom, calls, animation } = setup(); t.after(() => dom.window.close());
  animation.setAnalysis(1, true);
  assert.ok(calls.some(c => c.id === 'analysis-stream' && c.options?.loop));
  assert.ok(calls.some(c => c.id === 'analysis-apk' && c.options?.loop));
  calls.length = 0; animation.setAnalysis(3, true);
  assert.ok(calls.some(c => c.action === 'cancel' && c.id === 'analysis-apk'));
  assert.ok(calls.some(c => c.id === 'analysis-match' && c.options?.loop));
  calls.length = 0; animation.setAnalysis(0, false);
  assert.ok(calls.some(c => c.action === 'cancel' && c.id === 'analysis-stream'));
  assert.ok(!calls.some(c => c.action === 'animate'));
});
test('analysis respects reduced motion', t => {
  const { dom, calls, animation } = setup(true); t.after(() => dom.window.close());
  animation.setAnalysis(3, true); assert.ok(!calls.some(c => c.action === 'animate'));
});
