'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { projectVersion, desktopVersion } = require('../src/version');
const root = path.resolve(__dirname, '../..');

test('development desktop version matches the Java engine and Maven artifact', () => {
  const constants = fs.readFileSync(path.join(root, 'src/main/java/com/jordansamhi/androlog/utils/Constants.java'), 'utf8');
  const javaVersion = /String VERSION = "([^"]+)"/.exec(constants)[1];
  assert.equal(projectVersion(root), javaVersion);
  assert.equal(desktopVersion({ isPackaged: false, root }), javaVersion);
});

test('packaged version uses the bundled engine metadata without a source checkout', t => {
  const resourcesPath = fs.mkdtempSync(path.join(os.tmpdir(), 'androlog-version-'));
  t.after(() => fs.rmSync(resourcesPath, { recursive: true, force: true }));
  fs.writeFileSync(path.join(resourcesPath, 'androlog-version.json'), JSON.stringify({ version: projectVersion(root) }));
  assert.equal(desktopVersion({ isPackaged: true, resourcesPath, root: '/nonexistent-checkout' }), projectVersion(root));
});
