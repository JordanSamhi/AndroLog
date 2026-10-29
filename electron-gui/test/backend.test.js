'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { javaProperty, validatePlan, validateOptions, copySafely, hashFile } = require('../src/backend');

test('signing paths are safe Java properties, including Windows and unicode', () => {
  assert.equal(javaProperty('C:\\SDK Tools\\a:b=é'), 'C\\:\\\\SDK\\ Tools\\\\a\\:b\\=\\u00e9');
  assert.equal(javaProperty('x\ny😀'), 'x\\ny\\ud83d\\ude00');
});
test('custom points reject duplicate, invalid and overlong messages', () => {
  const valid = { apkSha256: 'a'.repeat(64), points: [{ method: '<A: void x()>', index: 1, statement: 'return', message: 'hello' }] };
  assert.doesNotThrow(() => validatePlan(valid));
  assert.throws(() => validatePlan({ ...valid, points: [valid.points[0], valid.points[0]] }), /one message/);
  assert.throws(() => validatePlan({ ...valid, points: [{ ...valid.points[0], message: 'a\nb' }] }), /single-line/);
  assert.throws(() => validatePlan({ ...valid, points: [{ ...valid.points[0], message: 'é'.repeat(501) }] }), /1,000/);
});
test('invalid probe scopes and tags are rejected', () => {
  const valid = { probes: [true, false, false, false, false], tag: 'ANDROLOG', packagePrefix: 'org.example' };
  assert.doesNotThrow(() => validateOptions(valid));
  assert.throws(() => validateOptions({ ...valid, tag: 'a b' }), /tag/);
  assert.throws(() => validateOptions({ ...valid, packagePrefix: 'a;exit' }), /package/);
  assert.throws(() => validateOptions({ ...valid, probes: [true] }), /probe/);
});
test('saving never replaces original inputs or a symlink', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androlog-copy-test-')); t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const input = path.join(dir, 'original.apk'), source = path.join(dir, 'output.apk'), target = path.join(dir, 'saved.apk');
  await fs.writeFile(input, 'original'); await fs.writeFile(source, 'instrumented');
  await assert.rejects(copySafely(source, input, [input]), /preserve/);
  if (process.platform !== 'win32') { const alias = path.join(dir, 'alias.apk'); await fs.symlink(input, alias); await assert.rejects(copySafely(source, alias, [input]), /symbolic/); const hard = path.join(dir, 'hard.apk'); await fs.link(input, hard); await assert.rejects(copySafely(source, hard, [input]), /preserve/); }
  await copySafely(source, target, [input]); assert.equal(await fs.readFile(target, 'utf8'), 'instrumented'); assert.equal(await fs.readFile(input, 'utf8'), 'original');
  assert.match(await hashFile(input), /^[a-f0-9]{64}$/);
});

test('backend passes paths as arguments without evaluating shell syntax', { skip: process.platform === 'win32' }, async t => {
  const { Backend } = require('../src/backend');
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androlog-process-test-')); t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const executable = path.join(dir, 'fake-java'), jar = path.join(dir, 'backend.jar'), touched = path.join(dir, 'SHOULD_NOT_EXIST');
  await fs.writeFile(jar, 'jar'); await fs.writeFile(executable, '#!/bin/sh\nprintf "%s\\n" "$@"\nprintf "@@ANDROLOG_STAGE 2\\n"\n'); await fs.chmod(executable, 0o755);
  const events = []; const backend = new Backend({ jar, tempRoot: dir, emit: e => events.push(e) });
  const malicious = `literal; touch ${touched}`;
  const output = await backend.run({ java: executable }, ['-a', malicious]);
  assert.ok(output.includes(malicious)); await assert.rejects(fs.stat(touched), { code: 'ENOENT' });
  assert.ok(events.some(event => event.type === 'stage' && event.stage === 2));
});

test('cancelling stops the Java process and reports cancellation', { skip: process.platform === 'win32', timeout: 5000 }, async t => {
  const { Backend } = require('../src/backend');
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androlog-cancel-test-')); t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const executable = path.join(dir, 'fake-java'), jar = path.join(dir, 'backend.jar');
  await fs.writeFile(jar, 'jar'); await fs.writeFile(executable, '#!/bin/sh\nprintf "@@ANDROLOG_STAGE 1\\n"\nsleep 30\n'); await fs.chmod(executable, 0o755);
  let signalReady; const ready = new Promise(resolve => { signalReady = resolve; });
  const backend = new Backend({ jar, emit: event => { if (event.type === 'stage') signalReady(); } });
  const operation = backend.run({ java: executable }, []); await ready; backend.cancel();
  await assert.rejects(operation, /cancelled/);
});

test('custom message summaries use the exact tag and count repeated records', async t => {
  const { readCustomMessages } = require('../src/backend');
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androlog-messages-test-')); t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'runtime.log'); await fs.writeFile(file, [
    '10-29 10:00:00.123 123 123 I LOG.v1: CUSTOM=reached this point',
    'I/LOG.v1( 123): CUSTOM=reached this point',
    'LOG.v1: CUSTOM=METHOD=literal',
    'I/LOGXv1( 123): CUSTOM=wrong tag',
    'LOG.v1: METHOD=not a custom message',
  ].join('\n'));
  const result = await readCustomMessages(file, 'LOG.v1');
  assert.equal(result.total, 3); assert.deepEqual(result.messages, [{ message: 'reached this point', count: 2 }, { message: 'METHOD=literal', count: 1 }]);
  assert.equal(result.omitted, 0);
});

test('portable coverage reads probes from the instrumented APK without instrumentation settings', async t => {
  const { Backend } = require('../src/backend');
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androlog-baseline-log-')); t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const platforms = path.join(dir, 'platforms'); await fs.mkdir(path.join(platforms, 'android-test'), { recursive: true });
  await fs.writeFile(path.join(platforms, 'android-test', 'android.jar'), 'fixture');
  const apk = path.join(dir, 'input.apk'), log = path.join(dir, 'runtime.log'); await fs.writeFile(apk, 'apk'); await fs.writeFile(log, '');
  const backend = new Backend({ jar: 'unused', tempRoot: dir }); t.after(() => backend.shutdown());
  backend.run = async (_settings, args) => {
    assert.ok(args.includes('--instrumented-input'));
    for (const flag of ['-c','-m','-s','-cp','-mc','-pkg','-n','--custom-report-only']) assert.equal(args.includes(flag), false);
    assert.equal(args[args.indexOf('-a') + 1], apk);
    await fs.writeFile(args[args.indexOf('-j') + 1], JSON.stringify({ coverageSummary: {} }));
    await fs.writeFile(args[args.indexOf('-html') + 1], '<html></html>');
  };
  const result = await backend.analyze(apk, log, { platforms }, { tag: 'ANDROLOG', probes: [true,true,true,true,true], packagePrefix: 'different.app', excludeLibraries: true });
  assert.deepEqual(result.report.coverageSummary, {});
});

test('variable plans accept only explicit supported template bindings and leave literal braces alone', () => {
  const point = { method: '<A: void x()>', index: 1, statement: 'return', message: 'value={i0}, text={$r1}', variables: [{ name: 'i0', type: 'int' }, { name: '$r1', type: 'java.lang.String' }] };
  const plan = value => ({ apkSha256: 'a'.repeat(64), points: [value] });
  assert.doesNotThrow(() => validatePlan(plan(point)));
  assert.doesNotThrow(() => validatePlan(plan({ ...point, variables: undefined })));
  for (const type of ['byte', 'short', 'int', 'long', 'float', 'double', 'boolean', 'java.lang.String', 'java.lang.Object', 'e.a.h', 'a', 'pkg.Outer$Inner', 'exemple.Élément', 'a-b', 'int[]', 'char[]', 'java.lang.String[][]', 'a' + '[]'.repeat(255)]) assert.doesNotThrow(() => validatePlan(plan({ ...point, variables: [{ name: 'i0', type }] })), type);
  for (const variables of [null, []]) assert.doesNotThrow(() => validatePlan(plan({ ...point, variables })));
  for (const variables of [false, {}, 'i0']) assert.throws(() => validatePlan(plan({ ...point, variables })), /variables/);
  for (const name of ['', 'two names', 'bad\nname', '{i0}', 'x'.repeat(201)]) assert.throws(() => validatePlan(plan({ ...point, variables: [{ name, type: 'int' }] })), /Insert variable/);
  for (const type of [null, undefined, 1, '', 'char', 'void', 'void[]', 'null', 'null_type', 'unknown', 'bottom_type', 'address', '[]', '[Ljava.lang.Object;', 'pkg..Type', '.Type', 'pkg.', 'pkg/Type', 'pkg:Type', 'pkg Type', 'pkg\nType', 'pkg\0Type', 'pkg\x7fType', 'pkg.<Type>', 'pkg{Type}', 'pkg(Type)', 'a' + '[]'.repeat(256), 'a'.repeat(1025)]) assert.throws(() => validatePlan(plan({ ...point, variables: [{ name: 'i0', type }] })), /Insert variable/, String(type));
  assert.throws(() => validatePlan(plan({ ...point, variables: [{ name: 'i0', type: 'int' }, { name: 'i0', type: 'int' }] })), /only be listed once/);
  assert.throws(() => validatePlan(plan({ ...point, variables: [{ name: 'other', type: 'int' }] })), /missing/);
});

test('custom message summaries include tagged variable values without counting their marker twice', async t => {
  const { readCustomMessages } = require('../src/backend');
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androlog-variable-messages-')); t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'runtime.log'), id = 'a'.repeat(32);
  await fs.writeFile(file, [`TEST: CUSTOM_VALUE=${id}|score=42`, `TEST: CUSTOM_POINT=${id}`, `OTHER: CUSTOM_VALUE=${id}|wrong tag`, 'TEST: CUSTOM_VALUE=invalid|wrong id', 'TEST: CUSTOM=literal', `TEST: CUSTOM_VALUE=${id}|unicode=one\u2028two\u2029three`].join('\n'));
  const summary = await readCustomMessages(file, 'TEST');
  assert.equal(summary.total, 3); assert.equal(summary.markers[id], 1);
  assert.deepEqual(summary.messages, [{ message: 'score=42', count: 1 }, { message: 'literal', count: 1 }, { message: 'unicode=one\u2028two\u2029three', count: 1 }]);
});
