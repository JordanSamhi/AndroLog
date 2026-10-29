'use strict';
const { app, BrowserWindow, dialog, ipcMain, protocol, net, clipboard } = require('electron');
const path = require('node:path');
const fs = require('node:fs/promises');
const { pathToFileURL } = require('node:url');
const { Backend, copySafely } = require('./backend');
const { preparePlan } = require('./custom-report');
const { desktopVersion } = require('./version');

const andrologVersion = desktopVersion({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath, root: path.resolve(__dirname, '../..') });

protocol.registerSchemesAsPrivileged([{ scheme: 'androlog', privileges: { standard: true, secure: true, supportFetchAPI: true } }]);
let window, backend, selectedApk = null, selectedCoverageApk = null, selectedLog = null, result = null, report = null, busy = false, dirty = false, closing = false;
let settings;
const temporary = new Set();
const bundledJar = () => app.isPackaged ? path.join(process.resourcesPath, 'androlog.jar') : path.resolve(__dirname, `../../target/androlog-${andrologVersion}-jar-with-dependencies.jar`);
const configFile = () => path.join(app.getPath('userData'), 'settings.json');
async function exists(file) { try { await fs.access(file); return true; } catch { return false; } }
async function defaultSettings() {
  const roots = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, path.join(app.getPath('home'), 'Library/Android/sdk'), path.join(app.getPath('home'), 'Android/Sdk'), process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android/Sdk')].filter(Boolean);
  for (const root of roots) {
    const platforms = path.join(root, 'platforms'), tools = path.join(root, 'build-tools');
    if (!(await exists(platforms))) continue;
    let versions = []; try { versions = (await fs.readdir(tools)).sort((a, b) => b.localeCompare(a, undefined, { numeric: true })); } catch { }
    return { platforms, buildTools: versions.length ? path.join(tools, versions[0]) : '', java: '' };
  }
  return { platforms: '', buildTools: '', java: '' };
}
async function readSettings() {
  const defaults = await defaultSettings();
  try { const saved = JSON.parse(await fs.readFile(configFile(), 'utf8')); return { ...defaults, ...saved }; } catch { return defaults; }
}
async function removeSession(item) { if (item?.dir) { temporary.delete(item.dir); await backend.discard(item.dir); } }
async function exclusive(fn) {
  if (busy) throw new Error('Another operation is running.');
  busy = true; try { return await fn(); } finally { busy = false; }
}
function currentOnly(event) { if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('Invalid request.'); }
function handle(name, fn) { ipcMain.handle(name, async (event, ...args) => { currentOnly(event); return fn(...args); }); }
async function confirmDiscard() {
  if (!dirty) return true;
  const { response } = await dialog.showMessageBox(window, { type: 'warning', buttons: ['Keep working', 'Discard'], defaultId: 0, cancelId: 0, title: 'Unsaved APK', message: 'Your instrumented APK has not been saved.', detail: 'Save it before choosing another APK or closing the app.' });
  return response === 1;
}
async function chooseFile(title, extensions) {
  const picked = await dialog.showOpenDialog(window, { title, properties: ['openFile'], filters: [{ name: extensions.includes('apk') ? 'Android APK' : 'Log files', extensions }, { name: 'All files', extensions: ['*'] }] });
  return picked.canceled ? null : picked.filePaths[0];
}
function registerHandlers() {
  handle('clipboard:write', text => {
    if (typeof text !== 'string' || !text || text.length > 32768) throw new Error('Cannot copy this command.');
    clipboard.writeText(text);
  });
  handle('settings:get', () => ({ ...settings, andrologVersion }));
  handle('settings:choose', async kind => {
    if (!['platforms', 'buildTools', 'java'].includes(kind)) throw new Error('Unknown setting.');
    const choice = await dialog.showOpenDialog(window, { title: kind === 'java' ? 'Choose Java 17 or newer' : kind === 'platforms' ? 'Choose the Android SDK platforms folder' : 'Choose an Android Build Tools version folder', properties: [kind === 'java' ? 'openFile' : 'openDirectory'] });
    return choice.canceled ? null : choice.filePaths[0];
  });
  handle('settings:save', async next => {
    if (busy) throw new Error('Wait for the current operation to finish.');
    for (const key of ['platforms', 'buildTools', 'java']) if (typeof next[key] !== 'string' || next[key].length > 4096) throw new Error('Invalid setting.');
    settings = { platforms: next.platforms.trim(), buildTools: next.buildTools.trim(), java: next.java.trim() };
    await fs.mkdir(path.dirname(configFile()), { recursive: true }); await fs.writeFile(configFile(), JSON.stringify(settings, null, 2), { mode: 0o600 }); return settings;
  });
  handle('select:apk', async () => {
    if (busy || !(await confirmDiscard())) return null;
    const file = await chooseFile('Choose the original APK', ['apk']); if (!file) return null;
    selectedApk = file; dirty = false; await removeSession(result); result = null; return file;
  });
  handle('select:coverage-apk', async () => {
    if (busy) throw new Error('Wait for the current operation to finish.');
    const file = await chooseFile('Choose the instrumented APK used for this run', ['apk']);
    if (file) { selectedCoverageApk = file; await removeSession(report); report = null; } return file;
  });
  handle('select:log', async () => {
    if (busy) throw new Error('Wait for the current operation to finish.');
    const file = await chooseFile('Choose a captured runtime log', ['log', 'txt']);
    if (file) { selectedLog = file; await removeSession(report); report = null; } return file;
  });
  handle('code:inspect', () => exclusive(async () => {
    if (!selectedApk) throw new Error('Choose the original APK first.');
    return backend.inspect(selectedApk, settings);
  }));
  handle('apk:instrument', (opts, plan) => exclusive(async () => {
    if (!selectedApk) throw new Error('Choose an APK first.');
    if (!(await confirmDiscard())) return null;
    const trackedPlan = plan ? preparePlan(plan, opts.tag) : null;
    const next = await backend.instrument(selectedApk, settings, opts, trackedPlan);
    await removeSession(result); result = next; temporary.add(next.dir); dirty = true;
    return { name: path.basename(next.output) };
  }));
  handle('apk:save', async () => {
    if (!result || busy) throw new Error('Instrument an APK first.');
    const name = path.basename(result.input).replace(/\.apk$/i, '') + '-androlog.apk';
    const selected = await dialog.showSaveDialog(window, { title: 'Save instrumented APK', defaultPath: path.join(path.dirname(result.input), name), filters: [{ name: 'Android APK', extensions: ['apk'] }], properties: process.platform === 'linux' ? ['showOverwriteConfirmation'] : [] });
    if (selected.canceled || !selected.filePath) return null;
    const destination = /\.apk$/i.test(selected.filePath) ? selected.filePath : selected.filePath + '.apk';
    if (destination !== selected.filePath && await exists(destination)) {
      const answer = await dialog.showMessageBox(window, { type: 'warning', buttons: ['Cancel', 'Replace'], defaultId: 0, cancelId: 0, message: `Replace ${path.basename(destination)}?` });
      if (answer.response !== 1) return null;
    }
    await copySafely(result.output, destination, [selectedApk, selectedLog]); dirty = false;
    selectedCoverageApk = destination; await removeSession(report); report = null; return destination;
  });
  handle('coverage:analyze', opts => exclusive(async () => {
    if (!selectedCoverageApk || !selectedLog) throw new Error('Choose the instrumented APK and a runtime log first.');
    const next = await backend.analyze(selectedCoverageApk, selectedLog, settings, opts);
    await removeSession(report); report = next; temporary.add(next.dir); return next.report;
  }));
  handle('coverage:save', async () => {
    if (!report || busy) throw new Error('Analyze a run first.');
    const selected = await dialog.showSaveDialog(window, { title: 'Save HTML coverage report', defaultPath: 'androlog-coverage.html', filters: [{ name: 'HTML report', extensions: ['html'] }], properties: process.platform === 'linux' ? ['showOverwriteConfirmation'] : [] });
    if (selected.canceled || !selected.filePath) return null;
    const destination = /\.html$/i.test(selected.filePath) ? selected.filePath : selected.filePath + '.html';
    if (destination !== selected.filePath && await exists(destination)) {
      const answer = await dialog.showMessageBox(window, { type: 'warning', buttons: ['Cancel', 'Replace'], defaultId: 0, cancelId: 0, message: `Replace ${path.basename(destination)}?` });
      if (answer.response !== 1) return null;
    }
    await copySafely(report.html, destination, [selectedApk, selectedCoverageApk, selectedLog]); return destination;
  });
  handle('operation:cancel', () => { backend.cancel(); });
}
app.whenReady().then(async () => {
  app.setAboutPanelOptions({ applicationName: 'AndroLog', applicationVersion: andrologVersion, version: andrologVersion });
  settings = await readSettings();
  protocol.handle('androlog', async request => {
    const url = new URL(request.url);
    if (url.hostname === 'report' && url.pathname === '/current' && report?.html) {
      const response = await net.fetch(pathToFileURL(report.html).toString());
      const headers = new Headers(response.headers);
      headers.set('Content-Security-Policy', "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
      return new Response(response.body, { status: response.status, headers });
    }
    if (url.hostname !== 'app') return new Response('Not found', { status: 404 });
    if (url.pathname === '/anime.js') { const bundle = path.join(path.dirname(require.resolve('animejs/package.json')), 'dist', 'bundles', 'anime.umd.min.js'); return net.fetch(pathToFileURL(bundle).toString()); }
    const files = { '/': 'index.html', '/index.html': 'index.html', '/style.css': 'style.css', '/animation.js': 'animation.js', '/renderer.js': 'renderer.js', '/icon.png': 'icon.png' };
    const name = files[url.pathname]; return name ? net.fetch(pathToFileURL(path.join(__dirname, name)).toString()) : new Response('Not found', { status: 404 });
  });
  registerHandlers();
  window = new BrowserWindow({ width: 1320, height: 860, minWidth: 980, minHeight: 720, title: 'AndroLog', backgroundColor: '#eff4f3', icon: path.join(__dirname, 'icon.png'), show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true } });
  const bundledJava = app.isPackaged ? path.join(process.resourcesPath, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : undefined;
  backend = new Backend({ jar: bundledJar(), defaultJava: bundledJava, emit: update => { if (!window.isDestroyed()) window.webContents.send('operation:update', update); } });
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', event => event.preventDefault());
  window.on('close', event => {
    if (closing) return;
    if (busy) { event.preventDefault(); dialog.showMessageBox(window, { type: 'question', buttons: ['Keep working', 'Cancel and quit'], defaultId: 0, cancelId: 0, message: 'An operation is still running.' }).then(({ response }) => { if (response === 1) { closing = true; backend.cancel(); window.close(); } }); return; }
    if (dirty) { event.preventDefault(); confirmDiscard().then(ok => { if (ok) { closing = true; window.close(); } }); }
  });
  window.once('ready-to-show', () => window.show()); await window.loadURL('androlog://app/');
});
app.on('window-all-closed', async () => {
  if (backend) await backend.shutdown();
  await Promise.allSettled([...temporary].map(dir => fs.rm(dir, { recursive: true, force: true }))); app.quit();
});
