#!/usr/bin/env node
'use strict';
// Exercise the same Java backend as the desktop GUI. No mock data is used.
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../..');
const { Backend, hashFile } = require(path.join(root, 'electron-gui/src/backend'));
const { preparePlan } = require(path.join(root, 'electron-gui/src/custom-report'));
const out = path.resolve(process.env.DEMO_OUTPUT || path.join(root, 'target/sstic2027/demo'));
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk || !process.env.JAVA_HOME) throw Error('Set ANDROID_HOME and JAVA_HOME.');
const settings = {
  platforms: path.join(sdk, 'platforms'),
  buildTools: path.join(sdk, 'build-tools', process.env.BUILD_TOOLS || '36.1.0'),
  java: path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
};
const jar = path.resolve(process.env.ANDROLOG_JAR || path.join(root, 'target/androlog-0.1-jar-with-dependencies.jar'));
const backend = new Backend({ jar, tempRoot: out, emit: e => { if (e.type === 'log') process.stdout.write(e.text); } });
const write = (file, value) => fs.writeFile(path.join(out, file), JSON.stringify(value, null, 2) + '\n');

(async () => {
  try {
    await fs.mkdir(out, { recursive: true });
    const apk = path.join(out, 'original.apk');
    const catalog = await backend.inspect(apk, settings);
    const code = await backend.inspectClass(apk, settings, 'com.example.andrologsstic.UrlAudit');
    const method = name => code.methods.find(m => m.signature.includes(` ${name}(`));
    const inspect = method('inspect');
    const pick = (m, predicate) => {
      const hits = m.statements.filter(s => s.selectable && predicate(s.text));
      assert.equal(hits.length, 1, 'The demo statement must have a unique match.');
      return hits[0];
    };
    const points = [], labels = [];
    const add = (label, m, statement, kind, extra = {}) => {
      labels.push(label);
      points.push({ kind, method: m.signature, index: statement.index, statement: statement.text, ...extra });
    };
    add('entrée', inspect, pick(inspect, t => t.includes(' normalize(')), 'reached');
    add('message', inspect, pick(inspect, t => t.includes(' choosePolicy(')), 'message', { message: 'Vérification de l’URL normalisée' });
    const call = pick(inspect, t => t.includes('interfaceinvoke') && t.includes(' allow('));
    const variables = await backend.inspectVariables(apk, settings, inspect.signature);
    const argument = call.text.match(/>\(([^)]+)\)$/)[1];
    const local = variables.locals.find(v => v.name === argument && v.type === 'java.lang.String');
    assert.ok(local, 'The policy argument must be a local string.');
    const mask = Buffer.from(variables.statements.find(s => s.index === call.index).localMask, 'base64');
    const position = variables.locals.indexOf(local);
    assert.ok(mask[position >> 3] & (1 << (position & 7)), 'The string must be initialized at the selected point.');
    add('URL normalisée', inspect, call, 'value', { message: `{${local.name}}`, variables: [local] });
    const decision = pick(inspect, t => t.startsWith('if '));
    const boolName = decision.text.match(/^if (\S+) /)[1];
    const bool = variables.locals.find(v => v.name === boolName && v.type === 'boolean');
    assert.ok(bool, 'The decision must be a local boolean.');
    add('décision', inspect, decision, 'value', { message: `{${bool.name}}`, variables: [bool] });
    for (const name of ['accepted', 'rejected']) {
      const m = method(name);
      add(name, m, m.statements.find(s => s.selectable), 'reached');
    }
    const plan = preparePlan({ apkSha256: catalog.apkSha256, points }, 'SSTIC_DEMO');
    await write('custom-plan.json', plan);
    await write('points.json', plan.points.map((p, i) => ({ label: labels[i], ...p })));
    await write('audit-code.json', code);
    await write('variables.json', variables);
    const result = await backend.instrument(apk, settings, {
      tag: 'SSTIC_DEMO', probes: [true, true, true, true, true], packagePrefix: '', excludeLibraries: false
    }, plan);
    const instrumented = path.join(out, 'instrumented.apk');
    await fs.copyFile(result.output, instrumented);
    await write('build-evidence.json', {
      producedAt: new Date().toISOString(),
      jarSha256: await hashFile(jar), originalSha256: await hashFile(apk), instrumentedSha256: await hashFile(instrumented),
      package: 'com.example.andrologsstic', tag: plan.tag,
      automaticProbes: ['classes', 'methods', 'statements', 'components', 'dynamic calls'], customPoints: points.length
    });
    console.log(`\nPrepared ${instrumented}`);
  } finally { await backend.shutdown(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
