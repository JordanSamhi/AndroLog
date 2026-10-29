'use strict';

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const readline = require('node:readline');
const { spawn } = require('node:child_process');

const FLAGS = ['-c', '-m', '-s', '-cp', '-mc'];
const TAG = /^[A-Za-z0-9_][A-Za-z0-9_.-]*$/;
const PACKAGE = /^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*\.?$/;

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
    const key = `${point.method}\n${point.index}`; if (seen.has(key)) throw new Error('Only one message can be attached to a statement.'); seen.add(key);
  }
}
async function readCustomMessages(file, tag, limit = 1000) {
  file = await regularFile(file, 'the runtime log');
  if (!TAG.test(tag || '')) throw new Error('Enter the same valid Logcat tag used during instrumentation.');
  const escaped = tag.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const timestamp = '(?:\\d{4}-)?\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?';
  const tagged = new RegExp(`^(?:(?:${timestamp}\\s+\\d+\\s+\\d+\\s+[VDIWEFA]\\s+)?${escaped}\\s*:\\s*|(?:${timestamp}\\s+)?[VDIWEFA]/${escaped}(?:\\s*\\(\\s*\\d+\\))?\\s*:\\s*)CUSTOM=(.*)$`);
  const markerPattern = new RegExp(`^(?:(?:${timestamp}\\s+\\d+\\s+\\d+\\s+[VDIWEFA]\\s+)?${escaped}\\s*:\\s*|(?:${timestamp}\\s+)?[VDIWEFA]/${escaped}(?:\\s*\\(\\s*\\d+\\))?\\s*:\\s*)CUSTOM_POINT=([a-f0-9]{32})$`);
  const markers = {}; const messages = new Map(); let total = 0, omitted = 0;
  const lines = readline.createInterface({ input: fs.createReadStream(file, { encoding: 'utf8' }), crlfDelay: Infinity });
  for await (const line of lines) {
    const marker = markerPattern.exec(line); if (marker) { markers[marker[1]] = (markers[marker[1]] || 0) + 1; continue; }
    const match = tagged.exec(line); if (!match) continue;
    total++; const message = match[1];
    if (messages.has(message)) messages.set(message, messages.get(message) + 1);
    else if (messages.size < limit) messages.set(message, 1);
    else omitted++;
  }
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

class Backend {
  constructor({ jar, tempRoot, defaultJava, emit = () => {} }) { this.jar = jar; this.tempRoot = tempRoot; this.defaultJava = defaultJava; this.emit = emit; this.child = null; this.active = null; this.workspaces = new Set(); }
  async run(settings, args, { config = false, workDir, label = 'Working' } = {}) {
    if (this.child) throw new Error('Another operation is already running.');
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
    const javaArgs = ['-Djava.awt.headless=true', '-Dandrolog.gui.progress=true', '-cp', classpath, 'com.jordansamhi.androlog.Main', ...args];
    const env = { ...process.env };
    if (path.isAbsolute(java)) { env.JAVA_HOME = path.dirname(path.dirname(java)); env.PATH = path.dirname(java) + path.delimiter + (env.PATH || ''); }
    this.emit({ type: 'start', label });
    return new Promise((resolve, reject) => {
      const child = spawn(java, javaArgs, { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], detached: process.platform !== 'win32' });
      const operation = { child, cancelled: false }; this.child = child; this.active = operation; let output = '', pending = '';
      const consume = chunk => {
        const text = chunk.toString(); output = (output + text).slice(-200000); pending += text;
        const lines = pending.split(/\r?\n/); pending = lines.pop();
        for (const line of lines) { const m = /^@@ANDROLOG_STAGE (\d+)/.exec(line); if (m) this.emit({ type: 'stage', stage: Number(m[1]) }); }
        this.emit({ type: 'log', text: text.slice(-12000) });
      };
      child.stdout.on('data', consume); child.stderr.on('data', consume);
      child.on('error', e => { if (this.active === operation) { this.child = null; this.active = null; } reject(new Error(`Could not start Java: ${e.message}. Set a Java 17+ executable in Settings.`)); });
      child.on('close', (code, signal) => {
        if (this.active === operation) { this.child = null; this.active = null; }
        if (operation.cancelled) return reject(new Error('Operation cancelled.'));
        if (code !== 0) { const errors = output.match(/^Error[^\r\n]*/gm); return reject(new Error(errors?.at(-1) || `${label} failed (${signal ? 'signal ' + signal : 'exit ' + code}). Open run details for the full log.`)); }
        resolve(output);
      });
    });
  }
  cancel() {
    if (!this.child) return;
    if (this.active) this.active.cancelled = true;
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
    const active = this.child; this.cancel();
    if (active) await new Promise(resolve => {
      const timer = setTimeout(resolve, 2500); timer.unref();
      active.once('close', () => { clearTimeout(timer); resolve(); });
    });
    await Promise.allSettled([...this.workspaces].map(dir => fsp.rm(dir, { recursive: true, force: true })));
    this.workspaces.clear();
  }
  async discard(dir) { await fsp.rm(dir, { recursive: true, force: true }); this.workspaces.delete(dir); }
  async inspect(apk, settings) {
    apk = await regularFile(apk, 'the original APK'); await validateSettings(settings, false);
    const dir = await this.workspace(), output = path.join(dir, 'catalog.json');
    try { await this.run(settings, ['-a', apk, '-p', settings.platforms, '-o', dir, '--inspect-code', output], { workDir: dir, label: 'Reading APK code' }); return JSON.parse(await fsp.readFile(output, 'utf8')); }
    finally { await this.discard(dir); }
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
  async analyze(apk, log, settings, opts, customOnly = false) {
    apk = await regularFile(apk, 'the original APK'); log = await regularFile(log, 'the runtime log');
    await validateSettings(settings, false); validateOptions(opts);
    if (!opts.probes.some(Boolean) && !customOnly) throw new Error('Select the probes used when instrumenting the APK or choose a custom log build.');
    const [apkHash, logHash] = await Promise.all([hashFile(apk), hashFile(log)]);
    const dir = await this.workspace(), json = path.join(dir, 'coverage.json'), html = path.join(dir, 'coverage.html');
    try {
      const args = ['-a', apk, '-p', settings.platforms, '-o', dir, '-pam', log, '-l', opts.tag, '-j', json, '-html', html];
      opts.probes.forEach((v, i) => { if (v) args.push(FLAGS[i]); });
      if (!opts.probes.some(Boolean) && customOnly) args.push('--custom-report-only');
      if (opts.excludeLibraries) args.push('-n'); if (opts.packagePrefix) args.push('-pkg', opts.packagePrefix);
      await this.run(settings, args, { workDir: dir, label: 'Analyzing coverage' });
      if (apkHash !== await hashFile(apk) || logHash !== await hashFile(log)) throw new Error('The APK or runtime log changed during analysis. Run it again.');
      const report = JSON.parse(await fsp.readFile(json, 'utf8'));
      if (!report.coverageSummary) throw new Error('The backend did not produce a coverage summary.');
      return { dir, json, html, report, apk, log, apkHash, logHash };
    } catch (e) { await this.discard(dir); throw e; }
  }
}
module.exports = { Backend, javaProperty, hashFile, validateOptions, validatePlan, validateSettings, copySafely, readCustomMessages };
