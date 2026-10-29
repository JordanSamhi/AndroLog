'use strict';

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { CodeBrowserSession } = require('./code-browser');

const FLAGS = ['-c', '-m', '-s', '-cp', '-mc'];
const TAG = /^[A-Za-z0-9_][A-Za-z0-9_.-]*$/;
const DEVICE = /^[^\s\x00-\x1f\x7f]{1,512}$/;
const CAPTURE_CLASS = 'com.jordansamhi.androlog.DeviceCapture';
const CAPTURE_STATES = new Set(['preparing', 'installing', 'launching', 'recording', 'stopping']);
const PACKAGE = /^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*\.?$/;
const LOCAL_TYPES = new Set(['byte', 'short', 'int', 'long', 'float', 'double', 'boolean', 'java.lang.String']);

function supportedLocalType(type) {
  // Keep aligned with LocalVariableAvailability.supported and the renderer.
  if (typeof type !== 'string' || !type || type.length > 1024) return false;
  let base = type, dimensions = 0;
  while (base.endsWith('[]')) { base = base.slice(0, -2); if (++dimensions > 255) return false; }
  if (LOCAL_TYPES.has(base)) return true;
  if (base === 'char') return dimensions > 0;
  if (['void', 'null', 'null_type', 'unknown', 'bottom_type', 'address'].includes(base)) return false;
  return /^[^\s\u0000-\u001f\u007f\[\];/(){}<>:.]+(?:\.[^\s\u0000-\u001f\u007f\[\];/(){}<>:.]+)*$/u.test(base);
}

function javaProperty(value) {
  return [...value].map(c => {
    if (c === '\\') return '\\\\';
    if (c === '\n') return '\\n';
    if (c === '\r') return '\\r';
    if (c === '=' || c === ':' || c === ' ' || c === '#' || c === '!') return '\\' + c;
    const code = c.codePointAt(0);
    if (code > 0xffff) { const n = code - 0x10000; return `\\u${(0xd800 + (n >> 10)).toString(16)}\\u${(0xdc00 + (n & 1023)).toString(16)}`; }
    return code > 126 || code < 32 ? `\\u${code.toString(16).padStart(4, '0')}` : c;
  }).join('');
}
async function hashFile(file) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256'); const stream = fs.createReadStream(file);
    stream.on('data', chunk => h.update(chunk)); stream.on('error', reject); stream.on('end', () => resolve(h.digest('hex')));
  });
}
async function regularFile(file, what) {
  if (typeof file !== 'string' || !file) throw new Error(`Choose ${what}.`);
  let stat; try { stat = await fsp.stat(file); await fsp.access(file, fs.constants.R_OK); } catch { throw new Error(`Cannot read ${what}.`); }
  if (!stat.isFile()) throw new Error(`Choose a file for ${what}.`);
  return path.resolve(file);
}
async function validateSettings(settings, signing) {
  if (!settings || typeof settings !== 'object') throw new Error('Open Settings and select your Android SDK.');
  const platforms = settings.platforms;
  let dirs; try { dirs = await fsp.readdir(platforms, { withFileTypes: true }); } catch { throw new Error('Choose the Android SDK platforms folder in Settings.'); }
  const jars = await Promise.all(dirs.filter(d => d.isDirectory()).map(async d => {
    try { return (await fsp.stat(path.join(platforms, d.name, 'android.jar'))).isFile(); } catch { return false; }
  }));
  if (!jars.some(Boolean)) throw new Error('The platforms folder needs an installed Android platform containing android.jar.');
  if (signing) {
    const windows = process.platform === 'win32';
    for (const name of [windows ? 'zipalign.exe' : 'zipalign', windows ? 'apksigner.bat' : 'apksigner']) {
      try { const p = path.join(settings.buildTools, name); if (!(await fsp.stat(p)).isFile()) throw new Error(); if (!windows) await fsp.access(p, fs.constants.X_OK); }
      catch { throw new Error(`Choose an Android SDK Build Tools version containing ${name} in Settings.`); }
    }
  }
}
function validateOptions(opts) {
  if (!opts || !TAG.test(opts.tag || '')) throw new Error('The log tag may contain letters, numbers, dots, underscores and hyphens.');
  if (opts.packagePrefix && !PACKAGE.test(opts.packagePrefix)) throw new Error('Enter a valid package prefix or leave it empty.');
  if (!Array.isArray(opts.probes) || opts.probes.length !== FLAGS.length || !opts.probes.every(v => typeof v === 'boolean')) throw new Error('Invalid probe selection.');
}
function validatePlan(plan) {
  if (!plan || !/^[a-f0-9]{64}$/.test(plan.apkSha256 || '') || !Array.isArray(plan.points) || !plan.points.length) throw new Error('Browse the APK and select at least one custom point.');
  const seen = new Set();
  for (const point of plan.points) {
    if (!point || typeof point.method !== 'string' || !point.method.trim() || !Number.isSafeInteger(point.index) || point.index < 1 || typeof point.statement !== 'string' || !point.statement.trim()) throw new Error('A custom log point is incomplete.');
    if (typeof point.message !== 'string' || !point.message.trim() || /[\n\r\0]/.test(point.message) || Buffer.byteLength(point.message, 'utf8') > 1000) throw new Error('Use a nonempty single-line message of at most 1,000 UTF-8 bytes.');
    if (point.kind != null && !['marker', 'value'].includes(point.kind)) throw new Error('Choose a statement marker or a variable value log.');
    if (point.variables != null) {
      if (!Array.isArray(point.variables)) throw new Error('The custom log variables are invalid. Browse the statement again.');
      const variables = new Set();
      for (const variable of point.variables) {
        if (!variable || typeof variable.name !== 'string' || !/^[^\s{}\u0000-\u001f\u007f]{1,200}$/u.test(variable.name) || !supportedLocalType(variable.type)) throw new Error('Choose a supported local variable from Insert variable.');
        if (variables.has(variable.name)) throw new Error('A custom log variable may only be listed once.');
        if (!point.message.includes(`{${variable.name}}`)) throw new Error('A selected variable is missing from the custom log message.');
        variables.add(variable.name);
      }
    }
    if (point.kind === 'marker' && point.variables?.length) throw new Error('Statement markers use a literal message and cannot bind variables.');
    if (point.kind === 'value' && (point.variables?.length !== 1 || point.message !== `{${point.variables[0].name}}`)) throw new Error('A variable value log must select exactly one variable without additional message text.');
    const key = `${point.method}\n${point.index}`; if (seen.has(key)) throw new Error('Only one message can be attached to a statement.'); seen.add(key);
  }
}
async function readCustomMessages(file, tag, limit = 1000) {
  file = await regularFile(file, 'the runtime log');
  if (!TAG.test(tag || '')) throw new Error('Enter the same valid Logcat tag used during instrumentation.');
  const escaped = tag.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const timestamp = '(?:\\d{4}-)?\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?';
  const tagged = new RegExp(`^(?:(?:${timestamp}\\s+\\d+\\s+\\d+\\s+[VDIWEFA]\\s+)?${escaped}\\s*:\\s*|(?:${timestamp}\\s+)?[VDIWEFA]/${escaped}(?:\\s*\\(\\s*\\d+\\))?\\s*:\\s*)(?:CUSTOM=|CUSTOM_VALUE=[a-f0-9]{32}\\|)(.*)$`, 's');
  const markerPattern = new RegExp(`^(?:(?:${timestamp}\\s+\\d+\\s+\\d+\\s+[VDIWEFA]\\s+)?${escaped}\\s*:\\s*|(?:${timestamp}\\s+)?[VDIWEFA]/${escaped}(?:\\s*\\(\\s*\\d+\\))?\\s*:\\s*)CUSTOM_POINT=([a-f0-9]{32})$`);
  const markers = {}; const messages = new Map(); let total = 0, omitted = 0;
  const consume = line => {
    const marker = markerPattern.exec(line); if (marker) { markers[marker[1]] = (markers[marker[1]] || 0) + 1; return; }
    const match = tagged.exec(line); if (!match) return;
    total++; const message = match[1];
    if (messages.has(message)) messages.set(message, messages.get(message) + 1);
    else if (messages.size < limit) messages.set(message, 1);
    else omitted++;
  };
  // Logcat separates records with CR/LF; Unicode separators may belong to a logged string.
  let pending = '';
  for await (const chunk of fs.createReadStream(file, { encoding: 'utf8' })) {
    const lines = (pending + chunk).split(/\r\n|\r|\n/); pending = lines.pop();
    for (const line of lines) consume(line);
  }
  if (pending) consume(pending);
  return { total, omitted, markers, messages: [...messages].map(([message, count]) => ({ message, count })) };
}
async function copySafely(source, destination, protectedFiles = []) {
  const target = path.resolve(destination); const parent = await fsp.realpath(path.dirname(target));
  const canonical = path.join(parent, path.basename(target));
  let targetStat = null; try { targetStat = await fsp.lstat(target); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  if (targetStat?.isSymbolicLink()) throw new Error('Choose a regular file, not a symbolic link.');
  for (const file of protectedFiles.filter(Boolean)) {
    const protectedReal = await fsp.realpath(file);
    const normal = name => process.platform === 'win32' ? name.toLowerCase() : name;
    if (normal(canonical) === normal(protectedReal) || (targetStat && normal(await fsp.realpath(target)) === normal(protectedReal))) throw new Error('Choose a different destination to preserve the input file.');
    if (targetStat) {
      const p = await fsp.stat(file); if (p.ino && targetStat.ino && p.dev === targetStat.dev && p.ino === targetStat.ino) throw new Error('Choose a different destination to preserve the input file.');
    }
  }
  const temporary = path.join(parent, `.androlog-${crypto.randomUUID()}.tmp`);
  try { await fsp.copyFile(source, temporary, fs.constants.COPYFILE_EXCL); await fsp.rename(temporary, target); }
  finally { await fsp.rm(temporary, { force: true }); }
}

function createOperation(interactive = false) {
  return { child: null, cancelled: false, interactive, state: null, stopRequested: false, failure: null };
}

class Backend {
  constructor({ jar, tempRoot, defaultJava, emit = () => {} }) { this.jar = jar; this.tempRoot = tempRoot; this.defaultJava = defaultJava; this.emit = emit; this.child = null; this.active = null; this.workspaces = new Set(); this.codeBrowser = null; this.codeBrowserQueue = Promise.resolve(); this.codeBrowserGeneration = 0; }
  async run(settings, args, { config = false, workDir, label = 'Working', mainClass = 'com.jordansamhi.androlog.Main', interactive = false, onLine, timeoutMs = 0, reservation } = {}) {
    if (this.active && this.active !== reservation) throw new Error('Another operation is already running.');
    const operation = reservation || createOperation(interactive);
    this.active = operation;
    try {
      await this.closeCodeBrowser();
      await regularFile(this.jar, 'the AndroLog backend JAR');
      const java = settings.java || this.defaultJava || (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java');
      let classpath = this.jar;
      if (config) {
        const dir = path.join(workDir, 'config'); await fsp.mkdir(dir, { recursive: true });
        const win = process.platform === 'win32';
        const zip = path.join(settings.buildTools, win ? 'zipalign.exe' : 'zipalign');
        const sign = path.join(settings.buildTools, win ? 'apksigner.bat' : 'apksigner');
        await fsp.writeFile(path.join(dir, 'config.properties'), `zipalignPath=${javaProperty(zip)}\napksignerPath=${javaProperty(sign)}\n`, { mode: 0o600 });
        classpath = dir + path.delimiter + this.jar;
      }
      if (operation.cancelled) throw new Error('Operation cancelled.');
      const javaArgs = ['-Djava.awt.headless=true', '-Dandrolog.gui.progress=true', '-cp', classpath, mainClass, ...args];
      const env = { ...process.env };
      if (path.isAbsolute(java)) { env.JAVA_HOME = path.dirname(path.dirname(java)); env.PATH = path.dirname(java) + path.delimiter + (env.PATH || ''); }
      this.emit({ type: 'start', label });
      return await new Promise((resolve, reject) => {
        const child = spawn(java, javaArgs, { env, windowsHide: true, stdio: [interactive ? 'pipe' : 'ignore', 'pipe', 'pipe'], detached: process.platform !== 'win32' });
        operation.child = child; this.child = child;
        let output = '';
        const pending = { stdout: '', stderr: '' };
        const fail = error => {
          if (this.active !== operation || operation.failure) return;
          operation.failure = error; this.cancel();
        };
        const lineReceived = line => {
          if (operation.failure) return;
          try {
            const m = /^@@ANDROLOG_STAGE (\d+)/.exec(line); if (m) this.emit({ type: 'stage', stage: Number(m[1]) });
            if (onLine) onLine(line, operation);
          } catch (error) { fail(error); }
        };
        const consume = stream => chunk => {
          const text = chunk.toString(); output = (output + text).slice(-200000); pending[stream] += text;
          const lines = pending[stream].split(/\r?\n/); pending[stream] = lines.pop();
          for (const line of lines) lineReceived(line);
          this.emit({ type: 'log', text: text.slice(-12000) });
        };
        child.stdout.setEncoding('utf8'); child.stderr.setEncoding('utf8');
        child.stdout.on('data', consume('stdout')); child.stderr.on('data', consume('stderr'));
        child.stdin?.on('error', error => {
          if (!operation.cancelled) fail(new Error(`Cannot stop device capture: ${error.message}`));
        });
        const timer = timeoutMs > 0 ? setTimeout(() => fail(new Error(`${label} timed out. Check the device connection and try again.`)), timeoutMs) : null;
        timer?.unref();
        child.on('error', e => { clearTimeout(timer); reject(new Error(`Could not start Java: ${e.message}. Set a Java 17+ executable in Settings.`)); });
        child.on('close', (code, signal) => {
          clearTimeout(timer);
          for (const line of Object.values(pending)) if (line) lineReceived(line);
          if (operation.failure) return reject(operation.failure);
          if (operation.cancelled) return reject(new Error('Operation cancelled.'));
          if (code !== 0) { const errors = output.match(/^Error[^\r\n]*/gm); return reject(new Error(errors?.at(-1) || `${label} failed (${signal ? 'signal ' + signal : 'exit ' + code}). Open run details for the full log.`)); }
          resolve(output);
        });
      });
    } finally {
      if (this.active === operation) { this.child = null; this.active = null; }
    }
  }
  async stopCapture() {
    const operation = this.active;
    if (!operation?.interactive || !operation.child) throw new Error('No device capture is running.');
    if (operation.stopRequested) return;
    if (operation.state !== 'recording' || operation.cancelled) throw new Error('Wait until the app is recording before stopping.');
    operation.stopRequested = true;
    await new Promise((resolve, reject) => {
      operation.child.stdin.write('\n', error => error ? reject(new Error(`Cannot stop device capture: ${error.message}`)) : resolve());
    });
  }
  cancel() {
    if (!this.active) { void this.closeCodeBrowser(); return; }
    if (this.active) this.active.cancelled = true;
    if (!this.child) return;
    const active = this.child;
    try {
      if (process.platform === 'win32') {
        const killer = spawn('taskkill', ['/pid', String(active.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
        killer.once('error', () => active.kill()); killer.unref();
      }
      else process.kill(-active.pid, 'SIGTERM');
    } catch { active.kill(); }
    if (process.platform !== 'win32') {
      // A signing child may have been forked as cancellation started and can
      // keep the pipes open after its parent exits. Reap the process group too.
      const escalation = setTimeout(() => {
        if (this.child !== active) return;
        try { process.kill(-active.pid, 'SIGKILL'); } catch { try { active.kill('SIGKILL'); } catch { } }
      }, 500);
      escalation.unref();
    }
  }
  async workspace() { const dir = await fsp.mkdtemp(path.join(this.tempRoot || os.tmpdir(), 'androlog-electron-')); this.workspaces.add(dir); return dir; }
  async shutdown() {
    await this.closeCodeBrowser();
    const active = this.child; this.cancel();
    if (active) await new Promise(resolve => {
      const timer = setTimeout(resolve, 2500); timer.unref();
      active.once('close', () => { clearTimeout(timer); resolve(); });
    });
    await Promise.allSettled([...this.workspaces].map(dir => fsp.rm(dir, { recursive: true, force: true })));
    this.workspaces.clear();
  }
  async discard(dir) { await fsp.rm(dir, { recursive: true, force: true }); this.workspaces.delete(dir); }
  async listDevices(settings) {
    await validateSettings(settings, false);
    let devices;
    await this.run(settings, ['--platforms', settings.platforms, '--list'], {
      mainClass: CAPTURE_CLASS, label: 'Finding devices', timeoutMs: 25000,
      onLine: line => {
        if (!line.startsWith('@@ANDROLOG_DEVICES ')) return;
        let parsed;
        try { parsed = JSON.parse(line.slice('@@ANDROLOG_DEVICES '.length)); } catch { throw new Error('The backend returned an invalid device list.'); }
        if (!Array.isArray(parsed) || parsed.some(device => !device || typeof device.serial !== 'string' || !DEVICE.test(device.serial) || typeof device.name !== 'string' || typeof device.state !== 'string')) throw new Error('The backend returned an invalid device list.');
        devices = parsed;
      }
    });
    if (!devices) throw new Error('The backend did not return a device list. Rebuild AndroLog and try again.');
    return devices;
  }
  async capture(apk, settings, opts, sessionRoot) {
    if (this.active) throw new Error('Another operation is already running.');
    // Reserve before any await: cancelling during APK validation/copying must
    // prevent a later install, just as cancelling the Java process does.
    const operation = createOperation(true); this.active = operation;
    const checkCancelled = () => { if (operation.cancelled) throw new Error('Operation cancelled.'); };
    let session;
    try {
      apk = await regularFile(apk, 'the instrumented APK'); checkCancelled();
      await validateSettings(settings, false); checkCancelled();
      if (!opts || !TAG.test(opts.tag || '')) throw new Error('Enter the same valid Logcat tag used during instrumentation.');
      if (typeof opts.device !== 'string' || !DEVICE.test(opts.device) || opts.device.startsWith('-')) throw new Error('Choose a connected device.');
      if (typeof sessionRoot !== 'string' || !path.isAbsolute(sessionRoot)) throw new Error('Choose a valid capture storage folder.');
      await fsp.mkdir(sessionRoot, { recursive: true, mode: 0o700 }); checkCancelled();
      const stamp = new Date().toISOString().replace(/[:.]/g, '-');
      const dir = await fsp.mkdtemp(path.join(sessionRoot, `capture-${stamp}-`));
      // Captures are permanent user data, deliberately absent from workspaces.
      session = { dir, apk: path.join(dir, path.basename(apk)), log: path.join(dir, 'runtime.log'), tag: opts.tag };
      checkCancelled();
      const originalHash = await hashFile(apk); checkCancelled();
      await fsp.copyFile(apk, session.apk, fs.constants.COPYFILE_EXCL); checkCancelled();
      const [snapshotHash, currentHash] = await Promise.all([hashFile(session.apk), hashFile(apk)]); checkCancelled();
      if (originalHash !== snapshotHash || originalHash !== currentHash) throw new Error('The instrumented APK changed while preparing the test. Start again.');
      await fsp.writeFile(path.join(dir, 'session.json'), JSON.stringify({ apk: path.basename(session.apk), log: 'runtime.log', tag: opts.tag, device: opts.device, apkSha256: originalHash, startedAt: new Date().toISOString() }, null, 2), { mode: 0o600 });
      checkCancelled();
      this.emit({ type: 'log', text: `Saved session files: ${dir}\n` });
      let recordingSeen = false;
      await this.run(settings, ['--platforms', settings.platforms, '--apk', session.apk, '--device', opts.device, '--tag', opts.tag, '--output', session.log], {
        mainClass: CAPTURE_CLASS, interactive: true, label: 'Testing on device', reservation: operation,
        onLine: line => {
          if (!line.startsWith('@@ANDROLOG_CAPTURE ')) return;
          let update;
          try { update = JSON.parse(line.slice('@@ANDROLOG_CAPTURE '.length)); } catch { throw new Error('The backend returned an invalid capture status.'); }
          if (!update || !CAPTURE_STATES.has(update.state)) throw new Error('The backend returned an invalid capture status.');
          operation.state = update.state;
          if (update.state === 'recording') recordingSeen = true;
          this.emit({ type: 'capture', state: update.state, ...session });
        }
      });
      if (!recordingSeen || !operation.stopRequested) throw new Error('Device capture ended before Stop & analyze was requested.');
      await regularFile(session.log, 'the captured runtime log');
      return session;
    } catch (error) {
      throw new Error(error.message + (session ? ` Saved session files: ${session.dir}` : ''));
    } finally {
      if (this.active === operation) { this.child = null; this.active = null; }
    }
  }
  async closeCodeBrowser() {
    this.codeBrowserGeneration++;
    const browser = this.codeBrowser; this.codeBrowser = null;
    if (browser) { await browser.session.close(); await this.discard(browser.directory); }
  }
  browse(apk, settings, reset, action) {
    const generation = this.codeBrowserGeneration;
    const task = this.codeBrowserQueue.then(async () => {
      const cancelled = () => { if (generation !== this.codeBrowserGeneration) throw new Error('Operation cancelled.'); };
      cancelled();
      if (this.active) throw new Error('Another operation is already running.');
      apk = await regularFile(apk, 'the original APK'); await validateSettings(settings, false);
      await regularFile(this.jar, 'the AndroLog backend JAR');
      const apkSha256 = await hashFile(apk), jarStat = await fsp.stat(this.jar);
      const java = settings.java || this.defaultJava || (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java');
      const key = JSON.stringify([apk, apkSha256, settings.platforms, java, this.jar, jarStat.size, jarStat.mtimeMs]);
      cancelled();
      if (this.codeBrowser && (this.codeBrowser.key !== key || this.codeBrowser.session.closed)) {
        const previous = this.codeBrowser; this.codeBrowser = null;
        await previous.session.close(); await this.discard(previous.directory);
        if (!reset) throw new Error('The APK or code browser changed. Close Your own messages and open it again.');
      }
      cancelled();
      if (!this.codeBrowser) {
        if (!reset) throw new Error('The code browser is closed. Open Your own messages again.');
        const directory = await this.workspace();
        try {
          cancelled();
          this.emit({ type: 'start', label: 'Reading APK packages' });
          const session = new CodeBrowserSession({ java, jar: this.jar, apk, platforms: settings.platforms, directory, emit: this.emit });
          this.codeBrowser = { key, directory, session, id: crypto.randomUUID(), outline: null };
        } catch (error) { await this.discard(directory); throw error; }
      }
      const browser = this.codeBrowser;
      try {
        const outline = await browser.session.ready;
        cancelled();
        if (!outline || outline.apkSha256 !== apkSha256 || !outline.lazy || !Array.isArray(outline.methods)) {
          await browser.session.close();
          throw new Error('The APK changed or its code index is incomplete. Open Your own messages again.');
        }
        if (!browser.outline) browser.outline = { ...outline, sessionId: browser.id };
        return await action(browser.session, browser.outline);
      } catch (error) {
        // Java permanently invalidates a session once it observes changed APK bytes,
        // even if the original file is restored before our next hash check.
        const staleApk = error.message?.startsWith('The APK changed while browsing its code.');
        if (this.codeBrowser === browser && (!browser.outline || browser.session.closed || staleApk)) { this.codeBrowser = null; await browser.session.close(); await this.discard(browser.directory); }
        throw error;
      }
    });
    this.codeBrowserQueue = task.catch(() => {});
    return task;
  }
  inspect(apk, settings) { return this.browse(apk, settings, true, (_session, outline) => outline); }
  inspectClass(apk, settings, className) {
    if (typeof className !== 'string' || !className || className.length > 4096) return Promise.reject(new Error('Choose a class from the package tree.'));
    return this.browse(apk, settings, false, session => session.request('class', { className }));
  }
  inspectVariables(apk, settings, method) {
    if (typeof method !== 'string' || !method || method.length > 16384) return Promise.reject(new Error('Choose a method from the code browser.'));
    return this.browse(apk, settings, false, session => session.request('variables', { method }));
  }
  async instrument(apk, settings, opts, plan) {
    apk = await regularFile(apk, 'an APK'); await validateSettings(settings, true); validateOptions(opts);
    if (!opts.probes.some(Boolean) && !plan?.points?.length) throw new Error('Choose at least one automatic probe or custom log point.');
    const before = await hashFile(apk);
    if (plan) { validatePlan(plan); if (plan.apkSha256 !== before) throw new Error('The APK changed. Browse its code again.'); }
    const dir = await this.workspace(), outDir = path.join(dir, 'output'); await fsp.mkdir(outDir);
    try {
      const args = ['-a', apk, '-p', settings.platforms, '-o', outDir, '-l', opts.tag];
      opts.probes.forEach((v, i) => { if (v) args.push(FLAGS[i]); });
      if (opts.excludeLibraries) args.push('-n'); if (opts.packagePrefix) args.push('-pkg', opts.packagePrefix);
      if (plan) { const planPath = path.join(dir, 'points.json'); await fsp.writeFile(planPath, JSON.stringify(plan), { mode: 0o600 }); args.push('--custom-logs', planPath); }
      await this.run(settings, args, { workDir: dir, config: true, label: 'Instrumenting APK' });
      if (before !== await hashFile(apk)) throw new Error('The original APK changed during instrumentation.');
      const output = path.join(outDir, path.basename(apk)); await regularFile(output, 'the instrumented APK');
      return { dir, output, input: apk, apkHash: before };
    } catch (e) { await this.discard(dir); throw e; }
  }
  async analyze(apk, log, settings, opts) {
    apk = await regularFile(apk, 'the instrumented APK'); log = await regularFile(log, 'the runtime log');
    await validateSettings(settings, false);
    if (!opts || !TAG.test(opts.tag || '')) throw new Error('Enter the same valid Logcat tag used during instrumentation.');
    const [apkHash, logHash] = await Promise.all([hashFile(apk), hashFile(log)]);
    const dir = await this.workspace(), json = path.join(dir, 'coverage.json'), html = path.join(dir, 'coverage.html');
    try {
      const args = ['-a', apk, '-p', settings.platforms, '-o', dir, '-pam', log, '-l', opts.tag, '-j', json, '-html', html, '--instrumented-input'];
      await this.run(settings, args, { workDir: dir, label: 'Analyzing coverage' });
      if (apkHash !== await hashFile(apk) || logHash !== await hashFile(log)) throw new Error('The APK or runtime log changed during analysis. Run it again.');
      const report = JSON.parse(await fsp.readFile(json, 'utf8'));
      if (!report.coverageSummary) throw new Error('The backend did not produce a coverage summary.');
      return { dir, json, html, report, apk, log, apkHash, logHash };
    } catch (e) { await this.discard(dir); throw e; }
  }
}
module.exports = { Backend, javaProperty, hashFile, validateOptions, validatePlan, validateSettings, copySafely, readCustomMessages };
