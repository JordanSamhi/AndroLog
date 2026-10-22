'use strict';

const assert = require('node:assert/strict');
const links = require('../../main/resources/report-links.js');

const first = '<com.foo.A: void run(int)>';
const second = '<com.foo.A: void run(java.lang.String)>';
const constructor = '<com.foo.A: void <init>()>';
const initializer = '<com.foo.A: void <clinit>()>';
const innerMethod = '<com.foo.Outer$Inner: com.foo.A get(com.foo.A[])>';
const unicodeMethod = '<例.Δοκιμή: void μέθοδος()>';
const index = links.createIndex([
  { name: 'com.foo.A', methods: [first, second, constructor, initializer].map(signature => ({ signature })) },
  { name: 'com.foo.Outer$Inner', methods: [{ signature: innerMethod }] },
  { name: '例.Δοκιμή', methods: [{ signature: unicodeMethod }] },
  { name: 'Widget', methods: [{ signature: '<Widget: void <init>()>' }] },
]);

function targets(text) {
  const tokens = links.tokenize(text, index);
  assert.equal(tokens.map(token => token.text).join(''), text, 'Tokenization must preserve every character');
  return tokens.filter(token => token.className !== undefined);
}

for (const signature of [first, second, constructor, initializer, innerMethod, unicodeMethod]) {
  const result = targets('specialinvoke $r0.' + signature + '($i1)');
  assert.equal(result.length, 1);
  assert.equal(result[0].text, signature);
  assert.equal(result[0].methodSignature, signature, 'Must link the exact overload/constructor');
}

for (const text of [
  '$r0 = new com.foo.A',
  '$r0 = newarray (com.foo.A)[3]',
  '$r0 = newmultiarray (com.foo.A)[3][4]',
  '$r0 = (com.foo.A[]) $r1',
  '$z0 = $r0 instanceof com.foo.A',
  'com.foo.A',
]) {
  const result = targets(text);
  assert.equal(result.length, 1, text);
  assert.equal(result[0].className, 'com.foo.A');
  assert.equal(result[0].methodSignature, undefined);
}

assert.equal(targets('$r0 = new com.foo.Outer$Inner')[0].className, 'com.foo.Outer$Inner');
assert.equal(targets('$r0 = (例.Δοκιμή) $r1')[0].className, '例.Δοκιμή');
assert.equal(targets('$r0 = <com.foo.A: com.foo.A field>').length, 2, 'Field owner and known field type should link');

const absentMethod = targets('virtualinvoke $r0.<com.foo.A: void missing(java.lang.String)>("value")');
assert.equal(absentMethod.length, 1);
assert.equal(absentMethod[0].className, 'com.foo.A');
assert.equal(absentMethod[0].methodSignature, undefined, 'Do not invent a method target');
assert.deepEqual(targets('staticinvoke <java.lang.Thread: java.lang.Thread currentThread()>()'), []);

for (const text of [
  'com.foo.AA', 'com.foo.A.Foo', 'com.foo.A$Unknown', 'other.com.foo.A',
  '.com.foo.A', '0com.foo.A', 'com.foo.Aé',
  '"com.foo.A"', "'com.foo.A'", '"' + first + '"',
  String.raw`"escaped \" com.foo.A still quoted"`,
  String.raw`'escaped \' com.foo.A still quoted'`,
  '"unterminated com.foo.A',
]) assert.deepEqual(targets(text), [], text);

assert.equal(targets(String.raw`"quoted \\"; $r0 = new com.foo.A`).length, 1, 'Even backslashes must not hide the ending quote');
assert.equal(targets('"com.foo.A" + new com.foo.A').length, 1);

assert.deepEqual(targets('Widget = Widget + 1'), [], 'Do not confuse default-package classes with bare local variables');
assert.deepEqual(targets('staticinvoke <com.foo.A: void absent(java.lang.Object)>(Widget)'), [
  { text: 'com.foo.A', className: 'com.foo.A' },
], 'A method argument is not a default-package type reference');
assert.deepEqual(targets('call(Widget)'), [], 'Parenthesized local variables are not casts');
for (const text of ['new Widget', 'newarray (Widget)[2]', '(Widget[]) $r0', '$r0 instanceof Widget', '@this: Widget']) {
  assert.equal(targets(text).length, 1, text);
}
assert.equal(targets('<Widget: int missing()>')[0].className, 'Widget');

const classHash = links.href('例.Outer$Inner');
assert.equal(classHash, '#class=' + encodeURIComponent('例.Outer$Inner'));
assert.equal(links.href('com.foo.A', constructor), '#class=com.foo.A&method=' + encodeURIComponent(constructor));
assert.equal(new URLSearchParams(links.href('com.foo.A', first).slice(1)).get('method'), first);

// A tiny DOM substitute checks that code never becomes HTML and anchors only
// receive known targets. No browser dependency is needed for these pure tests.
const doc = {
  createTextNode(text) { return { nodeType: 3, textContent: text }; },
  createElement(tag) {
    return {
      tagName: tag,
      attributes: {},
      setAttribute(name, value) { this.attributes[name] = value; },
      set innerHTML(value) { throw new Error('innerHTML must never be used'); },
    };
  },
};
const container = { ownerDocument: doc, children: [], appendChild(node) { this.children.push(node); } };
const hostile = '"</script><img src=x onerror=alert(1)> com.foo.A"; new com.foo.A; ' + constructor;
links.appendCode(container, hostile, index);
assert.equal(container.children.map(node => node.textContent).join(''), hostile);
const anchors = container.children.filter(node => node.tagName === 'a');
assert.equal(anchors.length, 2);
assert.equal(anchors[0].attributes['data-code-class'], 'com.foo.A');
assert.equal(anchors[0].attributes['data-code-method'], undefined);
assert.equal(anchors[1].attributes['data-code-method'], constructor);
assert.equal(anchors[1].attributes.href, links.href('com.foo.A', constructor));

assert.deepEqual(links.tokenize('', index), []);
assert.deepEqual(links.tokenize('plain', null), [{ text: 'plain' }]);
console.log('report-links: all assertions passed');
