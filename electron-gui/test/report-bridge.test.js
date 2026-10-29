'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM, VirtualConsole } = require('jsdom');
const resources = path.join(__dirname, '../../src/main/resources');
test('embedded report accepts only known categories from its parent', () => {
  const parent = {};
  const data = { appName: 'Fixture', categories: [{ key: 'classes', label: 'Classes', total: 2, covered: 1 }, { key: 'activities', label: 'Activities', total: 1, covered: 1 }], classes: [], callGraph: { nodes: [], edges: [] } };
  const html = fs.readFileSync(path.join(resources, 'report.html'), 'utf8')
    .replace('__ANDROLOG_DATA__', JSON.stringify(data))
    .replace('__ANDROLOG_LINKS__', fs.readFileSync(path.join(resources, 'report-links.js'), 'utf8'))
    .replace('__ANDROLOG_GRAPH__', fs.readFileSync(path.join(resources, 'report-graph.js'), 'utf8'));
  const errors = []; const console = new VirtualConsole(); console.on('jsdomError', error => errors.push(error));
  const dom = new JSDOM(html, { url: 'https://report.invalid/', runScripts: 'dangerously', pretendToBeVisual: true, virtualConsole: console,
    beforeParse(window) { Object.defineProperty(window, 'parent', { value: parent }); } });
  try {
    assert.deepEqual(errors.map(e => e.message), []);
    const { window } = dom; const activity = window.document.querySelector('[data-metric="activities"]');
    assert.ok(activity); assert.equal(activity.getAttribute('aria-pressed'), 'false');
    window.dispatchEvent(new window.MessageEvent('message', { source: {}, data: { type: 'androlog.selectCategory', category: 'activities' } }));
    assert.equal(activity.getAttribute('aria-pressed'), 'false');
    window.dispatchEvent(new window.MessageEvent('message', { source: parent, data: { type: 'androlog.selectCategory', category: '__proto__' } }));
    assert.equal(activity.getAttribute('aria-pressed'), 'false');
    window.dispatchEvent(new window.MessageEvent('message', { source: parent, data: { type: 'androlog.selectCategory', category: 'activities' } }));
    assert.equal(activity.getAttribute('aria-pressed'), 'true');
  } finally { dom.window.close(); }
});
