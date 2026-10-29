'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM, VirtualConsole } = require('jsdom');
const resources = path.join(__dirname, '../../src/main/resources');
const tick = () => new Promise(resolve => setTimeout(resolve, 15));
const first = '<example.App: void run(int)>', second = '<example.App: void run(java.lang.String)>';
const text = 'return';
function fixture(options = {}) {
  const parent = {}, errors = [];
  const points = options.points || [
    { id: 'covered', method: first, index: 1, statement: text, message: 'same message', count: 2 },
    { id: 'uncovered', method: first, index: 2, statement: text, message: 'same message', count: 0 },
    { id: 'overload', method: second, index: 1, statement: text, message: 'same message', count: 1 }
  ];
  const data = { appName: 'Fixture', categories: [{ key: 'classes', label: 'Classes', total: 1, covered: 0 }, { key: 'methods', label: 'Methods', total: 2, covered: 0 }], classes: [
    { name: 'example.App', packageName: 'example', classCovered: false, methods: [
      { signature: first, covered: false, statements: [{ line: 1, text, status: 'unmeasured' }, { line: 2, text, status: 'unmeasured' }] },
      { signature: second, covered: false, statements: [{ line: 1, text, status: 'unmeasured' }] }
    ] }
  ], callGraph: { available: false }, customPoints: points };
  const html = fs.readFileSync(path.join(resources, 'report.html'), 'utf8')
    .replace('__ANDROLOG_DATA__', () => JSON.stringify(data).replace(/</g, '\\u003c'))
    .replace('__ANDROLOG_LINKS__', () => fs.readFileSync(path.join(resources, 'report-links.js'), 'utf8'))
    .replace('__ANDROLOG_GRAPH__', () => fs.readFileSync(path.join(resources, 'report-graph.js'), 'utf8'));
  const virtualConsole = new VirtualConsole(); virtualConsole.on('jsdomError', error => errors.push(error));
  const dom = new JSDOM(html, { url: 'https://report.invalid/' + (options.hash || ''), runScripts: 'dangerously', pretendToBeVisual: true, virtualConsole,
    beforeParse(window) { Object.defineProperty(window, 'parent', { value: parent }); window.HTMLElement.prototype.scrollIntoView = function () { this.dataset.scrolledIntoView = 'true'; }; } });
  return { dom, doc: dom.window.document, parent, errors };
}

test('custom annotations use exact statement coordinates without changing automatic coverage', t => {
  const { dom, doc, errors } = fixture(); t.after(() => dom.window.close());
  const badges = [...doc.querySelectorAll('.custom-inline-log')]; assert.equal(badges.length, 3);
  assert.ok(doc.querySelector('[data-custom-point-id="covered"]').classList.contains('covered'));
  assert.ok(doc.querySelector('[data-custom-point-id="uncovered"]').classList.contains('uncovered'));
  const secondRow = doc.querySelector('[data-custom-point-id="uncovered"]').closest('tr');
  assert.equal(secondRow.dataset.jimpleLine, '2'); assert.equal(secondRow.dataset.jimpleMethod, first);
  assert.equal(secondRow.dataset.status, 'unmeasured');
  assert.equal(doc.querySelector('[data-custom-point-id="overload"]').closest('tr').dataset.jimpleMethod, second);
  assert.deepEqual(errors.map(e => e.message), []);
});

test('exported point links reset filters and jump to the precise statement in a whole class', async t => {
  const { dom, doc, errors } = fixture(); t.after(() => dom.window.close());
  doc.querySelector('[data-filter="covered"]').click();
  assert.equal(doc.querySelectorAll('.custom-inline-log').length, 0);
  const input = doc.getElementById('class-search'); input.value = 'no such class'; input.dispatchEvent(new dom.window.Event('input'));
  doc.querySelector('a[data-custom-point="uncovered"]').click(); await tick();
  assert.equal(doc.getElementById('class-search').value, '');
  assert.equal(doc.querySelector('[data-filter="all"]').getAttribute('aria-pressed'), 'true');
  const target = doc.querySelector('.navigation-target'); assert.ok(target);
  assert.equal(target.dataset.jimpleLine, '2'); assert.equal(target.dataset.jimpleMethod, first);
  assert.equal(doc.activeElement, target); assert.equal(doc.querySelectorAll('tr.method-declaration').length, 2);
  assert.equal(doc.getElementById('custom-navigation-note').hidden, true);
  assert.equal(doc.getElementById('code-explorer').dataset.scrolledIntoView, 'true');
  assert.deepEqual(errors.map(e => e.message), []);
});

test('parent bridge accepts only an embedded point ID from its own parent', async t => {
  const { dom, doc, parent, errors } = fixture(); t.after(() => dom.window.close());
  const dispatch = (source, pointId) => dom.window.dispatchEvent(new dom.window.MessageEvent('message', { source, data: { type: 'androlog.selectCustomPoint', pointId } }));
  dispatch({}, 'overload'); dispatch(parent, '__proto__'); dispatch(parent, 'missing'); await tick();
  assert.equal(dom.window.location.hash, '');
  dispatch(parent, 'overload'); await tick();
  assert.equal(doc.querySelector('.navigation-target').dataset.jimpleMethod, second);
  assert.equal(doc.querySelector('.navigation-target').dataset.jimpleLine, '1');
  assert.equal(doc.getElementById('code-view').hidden, false);
  assert.deepEqual(errors.map(e => e.message), []);
});

test('mismatched or out-of-scope points show a visible explanation without marking a wrong row', async t => {
  const { dom, doc, errors } = fixture({ points: [
    { id: 'stale', method: first, index: 1, statement: 'changed code', message: 'stale message', count: 1 },
    { id: 'outside', method: '<outside.Class: void run()>', index: 1, statement: text, message: 'outside', count: 0 }
  ] }); t.after(() => dom.window.close());
  assert.equal(doc.querySelectorAll('.custom-inline-log').length, 0);
  for (const id of ['stale', 'outside']) {
    doc.querySelector('a[data-custom-point="' + id + '"]').click(); await tick();
    assert.equal(doc.querySelectorAll('.navigation-target').length, 0);
    assert.equal(doc.getElementById('custom-navigation-note').hidden, false);
    assert.match(doc.getElementById('custom-navigation-note').textContent, /outside the report scope or no longer matches/);
  }
  assert.deepEqual(errors.map(e => e.message), []);
});

test('hostile message text remains inert in the panel and inline annotations', t => {
  const message = '</script><img src=x onerror="window.__executed=true"> $&';
  const { dom, doc, errors } = fixture({ points: [{ id: 'hostile', method: first, index: 1, statement: text, message, count: 1 }], hash: '#custom=hostile' });
  t.after(() => dom.window.close());
  assert.equal(doc.querySelector('.custom-inline-message').textContent, message);
  assert.equal(doc.querySelector('.custom-inline-kind').textContent, 'Statement marker');
  assert.equal(doc.querySelector('.custom-inline-status').textContent, 'Reached');
  assert.equal(doc.querySelector('.custom-point strong').textContent, message);
  assert.equal(doc.querySelector('.custom-inline-log img'), null);
  assert.equal(dom.window.__executed, undefined);
  assert.equal(doc.querySelector('.navigation-target').dataset.jimpleLine, '1');
  assert.deepEqual(errors.map(e => e.message), []);
});

test('variable templates show the latest sample safely in the panel and beside their statement', t => {
  const sample = '<img src=x onerror="window.__executed=true"> | value=42';
  const { dom, doc, errors } = fixture({ points: [{ id: 'variable', method: first, index: 1, statement: text,
    message: 'value={i3}', count: 3, samples: ['value=1', 'value=2', sample] }], hash: '#custom=variable' });
  t.after(() => dom.window.close());
  assert.equal(doc.querySelector('.custom-value-label').textContent, 'value={i3}');
  assert.equal(doc.querySelector('.custom-point strong').textContent, sample);
  assert.equal(doc.querySelector('.custom-value-caption').textContent, 'Latest value');
  assert.equal(doc.querySelector('.custom-value').textContent, sample);
  assert.equal(doc.querySelector('.custom-inline-value').textContent, 'Latest value: ' + sample);
  assert.match(doc.querySelector('.custom-inline-log').title, /value=1\nvalue=2/);
  assert.equal(doc.querySelector('.custom-inline-log img'), null); assert.equal(doc.querySelector('.custom-value img'), null);
  assert.equal(dom.window.__executed, undefined); assert.deepEqual(errors.map(e => e.message), []);
});

test('marker and value logs have distinct sections and honest missing-value states', t => {
  const { dom, doc, errors } = fixture({ points: [
    { id: 'marker', kind: 'marker', method: first, index: 1, statement: text, message: 'reached {r1}', count: 1, samples: ['must be ignored'] },
    { id: 'never', kind: 'value', method: first, index: 2, statement: text, message: '{i0}', count: 0, samples: ['must not show'] },
    { id: 'missing', kind: 'value', method: second, index: 1, statement: text, message: '{r1}', count: 2, samples: [] }
  ] }); t.after(() => dom.window.close());
  assert.deepEqual([...doc.querySelectorAll('.custom-group-heading')].map(el => el.textContent), ['Statement markers', 'Variable values']);
  const marker = doc.querySelector('[data-custom-point="marker"]');
  assert.equal(marker.querySelector('strong').textContent, 'reached {r1}'); assert.equal(marker.querySelector('.custom-status').textContent, 'Reached');
  assert.equal(marker.querySelector('.custom-value'), null);
  const never = doc.querySelector('[data-custom-point="never"]');
  assert.equal(never.querySelector('.custom-status').textContent, 'Not reached'); assert.equal(never.querySelector('.custom-value'), null);
  const missing = doc.querySelector('[data-custom-point="missing"]');
  assert.equal(missing.querySelector('.custom-status').firstChild.textContent, 'Reached · value not captured'); assert.equal(missing.querySelector('.custom-value'), null);
  assert.equal(doc.querySelector('[data-custom-point-id="missing"] .custom-inline-status').textContent, 'Reached · value not captured');
  assert.equal(doc.querySelector('[data-custom-point-id="never"] .custom-inline-status').textContent, 'Not reached');
  assert.equal(doc.querySelectorAll('.custom-inline-value').length, 0);
  assert.deepEqual(errors.map(e => e.message), []);
});

test('latest values preserve null and empty strings and honor legacy dynamic metadata', t => {
  const { dom, doc, errors } = fixture({ points: [
    { id: 'null', kind: 'value', method: first, index: 1, statement: text, message: '{r0}', status: 'covered', count: 2, samples: ['old', 'null'], latestValue: 'null' },
    { id: 'empty', dynamic: true, method: first, index: 2, statement: text, message: '{r1}', count: 1, samples: ['old'], latestValue: '' },
    { id: 'legacy-missing', dynamic: true, method: second, index: 1, statement: text, message: '{i0}', count: 1 }
  ] }); t.after(() => dom.window.close());
  assert.equal(doc.querySelector('[data-custom-point="null"] .custom-value').textContent, 'null');
  assert.equal(doc.querySelector('[data-custom-point="empty"] .custom-value').textContent, '(empty string)');
  assert.equal(doc.querySelector('[data-custom-point-id="empty"] .custom-inline-value').textContent, 'Latest value: (empty string)');
  assert.equal(doc.querySelector('[data-custom-point="legacy-missing"]').dataset.kind, 'value');
  assert.equal(doc.querySelector('[data-custom-point-id="legacy-missing"] .custom-inline-status').textContent, 'Reached · value not captured');
  assert.deepEqual(errors.map(e => e.message), []);
});
