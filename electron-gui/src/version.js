'use strict';
const fs = require('node:fs');
const path = require('node:path');

// The desktop package has its own build number. Show the Java engine's version
// from Maven, and carry it into standalone packages that do not contain a POM.
function projectVersion(root) {
  const pom = fs.readFileSync(path.join(root, 'pom.xml'), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
  const version = /<artifactId>androlog<\/artifactId>\s*<version>([^<]+)<\/version>/.exec(pom)?.[1].trim();
  if (!version || !/^[0-9][A-Za-z0-9.+-]*$/.test(version)) throw new Error('Cannot read the AndroLog version from pom.xml.');
  return version;
}

function desktopVersion({ isPackaged, resourcesPath, root }) {
  return isPackaged
    ? JSON.parse(fs.readFileSync(path.join(resourcesPath, 'androlog-version.json'), 'utf8')).version
    : projectVersion(root);
}

module.exports = { projectVersion, desktopVersion };
