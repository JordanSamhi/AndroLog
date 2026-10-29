'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM, VirtualConsole } = require('jsdom');
const resources = path.join(__dirname, '../../src/main/resources');

test('class and method declarations carry their own coverage status', () => {
  const clazz = { name: 'example.Main', packageName: 'example', classCovered: true, componentType: null, componentCovered: null,
    methods: [
      { signature: '<example.Main: void seen()>', covered: true, statements: [] },
      { signature: '<example.Main: void unseen()>', covered: false, statements: [] },
      { signature: '<example.Main: void unmeasured()>', covered: null, statements: [] }
    ] };
  const data = { appName: 'Fixture', categories: [{ key: 'classes', label: 'Classes', total: 1, covered: 1 }, { key: 'methods', label: 'Methods', total: 2, covered: 1 }], classes: [clazz], callGraph: { nodes: [], edges: [] } };
  const html = fs.readFileSync(path.join(resources, 'report.html'), 'utf8')
    .replace('__ANDROLOG_DATA__', JSON.stringify(data))
    .replace('__ANDROLOG_LINKS__', fs.readFileSync(path.join(resources, 'report-links.js'), 'utf8'))
    .replace('__ANDROLOG_GRAPH__', fs.readFileSync(path.join(resources, 'report-graph.js'), 'utf8'));
  const errors = []; const console = new VirtualConsole(); console.on('jsdomError', error => errors.push(error));
  const dom = new JSDOM(html, { url: 'https://report.invalid/', runScripts: 'dangerously', pretendToBeVisual: true, virtualConsole: console });
  try {
    assert.deepEqual(errors.map(e => e.message), []);
    const doc = dom.window.document;
    assert.ok(doc.querySelector('tr.class-declaration.covered'));
    assert.ok(doc.querySelector('a.class-choice.covered'));
    const declarations = doc.querySelectorAll('tr.method-declaration');
    assert.equal(declarations.length, 3);
    assert.equal(declarations[0].getAttribute('data-status'), 'covered');
    assert.equal(declarations[1].getAttribute('data-status'), 'uncovered');
    assert.equal(declarations[2].getAttribute('data-status'), 'unmeasured');
    assert.ok(declarations[0].classList.contains('covered'));
    assert.ok(declarations[1].classList.contains('uncovered'));
    assert.ok(!declarations[2].classList.contains('covered'));
    assert.ok(declarations[0].textContent.includes('Method probe: Covered'));
  } finally { dom.window.close(); }
});
