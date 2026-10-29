'use strict';

const { spawn } = require('node:child_process');
const path = require('node:path');

const PREFIX = '@@ANDROLOG_CODE ';

/** One APK, one JVM. Requests are serialized; an idle session does no analysis. */
class CodeBrowserSession {
  constructor({ java, jar, apk, platforms, directory, emit = () => {}, timeoutMs = 120000 }) {
    this.emit = emit; this.timeoutMs = timeoutMs; this.nextId = 1; this.pending = new Map();
    this.closed = false; this.queue = Promise.resolve(); this.inFlight = 0;
    const env = { ...process.env };
    if (path.isAbsolute(java)) { env.JAVA_HOME = path.dirname(path.dirname(java)); env.PATH = path.dirname(java) + path.delimiter + (env.PATH || ''); }
    this.ready = this.waitFor(0); this.ready.catch(() => {});
    this.child = spawn(java, ['-Djava.awt.headless=true', '-cp', jar, 'com.jordansamhi.androlog.CodeBrowser', '--apk', apk, '--platforms', platforms, '--output', directory], {
      env, windowsHide: true, detached: process.platform !== 'win32', stdio: ['pipe', 'pipe', 'pipe']
    });
    this.exited = new Promise(resolve => this.child.once('close', resolve));
    let stdout = '';
    this.child.stdout.setEncoding('utf8'); this.child.stderr.setEncoding('utf8');
    this.child.stdout.on('data', text => {
      stdout += text;
      let end;
      while ((end = stdout.indexOf('\n')) !== -1) {
        const line = stdout.slice(0, end).replace(/\r$/, ''); stdout = stdout.slice(end + 1); this.receive(line);
      }
      if (stdout.length > 64 * 1024 * 1024) this.fail(new Error('The code browser returned an oversized response.'));
    });
    this.child.stderr.on('data', text => this.emit({ type: 'log', text: text.slice(-12000) }));
    this.child.stdin.on('error', error => { if (!this.closed) this.fail(new Error(`The code browser connection closed: ${error.message}`)); });
    this.child.once('error', error => this.fail(new Error(`Could not start Java: ${error.message}. Set a Java 17+ executable in Settings.`)));
    this.child.once('close', (code, signal) => {
      if (stdout && !this.closed) this.receive(stdout);
      if (!this.closed) this.fail(new Error(`The code browser stopped (${signal || `exit ${code}`}). Open Your own messages again.`));
    });
  }
  get busy() { return this.pending.size > 0 || this.inFlight > 0; }
  waitFor(id) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.fail(new Error('Reading APK code timed out. Close the code browser and try again.')), this.timeoutMs);
      timer.unref(); this.pending.set(id, { resolve, reject, timer });
    });
  }
  receive(line) {
    if (this.closed) return;
    if (!line.startsWith(PREFIX)) { if (line) this.emit({ type: 'log', text: line + '\n' }); return; }
    let message;
    try { message = JSON.parse(line.slice(PREFIX.length)); } catch { this.fail(new Error('The code browser returned an invalid response.')); return; }
    if (!message || !Number.isSafeInteger(message.id) || !this.pending.has(message.id)) { this.fail(new Error('The code browser returned an unexpected response.')); return; }
    const pending = this.pending.get(message.id); this.pending.delete(message.id); clearTimeout(pending.timer);
    if (typeof message.error === 'string') pending.reject(new Error(message.error));
    else if (Object.hasOwn(message, 'result')) pending.resolve(message.result);
    else { pending.reject(new Error('The code browser response is incomplete.')); this.fail(new Error('The code browser response is incomplete.')); }
  }
  request(action, details) {
    this.inFlight++;
    const task = this.queue.then(async () => {
      await this.ready;
      if (this.closed) throw new Error('The code browser is closed. Open Your own messages again.');
      const id = this.nextId++, response = this.waitFor(id);
      this.child.stdin.write(JSON.stringify({ id, action, ...details }) + '\n', error => { if (error) this.fail(error); });
      return response;
    }).finally(() => { this.inFlight--; });
    this.queue = task.catch(() => {});
    return task;
  }
  fail(error) {
    if (this.closed) return;
    this.closed = true;
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(error); }
    this.pending.clear();
    this.child?.stdin.destroy();
    if (!this.child || this.child.exitCode != null || this.child.signalCode != null) return;
    try {
      if (process.platform === 'win32') this.child.kill();
      else process.kill(-this.child.pid, 'SIGTERM');
    } catch { this.child.kill(); }
    const timer = setTimeout(() => {
      if (this.child.exitCode != null || this.child.signalCode != null) return;
      try { if (process.platform === 'win32') this.child.kill('SIGKILL'); else process.kill(-this.child.pid, 'SIGKILL'); } catch { this.child.kill('SIGKILL'); }
    }, 500); timer.unref(); this.exited.finally(() => clearTimeout(timer));
  }
  async close() {
    this.fail(new Error('Operation cancelled.'));
    let timer;
    await Promise.race([this.exited, new Promise(resolve => { timer = setTimeout(resolve, 2500); timer.unref(); })]);
    clearTimeout(timer);
  }
}

module.exports = { CodeBrowserSession };
