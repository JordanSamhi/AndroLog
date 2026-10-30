#!/usr/bin/env node
'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../..');
const { Backend, hashFile } = require(path.join(root, 'electron-gui/src/backend'));
const out = path.resolve(process.env.DEMO_OUTPUT || path.join(root, 'target/sstic2027/password-demo'));
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk || !process.env.JAVA_HOME) throw Error('Set ANDROID_HOME and JAVA_HOME.');
const settings = { platforms: path.join(sdk, 'platforms'), java: path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') };
const jar = path.resolve(process.env.ANDROLOG_JAR || path.join(root, 'target/androlog-0.1-jar-with-dependencies.jar'));
const backend = new Backend({ jar, tempRoot: out });

(async () => {
  try {
    const points = JSON.parse(await fs.readFile(path.join(out, 'points.json'), 'utf8'));
    const summaries = [];
    for (const scenario of ['incorrect', 'correct']) {
      const dir = path.join(out, scenario);
      const capture = JSON.parse(await fs.readFile(path.join(dir, 'capture.json'), 'utf8'));
      const apk = path.join(out, 'instrumented.apk'), log = path.join(dir, 'runtime.log');
      assert.equal(await hashFile(apk), capture.instrumentedSha256);
      assert.equal(await hashFile(log), capture.logSha256);
      const result = await backend.analyze(apk, log, settings, { tag: 'SSTIC_PASSWORD' });
      const report = result.report, actual = report.custom.summary.points;
      const point = label => actual.find(p => p.id === points.find(source => source.label === label).id);
      assert.equal(actual.length, 4);
      assert.equal(report.custom.summary.observed, 3);
      assert.equal(report.custom.summary.unknown, 0);
      assert.equal(report.custom.unmatchedMarkers, 0);
      assert.equal(point('message').status, 'covered');
      assert.equal(point('mot de passe').latestValue, capture.expected.password);
      assert.equal(point('showSecret').status, capture.expected.allowed ? 'covered' : 'uncovered');
      assert.equal(point('showError').status, capture.expected.allowed ? 'uncovered' : 'covered');
      assert.ok(report.coverageSummary.statements.covered > 0, 'Automatic coverage must have runtime records too.');
      const expectedMethod = capture.expected.allowed ? 'showSecret' : 'showError';
      const edge = report.callGraph.edges.find(e => e.caller.includes('PasswordCheck: boolean check(')
        && e.callee.includes(`PasswordCheck: boolean ${expectedMethod}(`));
      assert.equal(edge?.kind, 'observed', `Expected a confirmed call to ${expectedMethod}`);
      await fs.copyFile(result.json, path.join(dir, 'coverage.json'));
      await fs.copyFile(result.html, path.join(dir, 'coverage.html'));
      const summary = {
        scenario, coverage: report.coverageSummary, logLines: capture.logLines,
        custom: { observed: report.custom.summary.observed, total: actual.length, unknown: report.custom.summary.unknown },
        password: point('mot de passe').latestValue, allowed: capture.expected.allowed,
        showSecret: point('showSecret').status, showError: point('showError').status,
        confirmedCallEdge: edge, graphStats: report.callGraph.stats
      };
      summaries.push(summary);
      console.log(JSON.stringify(summary));
    }
    await fs.writeFile(path.join(out, 'validation.json'), JSON.stringify({ validatedAt: new Date().toISOString(), jarSha256: await hashFile(jar), scenarios: summaries }, null, 2) + '\n');
  } finally { await backend.shutdown(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
