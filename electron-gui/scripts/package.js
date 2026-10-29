'use strict';
// Build a runtime for the host; distribution bundles must be built on each target OS/architecture.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const args = process.argv.slice(2);
const platformFlags = { '--mac': 'darwin', '-m': 'darwin', '--win': 'win32', '-w': 'win32', '--linux': 'linux', '-l': 'linux' };
for (const flag of args) {
  const platform = Object.keys(platformFlags).find(key => flag === key || flag.startsWith(key + '='));
  if (platform && platformFlags[platform] !== process.platform) throw new Error('Build on the target OS so the bundled Java runtime matches the app.');
  if ((flag === '--x64' && process.arch !== 'x64') || (flag === '--arm64' && process.arch !== 'arm64') || flag === '--universal' || flag === '--ia32') throw new Error('Build on the target architecture so the bundled Java runtime matches the app.');
}
const jar = path.join(root, 'target', 'androlog-0.1-jar-with-dependencies.jar');
if (!fs.existsSync(jar)) throw new Error('Build the Java backend first with mvn package.');
const bin = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : '';
const tool = name => bin ? path.join(bin, process.platform === 'win32' ? name + '.exe' : name) : name;
const javaVersion = spawnSync(tool('java'), ['-XshowSettings:properties', '-version'], { encoding: 'utf8' });
const match = /version\s+"(?:1\.)?(\d+)/.exec((javaVersion.stderr || '') + (javaVersion.stdout || ''));
if (javaVersion.error || !match || Number(match[1]) < 17) throw new Error('Use a JDK 17 or newer with jlink. Set JAVA_HOME to the JDK.');
const archMatch = /\bos\.arch\s*=\s*(\S+)/.exec((javaVersion.stderr || '') + (javaVersion.stdout || ''));
const runtimeArch = archMatch && ({ aarch64: 'arm64', arm64: 'arm64', amd64: 'x64', x86_64: 'x64' })[archMatch[1].toLowerCase()];
if (runtimeArch !== process.arch) throw new Error('The JDK architecture must match Node and Electron; select the matching JDK with JAVA_HOME.');
// Each build has its own runtime and app staging directory, outside cloud-synced folders.
const staging = fs.mkdtempSync(path.join(os.tmpdir(), 'androlog-electron-build-'));
const destination = path.join(staging, 'runtime');
console.log('Preparing a Java runtime for', process.platform, process.arch);
const link = spawnSync(tool('jlink'), ['--add-modules', 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs', '--strip-debug', '--no-header-files', '--no-man-pages', '--output', destination], { stdio: 'inherit' });
if (link.error || link.status !== 0) { fs.rmSync(staging, { recursive: true, force: true }); throw new Error('Could not build the Java runtime. Use a full JDK with jlink.'); }
const builder = require.resolve('electron-builder/cli.js');
const buildOutput = path.join(staging, 'output');
const config = JSON.parse(fs.readFileSync(path.join(root, 'electron-gui', 'package.json'), 'utf8')).build;
config.directories.output = buildOutput;
config.extraResources = [{ from: jar, to: 'androlog.jar' }, { from: destination, to: 'runtime' }];
if (process.platform === 'darwin' && !process.env.CSC_NAME && !process.env.CSC_LINK && !args.some(arg => arg.includes('mac.identity'))) {
  config.mac.identity = '-';
  console.log('Using an ad-hoc macOS signature for local testing. A Developer ID and notarization are needed for distribution.');
}
const configFile = path.join(staging, 'builder-config.json'); fs.writeFileSync(configFile, JSON.stringify(config, null, 2));
const builderArgs = ['--config', configFile, ...args];
const build = spawnSync(process.execPath, [builder, ...builderArgs], { cwd: path.join(root, 'electron-gui'), stdio: 'inherit' });
if (build.error) { fs.rmSync(staging, { recursive: true, force: true }); throw build.error; }
if (build.status === 0) {
  if (args.includes('--dir')) { fs.rmSync(destination, { recursive: true, force: true }); console.log(`App image: ${buildOutput}`); }
  else {
    const output = path.join(root, 'target', 'electron'); fs.mkdirSync(output, { recursive: true });
    for (const name of fs.readdirSync(buildOutput)) {
      if (/\.(zip|tar\.gz|AppImage|exe|dmg|deb|rpm)$/.test(name)) {
        fs.copyFileSync(path.join(buildOutput, name), path.join(output, name)); console.log(`Package: ${path.join(output, name)}`);
      }
    }
    fs.rmSync(staging, { recursive: true, force: true });
  }
} else fs.rmSync(staging, { recursive: true, force: true });
process.exitCode = build.status ?? 1;
