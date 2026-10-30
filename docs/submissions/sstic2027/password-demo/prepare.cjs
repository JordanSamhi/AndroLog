#!/usr/bin/env node
'use strict';
// Exercise the same Java backend as the desktop GUI. No mock data is used.
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../..');
const { Backend, hashFile } = require(path.join(root, 'electron-gui/src/backend'));
const { preparePlan } = require(path.join(root, 'electron-gui/src/custom-report'));
const out = path.resolve(process.env.DEMO_OUTPUT || path.join(root, 'target/sstic2027/password-demo'));
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
    const code = await backend.inspectClass(apk, settings, 'com.example.andrologpassword.PasswordCheck');
    const method = name => code.methods.find(m => m.signature.includes(` ${name}(`));
    const inspect = method('check');
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
    const comparison = pick(inspect, t => t.includes('java.lang.String: boolean equals(') && t.includes('sstic2027'));
    add('message', inspect, pick(inspect, t => t.startsWith('if ')), 'message', { message: 'Vérification du mot de passe' });
    const variables = await backend.inspectVariables(apk, settings, inspect.signature);
    const argument = comparison.text.match(/virtualinvoke (\S+)\.</)[1];
    const local = variables.locals.find(v => v.name === argument && v.type === 'java.lang.String');
    assert.ok(local, 'The password must be a local string.');
    const mask = Buffer.from(variables.statements.find(s => s.index === comparison.index).localMask, 'base64');
    const position = variables.locals.indexOf(local);
    assert.ok(mask[position >> 3] & (1 << (position & 7)), 'The string must be initialized at the selected point.');
    add('mot de passe', inspect, comparison, 'value', { message: `{${local.name}}`, variables: [local] });
    for (const name of ['showSecret', 'showError']) {
      const m = method(name);
      add(name, m, m.statements.find(s => s.selectable), 'reached');
    }
    const plan = preparePlan({ apkSha256: catalog.apkSha256, points }, 'SSTIC_PASSWORD');
    await write('custom-plan.json', plan);
    await write('points.json', plan.points.map((p, i) => ({ label: labels[i], ...p })));
    await write('audit-code.json', code);
    await write('variables.json', variables);
    const result = await backend.instrument(apk, settings, {
      tag: 'SSTIC_PASSWORD', probes: [true, true, true, true, true], packagePrefix: '', excludeLibraries: false
    }, plan);
    const instrumented = path.join(out, 'instrumented.apk');
    await fs.copyFile(result.output, instrumented);
    await write('build-evidence.json', {
      producedAt: new Date().toISOString(),
      jarSha256: await hashFile(jar), originalSha256: await hashFile(apk), instrumentedSha256: await hashFile(instrumented),
      package: 'com.example.andrologpassword', tag: plan.tag,
      automaticProbes: ['classes', 'methods', 'statements', 'components', 'dynamic calls'], customPoints: points.length
    });
    console.log(`\nPrepared ${instrumented}`);
  } finally { await backend.shutdown(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
