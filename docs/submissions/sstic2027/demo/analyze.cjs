#!/usr/bin/env node
'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../..');
const { Backend, hashFile } = require(path.join(root, 'electron-gui/src/backend'));
const out = path.resolve(process.env.DEMO_OUTPUT || path.join(root, 'target/sstic2027/demo'));
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk || !process.env.JAVA_HOME) throw Error('Set ANDROID_HOME and JAVA_HOME.');
const settings = { platforms: path.join(sdk, 'platforms'), java: path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') };
const jar = path.resolve(process.env.ANDROLOG_JAR || path.join(root, 'target/androlog-0.1-jar-with-dependencies.jar'));
const backend = new Backend({ jar, tempRoot: out });

(async () => {
  try {
    const points = JSON.parse(await fs.readFile(path.join(out, 'points.json'), 'utf8'));
    const summaries = [];
    for (const scenario of ['accepted', 'rejected', 'deny-policy']) {
      const dir = path.join(out, scenario);
      const capture = JSON.parse(await fs.readFile(path.join(dir, 'capture.json'), 'utf8'));
      const apk = path.join(out, 'instrumented.apk'), log = path.join(dir, 'runtime.log');
      assert.equal(await hashFile(apk), capture.instrumentedSha256);
      assert.equal(await hashFile(log), capture.logSha256);
      const result = await backend.analyze(apk, log, settings, { tag: 'SSTIC_DEMO' });
      const report = result.report, actual = report.custom.summary.points;
      const point = label => actual.find(p => p.id === points.find(source => source.label === label).id);
      assert.equal(actual.length, 6);
      assert.equal(report.custom.summary.observed, 5);
      assert.equal(report.custom.summary.unknown, 0);
      assert.equal(report.custom.unmatchedMarkers, 0);
      assert.equal(point('entrée').status, 'covered');
      assert.equal(point('message').status, 'covered');
      assert.equal(point('URL normalisée').latestValue, capture.expected.normalized);
      assert.equal(point('décision').latestValue, String(capture.expected.allowed));
      assert.equal(point('accepted').status, capture.expected.allowed ? 'covered' : 'uncovered');
      assert.equal(point('rejected').status, capture.expected.allowed ? 'uncovered' : 'covered');
      assert.ok(report.coverageSummary.statements.covered > 0, 'Automatic coverage must have runtime records too.');
      const edge = report.callGraph.edges.find(e => e.caller.includes('UrlAudit: java.lang.String inspect(')
        && e.callee === `<com.example.andrologsstic.${capture.expected.policy}: boolean allow(java.lang.String)>`);
      assert.equal(edge?.kind, 'observed');
      assert.ok(edge.callSites.some(s => s.kind === 'interface' && s.declared.includes('UrlPolicy: boolean allow(')));
      await fs.copyFile(result.json, path.join(dir, 'coverage.json'));
      await fs.copyFile(result.html, path.join(dir, 'coverage.html'));
      const summary = {
        scenario, coverage: report.coverageSummary, logLines: capture.logLines,
        custom: { observed: report.custom.summary.observed, total: actual.length, unknown: report.custom.summary.unknown },
        normalized: point('URL normalisée').latestValue, allowed: point('décision').latestValue,
        accepted: point('accepted').status, rejected: point('rejected').status,
        confirmedInterfaceEdge: edge, graphStats: report.callGraph.stats
      };
      summaries.push(summary);
      console.log(JSON.stringify(summary));
    }
    await fs.writeFile(path.join(out, 'validation.json'), JSON.stringify({ validatedAt: new Date().toISOString(), jarSha256: await hashFile(jar), scenarios: summaries }, null, 2) + '\n');
  } finally { await backend.shutdown(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
