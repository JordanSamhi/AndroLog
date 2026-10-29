'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM, VirtualConsole } = require('jsdom');
const resources = path.join(__dirname, '../../src/main/resources');
const tick = () => new Promise(resolve => setTimeout(resolve, 10));

function report() {
  const data = { appName: 'Fixture', categories: [{ key: 'classes', label: 'Classes', total: 2, covered: 1 }], classes: [
    { name: 'example.app.First', packageName: 'example.app', classCovered: true, methods: [
      { signature: '<example.app.First: void start()>', covered: true, statements: [{ line: 1, text: 'return', status: 'excluded' }] }
    ] },
    { name: 'example.app.Second', packageName: 'example.app', classCovered: false, methods: [
      { signature: '<example.app.Second: void run(int)>', covered: true, statements: [{ line: 1, text: 'return', status: 'excluded' }] },
      { signature: '<example.app.Second: void run(java.lang.String)>', covered: false, statements: [{ line: 1, text: 'return', status: 'excluded' }] },
      { signature: '<example.app.Second: void <init>()>', covered: null, statements: [] }
    ] }
  ], callGraph: { available: false } };
  const html = fs.readFileSync(path.join(resources, 'report.html'), 'utf8')
    .replace('__ANDROLOG_DATA__', JSON.stringify(data))
    .replace('__ANDROLOG_LINKS__', fs.readFileSync(path.join(resources, 'report-links.js'), 'utf8'))
    .replace('__ANDROLOG_GRAPH__', fs.readFileSync(path.join(resources, 'report-graph.js'), 'utf8'));
  const errors = []; const virtualConsole = new VirtualConsole(); virtualConsole.on('jsdomError', error => errors.push(error));
  const dom = new JSDOM(html, { url: 'https://report.invalid/', runScripts: 'dangerously', pretendToBeVisual: true, virtualConsole });
  return { dom, errors, doc: dom.window.document };
}

test('selecting a class expands its methods and method links jump to the exact overload', async t => {
  const { dom, errors, doc } = report(); t.after(() => dom.window.close());
  assert.equal(doc.querySelectorAll('.class-methods').length, 1);
  assert.match(doc.querySelector('.class-methods').getAttribute('aria-label'), /First/);
  const second = [...doc.querySelectorAll('.class-choice')].find(link => link.dataset.codeClass === 'example.app.Second');
  assert.equal(second.getAttribute('aria-expanded'), 'false'); second.click(); await tick();
  assert.equal(doc.querySelectorAll('.class-methods').length, 1);
  assert.match(doc.querySelector('.class-methods').getAttribute('aria-label'), /Second/);
  assert.equal(doc.querySelectorAll('.method-choice').length, 3);
  const targetSignature = '<example.app.Second: void run(java.lang.String)>';
  const target = [...doc.querySelectorAll('.method-choice')].find(link => link.dataset.codeMethod === targetSignature);
  assert.equal(target.textContent, 'ƒvoid run(java.lang.String)'); assert.ok(target.classList.contains('uncovered'));
  target.click(); await tick();
  assert.equal(new URLSearchParams(dom.window.location.hash.slice(1)).get('method'), targetSignature);
  assert.equal(doc.querySelector('.navigation-target').dataset.methodSignature, targetSignature);
  assert.equal(doc.activeElement.dataset.methodSignature, targetSignature);
  assert.equal(doc.querySelector('.method-choice[aria-current="location"]').dataset.codeMethod, targetSignature);
  assert.equal(doc.querySelectorAll('tr.method-declaration').length, 3, 'Method navigation retains the whole class');
  assert.match(doc.querySelectorAll('.method-choice')[2].textContent, /<init>/);
  assert.deepEqual(errors.map(e => e.message), []);
});

test('nested package collapse and class counts remain class-based after method expansion', t => {
  const { dom, errors, doc } = report(); t.after(() => dom.window.close());
  assert.match(doc.getElementById('class-results').textContent, /^2 classes shown/);
  const rootPackage = doc.querySelector('.package-toggle'); rootPackage.click();
  assert.equal(rootPackage.getAttribute('aria-expanded'), 'false');
  assert.equal(doc.querySelectorAll('.method-choice').length, 0);
  rootPackage.click();
  assert.equal(doc.querySelectorAll('.class-choice').length, 2);
  assert.equal(doc.querySelectorAll('.method-choice').length, 1);
  assert.deepEqual(errors.map(e => e.message), []);
});
