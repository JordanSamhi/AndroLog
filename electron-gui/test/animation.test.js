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
test('package animation follows actual read, insertion, rebuild and signing stages', t => {
  const { dom, calls, animation } = setup(); t.after(() => dom.window.close());
  animation.setStage(1);
  assert.equal(dom.window.document.getElementById('apk-animation').dataset.stage, '1');
  assert.equal(calls.find(c => c.action === 'animate' && c.id === 'apk-lid').options.translateY, -42);
  assert.equal(calls.find(c => c.action === 'animate' && c.id === 'apk-sheet').options.opacity, 1);
  assert.equal(calls.find(c => c.action === 'animate' && c.id === 'apk-scan').options.loop, true);
  calls.length = 0; animation.setStage(2);
  assert.equal(calls.find(c => c.action === 'animate' && c.id === 'apk-probe').options.opacity, 1);
  assert.ok(calls.some(c => c.action === 'cancel' && c.id === 'apk-scan'));
  assert.equal(calls.find(c => c.action === 'animate' && c.id === 'apk-probe-halo').options.loop, true);
  calls.length = 0; animation.setStage(3);
  assert.equal(calls.find(c => c.action === 'animate' && c.id === 'apk-lid').options.translateY, 0);
  assert.equal(calls.find(c => c.action === 'animate' && c.id === 'apk-rebuild-shine').options.loop, true);
  calls.length = 0; animation.setStage(4);
  assert.equal(calls.find(c => c.action === 'animate' && c.id === 'apk-seal').options.opacity, 1);
  assert.equal(calls.find(c => c.action === 'animate' && c.id === 'apk-sign-halo').options.loop, true);
  calls.length = 0; animation.setStage(5);
  assert.ok(calls.some(c => c.action === 'cancel' && c.id === 'apk-sign-halo'));
  assert.ok(!calls.some(c => c.action === 'animate' && c.options.loop));
});
test('reduced motion applies the final stage without starting animations', t => {
  const { dom, calls, animation } = setup(true); t.after(() => dom.window.close());
  animation.setStage(5);
  assert.equal(calls.filter(c => c.action === 'animate').length, 0);
  assert.equal(dom.window.document.getElementById('apk-seal').style.opacity, '1');
});

test('the locally installed Anime.js bundle drives the SVG to its selected state', async t => {
  const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true, url: 'https://androlog.invalid/' });
  t.after(() => dom.window.close());
  dom.window.matchMedia = () => ({ matches: false });
  const bundle = path.join(path.dirname(require.resolve('animejs/package.json')), 'dist/bundles/anime.umd.min.js');
  dom.window.eval(fs.readFileSync(bundle, 'utf8')); dom.window.eval(script);
  dom.window.andrologAnimation.setStage(2);
  await new Promise(resolve => setTimeout(resolve, 800));
  assert.ok(Number(dom.window.document.getElementById('apk-probe').style.opacity) > 0.99);
  assert.match(dom.window.document.getElementById('apk-lid').style.transform, /-42px/);
  assert.equal(dom.window.document.getElementById('apk-animation').dataset.stage, '2');
  dom.window.andrologAnimation.setStage(5, true);
});
