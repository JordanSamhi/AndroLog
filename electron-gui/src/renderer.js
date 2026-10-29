'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const api = window.androlog;
  const state = { apk: null, coverageApk: null, log: null, catalog: null, points: new Map(), draft: new Map(), selected: null, selectedClass: null, result: false, report: null, busy: false, stageKind: '', logText: '', pendingCategory: null, pendingCustomPoint: null, reportGeneration: 0, instrumentTag: null, instrumentProbes: [], openPackages: new Set(), openClasses: new Set() };
  const metricInfo = [
    ['statements', 'Statements'], ['methods', 'Methods'], ['classes', 'Classes'], ['activities', 'Activities'],
    ['services', 'Services'], ['broadcast-receivers', 'Broadcast receivers'], ['content-providers', 'Content providers']
  ];
  const basename = value => value ? value.split(/[\\/]/).pop() : '';
  const localTypes = new Set(['byte', 'short', 'int', 'long', 'float', 'double', 'boolean', 'java.lang.String']);
  function supportedLocalType(type) {
    // Keep aligned with LocalVariableAvailability.supported in the Java backend.
    if (typeof type !== 'string' || !type || type.length > 1024) return false;
    let base = type, dimensions = 0;
    while (base.endsWith('[]')) { base = base.slice(0, -2); if (++dimensions > 255) return false; }
    if (localTypes.has(base)) return true;
    if (base === 'char') return dimensions > 0;
    if (['void', 'null', 'null_type', 'unknown', 'bottom_type', 'address'].includes(base)) return false;
    return /^[^\s\u0000-\u001f\u007f\[\];/(){}<>:.]+(?:\.[^\s\u0000-\u001f\u007f\[\];/(){}<>:.]+)*$/u.test(base);
  }
  let variableSelection = null;
  const codeBrowser = { view: 0, classRequest: 0, classLoads: new Map(), variableLoads: new Map() };
  let toastTimer;
  const deviceTest = { active: false, loading: false, phase: '', started: null, timer: null, request: 0, cancelled: false };
  function toast(text, error = false) { const el = $('toast'); el.textContent = text; el.classList.toggle('error', error); el.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { el.hidden = true; }, 6000); }
  function navigate(page) {
    for (const el of document.querySelectorAll('.page')) { const selected = el.id === `page-${page}`; el.hidden = !selected; el.classList.toggle('active', selected); }
    for (const nav of document.querySelectorAll('.nav[data-page]')) { const selected = nav.dataset.page === page; nav.classList.toggle('active', selected); if (selected) nav.setAttribute('aria-current', 'page'); else nav.removeAttribute('aria-current'); }
    for (const tab of document.querySelectorAll('.workspace-tab')) { const selected = tab.dataset.page === page; tab.classList.toggle('active', selected); tab.setAttribute('aria-selected', String(selected)); tab.tabIndex = selected || (page === 'settings' && tab.dataset.page === 'instrument') ? 0 : -1; }
    document.querySelector('.main-content').scrollTo({ top: 0 });
  }
  for (const nav of document.querySelectorAll('[data-page]')) nav.addEventListener('click', () => navigate(nav.dataset.page));
  document.querySelector('.workspace-tabs').addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
    const tabs = [...document.querySelectorAll('.workspace-tab')]; let index = tabs.indexOf(document.activeElement);
    if (index < 0) return; event.preventDefault();
    index = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : -1) + tabs.length) % tabs.length;
    navigate(tabs[index].dataset.page); tabs[index].focus();
  });
  function options() { return { probes: [...document.querySelectorAll('[data-probe]')].map(el => el.checked), tag: $('log-tag').value.trim(), packagePrefix: $('package-prefix').value.trim(), excludeLibraries: $('exclude-libraries').checked }; }
  function clearReport() {
    state.report = null; state.pendingCategory = null; state.pendingCustomPoint = null; $('report-frame').hidden = true; $('report-frame').removeAttribute('src'); $('empty-report').hidden = false;
    $('explore-report').disabled = true; $('save-report').disabled = true; $('report-export').disabled = true; $('coverage-meta').textContent = 'Run an analysis to see your statistics';
    drawMetrics(null); renderCustomLogs(null);
  }
  function setBusy(busy, kind = '') {
    state.busy = busy; state.stageKind = kind; document.body.classList.toggle('busy', busy); $('operation-panel').hidden = !busy || kind === 'capture';
    if (busy && kind === 'instrument') window.andrologAnimation?.setStage(0, true);
    $('analysis-progress').hidden = !(busy && kind === 'coverage');
    window.andrologAnimation?.setAnalysis?.(0, busy && kind === 'coverage');
    if (busy && kind === 'coverage') { $('analysis-title').textContent = 'Preparing analysis'; $('analysis-description').textContent = 'Reading the APK and runtime log…'; document.querySelectorAll('.analysis-stage i').forEach(el => el.classList.remove('done')); }
    $('cancel-coverage').hidden = !(busy && kind === 'coverage');
    $('analyze').textContent = busy && kind === 'coverage' ? 'Analyzing…' : 'Analyze run →';
    for (const id of ['choose-apk','coverage-choose-apk','choose-log','open-custom','instrument','save-apk','test-device','analyze','settings-save','save-report','report-export','preset-essentials','preset-all','preset-custom']) $(id).disabled = busy;
    document.querySelectorAll('.custom-point-link').forEach(el => { el.disabled = busy; });
    document.querySelectorAll('[data-probe], #log-tag, #coverage-tag, #package-prefix, #exclude-libraries, [data-setting]').forEach(el => { el.disabled = busy; });
    if (!busy) updateAvailability();
    if (busy) { $('operation-title').textContent = kind === 'inspect' ? 'Reading your APK' : kind === 'coverage' ? 'Analyzing your run' : 'Preparing your APK'; $('operation-description').textContent = 'Starting the Java analysis…'; document.querySelectorAll('.stage-rail span').forEach(el => el.classList.remove('done')); }
  }
  function updateAvailability() {
    if (state.busy) return;
    document.querySelectorAll('.custom-point-link').forEach(el => { el.disabled = !el.dataset.pointId; });
    $('instrument').disabled = !state.apk; $('open-custom').disabled = !state.apk; $('save-apk').disabled = !state.result;
    $('test-device').disabled = !state.result; $('test-device').hidden = !state.result;
    $('analyze').disabled = !(state.coverageApk && state.log); $('save-report').disabled = !state.report; $('report-export').disabled = !state.report; $('explore-report').disabled = !state.report;
  }
  async function run(kind, task) {
    if (state.busy) return null;
    setBusy(true, kind); state.logText = ''; $('operation-log').textContent = 'Starting…';
    try { return await task(); } catch (error) { toast(error.message || String(error), true); return null; } finally { setBusy(false); }
  }
  api.onUpdate(update => {
    if (update.type === 'capture' && deviceTest.active) showCaptureStage(update.state);
    if (update.type === 'log') { state.logText = (state.logText + update.text).slice(-180000); $('operation-log').textContent = state.logText; $('operation-log').scrollTop = $('operation-log').scrollHeight; }
    if (update.type === 'stage') {
      if (state.stageKind === 'instrument') window.andrologAnimation?.setStage(update.stage);
      if (state.stageKind === 'coverage') { window.andrologAnimation?.setAnalysis?.(update.stage, true); const labels = ['Reading your APK', 'Reading inserted probes', 'Matching runtime logs', 'Creating the report', 'Finishing']; $('analysis-title').textContent = labels[Math.min(Math.max(update.stage - 1, 0), labels.length - 1)]; $('analysis-description').textContent = 'Comparing the selected APK with your runtime log.'; document.querySelectorAll('.analysis-stage i').forEach((el, i) => el.classList.toggle('done', i < update.stage)); }
      document.querySelectorAll('.stage-rail span').forEach((el, i) => el.classList.toggle('done', i < update.stage));
      const stages = state.stageKind === 'instrument' ? ['Reading your APK', 'Adding your log points', 'Rebuilding the APK', 'Aligning and signing'] : state.stageKind === 'inspect' ? ['Reading your APK', 'Preparing the code browser'] : ['Reading your APK', 'Reading inserted probes', 'Matching runtime logs', 'Creating the report', 'Finishing'];
      $('operation-title').textContent = stages[Math.min(Math.max(update.stage - 1, 0), stages.length - 1)];
      $('operation-description').textContent = 'Working locally with your Android SDK.';
    }
  });
  $('cancel-operation').addEventListener('click', () => { api.cancel(); $('operation-description').textContent = 'Stopping the current operation…'; });
  $('cancel-coverage').addEventListener('click', () => { api.cancel(); $('analyze').textContent = 'Stopping…'; });
  $('details-open').addEventListener('click', () => $('log-dialog').showModal()); $('details-close').addEventListener('click', () => $('log-dialog').close());
  async function chooseApk() {
    try {
      const selected = await api.chooseApk(); if (!selected) return;
      state.apk = selected; state.catalog = null; state.points.clear(); state.result = false; state.instrumentTag = null; state.instrumentProbes = []; $('next-steps').hidden = true;
      codeBrowser.view++; codeBrowser.classLoads.clear(); codeBrowser.variableLoads.clear();
      window.andrologAnimation?.setStage(0, true);
      $('apk-name').textContent = basename(selected); $('apk-path').textContent = selected; $('apk-path').title = selected;
      $('choose-apk').textContent = 'Change APK ↗';
      $('custom-count').textContent = 'Place a log in the code'; $('save-apk').hidden = true; $('action-title').textContent = 'Ready to instrument';
      $('action-description').textContent = 'Your original APK stays unchanged.'; updateAvailability();
    } catch (error) { toast(error.message, true); }
  }
  $('choose-apk').addEventListener('click', chooseApk);
  function selectCoverageApk(file) {
    state.coverageApk = file; $('coverage-apk').textContent = basename(file); $('coverage-apk').title = file;
    clearReport(); updateAvailability();
  }
  $('coverage-choose-apk').addEventListener('click', async () => {
    try { const file = await api.chooseCoverageApk(); if (file) selectCoverageApk(file); } catch (error) { toast(error.message, true); }
  });
  $('choose-log').addEventListener('click', async () => { try { const selected = await api.chooseLog(); if (!selected) return; state.log = selected; $('log-name').textContent = basename(selected); $('log-name').title = selected; clearReport(); updateAvailability(); } catch (e) { toast(e.message, true); } });
  function renderCustomLogs(result) {
    $('messages-panel').hidden = !result;
    $('messages-list').replaceChildren(); $('messages-summary').hidden = false;
    if (!result) return;
    const points = result.summary?.points || [];
    $('messages-summary').textContent = result.notice || (points.length ? 'Points found in the instrumented APK.' : 'No custom log points were found in this APK.');
    const kindOf = point => point.kind === 'marker' || point.kind === 'value' ? point.kind
      : point.dynamic === true || typeof point.latestValue === 'string' || (Array.isArray(point.samples) && point.samples.length) ? 'value' : 'marker';
    for (const kind of ['marker', 'value']) {
      const matching = points.filter(point => kindOf(point) === kind);
      if (!matching.length) continue;
      const group = document.createElement('section'); group.className = 'custom-log-group';
      const heading = document.createElement('h4'); heading.textContent = kind === 'marker' ? 'Statement markers' : 'Variable values'; group.appendChild(heading);
      for (const point of matching) {
      const statusName = point.status || (point.count == null ? 'unknown' : point.count > 0 ? 'covered' : 'uncovered');
      const observed = statusName === 'covered', unknown = statusName === 'unknown';
      const row = document.createElement('button'); row.type = 'button'; row.className = `message-row point-row custom-point-link ${unknown ? 'unknown' : observed ? 'observed' : 'unobserved'}`; row.dataset.pointId = point.id || '';
      row.dataset.kind = kind;
      row.title = 'Open this custom log in the code'; row.addEventListener('click', () => openCustomPoint(point));
      const samples = Array.isArray(point.samples) ? point.samples.filter(sample => typeof sample === 'string').slice(-3) : [];
      const latest = typeof point.latestValue === 'string' ? point.latestValue : samples.at(-1);
      const captured = observed && typeof latest === 'string';
      const detail = document.createElement('span'); detail.className = 'point-detail'; const message = document.createElement('strong');
      if (kind === 'value') {
        if (captured) {
          const label = document.createElement('small'); label.className = 'point-label'; label.textContent = point.message; detail.appendChild(label);
          const caption = document.createElement('small'); caption.className = 'point-value-caption'; caption.textContent = 'Latest value'; detail.appendChild(caption);
          message.className = 'point-value point-sample'; message.textContent = latest === '' ? '(empty string)' : latest;
          if (samples.length) message.title = `Recent distinct values:\n${samples.map(value => value === '' ? '(empty string)' : value).join('\n')}`;
        } else { message.className = 'point-label'; message.textContent = point.message; }
      } else message.textContent = point.message;
      const location = document.createElement('small'); location.textContent = `${point.method} · statement ${point.index}`; location.title = point.statement;
      const status = document.createElement('span'); status.className = 'point-status'; status.textContent = unknown ? 'Unconfirmed' : observed ? kind === 'value' && !captured ? 'Reached · value not captured' : 'Reached' : 'Not reached';
      status.title = unknown ? 'These legacy logs do not distinguish this point from another point with the same message.' : observed ? 'A matching point was observed in this log.' : 'No matching point was observed in this log.';
      detail.append(message, location);
      if (kind === 'value' && observed && point.count > 0) {
        const count = document.createElement('small'); count.className = 'point-observations'; count.textContent = `${point.count} observation${point.count === 1 ? '' : 's'}`; status.appendChild(count);
      }
      row.append(detail, status); group.appendChild(row);
      }
      $('messages-list').appendChild(group);
    }
  }
  for (const [id, values] of [['preset-essentials', [1,1,0,1,0]], ['preset-all', [1,1,1,1,1]], ['preset-custom', [0,0,0,0,0]]]) {
    $(id).addEventListener('click', () => { document.querySelectorAll('[data-probe]').forEach((el, i) => { el.checked = Boolean(values[i]); }); updateAvailability(); if (id === 'preset-custom' && state.apk && !state.points.size) browseCode(); });
  }
  $('coverage-tag').addEventListener('input', () => { clearReport(); updateAvailability(); });
  $('instrument').addEventListener('click', async () => {
    const plan = state.points.size ? { apkSha256: state.catalog.apkSha256, points: [...state.points.values()] } : null;
    const selectedOptions = options();
    const output = await run('instrument', () => api.instrument(selectedOptions, plan));
    if (!output) { window.andrologAnimation?.setStage(0); return; }
    window.andrologAnimation?.setStage(5);
    state.instrumentTag = selectedOptions.tag; state.instrumentProbes = selectedOptions.probes.slice(); $('next-steps').hidden = true;
    state.result = true; $('save-apk').hidden = false; $('action-title').textContent = 'Your APK is ready.'; $('action-description').textContent = 'Instrumented, aligned, and signed. Choose where to save your new APK.'; updateAvailability(); toast('Your instrumented APK is ready to save.');
  });
  function quoteShell(value) {
    return api.platform === 'win32' ? `'${value.replace(/'/g, "''")}'` : `'${value.replace(/'/g, "'\\''")}'`;
  }
  document.querySelectorAll('[data-copy-command]').forEach(button => button.addEventListener('click', async () => {
    const command = $(button.dataset.copyCommand).textContent;
    if (!command) return;
    button.disabled = true;
    try { await api.copyText(command); toast('Command copied.'); }
    catch { toast('Could not copy the command. Select and copy the text manually.', true); }
    finally { button.disabled = false; }
  }));
  function showNextSteps(file, tag) {
    $('terminal-instructions').textContent = api.platform === 'win32'
      ? 'Connect an Android device or emulator, then run these commands in PowerShell.'
      : 'Connect an Android device or emulator, then run these commands in a terminal.';
    $('install-command').textContent = `adb install -r ${quoteShell(file)}`;
    $('logcat-command').textContent = api.platform === 'win32'
      ? `cmd /c "adb logcat -v threadtime -s ${tag}:V > androlog-runtime.log"`
      : `adb logcat -v threadtime -s ${quoteShell(`${tag}:V`)} > androlog-runtime.log`;
    $('next-coverage').textContent = 'Next, open Coverage and choose androlog-runtime.log. Your saved instrumented APK is already selected. Analyze the run to see coverage and custom points. ADB is included in Android SDK Platform Tools.';
    $('next-steps').hidden = false;
    const reduced = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    $('next-steps').scrollIntoView?.({ behavior: reduced ? 'auto' : 'smooth', block: 'start' });
  }
  $('save-apk').addEventListener('click', async () => {
    try { const file = await api.saveApk(); if (file) {
      $('action-title').textContent = 'Saved. Ready to run.'; $('action-description').textContent = file;
      selectCoverageApk(file); $('coverage-tag').value = state.instrumentTag; showNextSteps(file, state.instrumentTag); toast(`Saved ${basename(file)}`);
    } } catch (e) { toast(e.message, true); }
  });
  function deviceError(message = '') {
    $('device-error').textContent = message; $('device-error').hidden = !message;
  }
  function updateDeviceControls() {
    const selected = $('device-select').selectedOptions[0];
    $('device-select').disabled = deviceTest.active || deviceTest.loading || ![...$('device-select').options].some(option => option.value && !option.disabled);
    $('device-refresh').disabled = deviceTest.active || deviceTest.loading;
    $('device-close').disabled = deviceTest.active;
    $('device-start').hidden = deviceTest.active;
    $('device-start').disabled = deviceTest.active || deviceTest.loading || !selected?.value || selected.disabled;
    $('device-stop').hidden = !deviceTest.active;
    $('device-stop').disabled = !deviceTest.active || deviceTest.phase !== 'recording' || deviceTest.cancelled;
    $('device-cancel').textContent = deviceTest.active ? 'Cancel test' : 'Cancel';
    $('device-cancel').disabled = deviceTest.active && deviceTest.cancelled;
    $('device-setup').hidden = deviceTest.active;
    $('device-progress').hidden = !deviceTest.active;
  }
  async function refreshDevices() {
    if (deviceTest.active || deviceTest.loading) return;
    const request = ++deviceTest.request, previous = $('device-select').value;
    deviceTest.loading = true; deviceError(); updateDeviceControls();
    $('device-hint').textContent = 'Looking for connected devices…';
    try {
      const devices = await api.listDevices();
      if (request !== deviceTest.request) return;
      $('device-select').replaceChildren();
      for (const device of devices) {
        const option = document.createElement('option'); option.value = device.serial;
        const status = device.state === 'unauthorized' ? 'authorization needed' : device.state;
        option.textContent = `${device.name || device.serial}${device.name && device.name !== device.serial ? ' · ' + device.serial : ''}${device.state === 'device' ? '' : ' — ' + status}`;
        option.disabled = device.state !== 'device'; $('device-select').appendChild(option);
      }
      const ready = [...$('device-select').options].filter(option => !option.disabled);
      if (ready.length) $('device-select').value = ready.some(option => option.value === previous) ? previous : ready[0].value;
      else {
        const placeholder = document.createElement('option'); placeholder.value = ''; placeholder.textContent = devices.length ? 'No device is ready' : 'No devices connected';
        placeholder.disabled = true; placeholder.selected = true; $('device-select').prepend(placeholder);
      }
      $('device-hint').textContent = ready.length
        ? 'The app will be installed and opened. Use it normally, then stop to see coverage.'
        : 'Connect a phone with USB debugging enabled, or start an emulator. Accept the debugging prompt on your phone, then refresh.';
    } catch (error) {
      if (request !== deviceTest.request) return;
      $('device-select').replaceChildren(); deviceError(error.message || String(error));
      $('device-hint').textContent = 'Check your SDK settings and Android SDK Platform Tools, then refresh.';
    } finally {
      if (request === deviceTest.request) { deviceTest.loading = false; updateDeviceControls(); }
    }
  }
  function updateElapsed() {
    const seconds = Math.max(0, Math.floor((Date.now() - deviceTest.started) / 1000));
    $('device-elapsed').textContent = `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
  }
  function showCaptureStage(phase) {
    deviceTest.phase = phase;
    const labels = {
      preparing: ['Preparing your test', 'Connecting to your device…'],
      installing: ['Installing the APK', 'Your instrumented app is being installed.'],
      launching: ['Opening your app', 'Starting the app and its log recording…'],
      recording: ['Recording', 'Use the app on your device. Stop when you are ready to see coverage.'],
      stopping: ['Finishing the recording', 'Saving the last logs before analysis…'],
      cancelling: ['Cancelling the test', 'Keeping any logs already recorded…']
    };
    const [title, guidance] = labels[phase] || labels.preparing;
    $('device-status').textContent = title; $('device-guidance').textContent = guidance;
    $('device-indicator').classList.toggle('recording', phase === 'recording');
    if (phase === 'recording') {
      if (deviceTest.started === null) deviceTest.started = Date.now();
      updateElapsed(); if (!deviceTest.timer) deviceTest.timer = setInterval(updateElapsed, 1000);
    } else { clearInterval(deviceTest.timer); deviceTest.timer = null; }
    $('device-elapsed').hidden = deviceTest.started === null;
    updateDeviceControls();
  }
  function closeDeviceDialog() {
    if (deviceTest.active) return;
    deviceTest.request++; deviceTest.loading = false; $('device-dialog').close();
  }
  $('test-device').addEventListener('click', () => {
    if (state.busy || !state.result) return;
    deviceError(); updateDeviceControls(); $('device-dialog').showModal(); refreshDevices();
  });
  $('device-refresh').addEventListener('click', refreshDevices);
  $('device-select').addEventListener('change', updateDeviceControls);
  $('device-close').addEventListener('click', closeDeviceDialog);
  $('device-dialog').addEventListener('cancel', event => {
    if (deviceTest.active) event.preventDefault();
    else { deviceTest.request++; deviceTest.loading = false; }
  });
  $('device-cancel').addEventListener('click', async () => {
    if (!deviceTest.active) { closeDeviceDialog(); return; }
    const previousPhase = deviceTest.phase;
    deviceTest.cancelled = true; showCaptureStage('cancelling');
    try { await api.cancel(); }
    catch (error) { if (deviceTest.active) { deviceTest.cancelled = false; deviceError(error.message || String(error)); showCaptureStage(previousPhase); } }
  });
  $('device-start').addEventListener('click', async () => {
    const selected = $('device-select').selectedOptions[0];
    if (state.busy || !state.result || deviceTest.loading || !selected?.value || selected.disabled) return;
    const device = selected.value;
    deviceTest.active = true; deviceTest.cancelled = false; deviceTest.started = null;
    $('device-running-name').textContent = selected.textContent; deviceError();
    setBusy(true, 'capture'); state.logText = ''; $('operation-log').textContent = 'Starting device test…'; showCaptureStage('preparing');
    let captured;
    try { captured = await api.startDeviceTest({ device }); }
    catch (error) { deviceError(error.message || String(error)); }
    finally {
      clearInterval(deviceTest.timer); deviceTest.timer = null; deviceTest.active = false;
      setBusy(false); updateDeviceControls();
    }
    if (!captured) return;
    closeDeviceDialog(); selectCoverageApk(captured.apk);
    state.log = captured.log; $('log-name').textContent = basename(captured.log); $('log-name').title = captured.log;
    $('coverage-tag').value = captured.tag; updateAvailability(); navigate('coverage');
    if (!deviceTest.cancelled) await analyzeRun();
    state.logText = `${state.logText}\nDevice test files\nAPK: ${captured.apk}\nRuntime log: ${captured.log}\n`;
    $('operation-log').textContent = state.logText;
  });
  $('device-stop').addEventListener('click', async () => {
    if (!deviceTest.active || deviceTest.phase !== 'recording') return;
    showCaptureStage('stopping');
    try { await api.stopDeviceTest(); }
    catch (error) { if (deviceTest.active) { deviceError(error.message || String(error)); showCaptureStage('recording'); } }
  });
  async function analyzeRun() {
    if (state.busy) return null;
    clearReport();
    const result = await run('coverage', () => api.analyze({ tag: $('coverage-tag').value.trim() })); if (!result) return null;
    state.report = result; state.reportGeneration++; state.pendingCategory = null; state.pendingCustomPoint = null; $('coverage-meta').textContent = `${basename(state.coverageApk)} · ${basename(state.log)}`;
    $('empty-report').hidden = true; $('report-frame').hidden = false; $('report-frame').src = `androlog://report/current?run=${state.reportGeneration}`;
    drawMetrics(result.coverageSummary); renderCustomLogs(result.custom); updateAvailability(); toast('Your coverage report is ready.');
    return result;
  }
  $('analyze').addEventListener('click', () => analyzeRun());
  function openCustomPoint(point) {
    if (state.busy || !state.report || !point.id) return;
    state.pendingCategory = null; state.pendingCustomPoint = point.id; navigate('explore'); sendReportSelection();
  }
  $('explore-report').addEventListener('click', () => navigate('explore'));
  for (const id of ['save-report','report-export']) $(id).addEventListener('click', async () => { try { const file = await api.saveReport(); if (file) toast(`Saved ${basename(file)}`); } catch (e) { toast(e.message, true); } });
  function drawMetrics(summary) {
    const grid = $('stats-grid'); grid.replaceChildren();
    for (const [key, name] of metricInfo) {
      const metric = summary?.[key]; const total = metric && Number(metric.total); const observed = metric && Number(metric.covered);
      const valid = Number.isFinite(total) && Number.isFinite(observed) && total > 0;
      const card = document.createElement('button'); card.className = 'stat'; card.type = 'button'; card.disabled = !metric;
      const label = document.createElement('span'); label.className = 'stat-title'; label.textContent = name;
      const value = document.createElement('strong'); value.textContent = valid ? `${(100 * observed / total).toFixed(1)}%` : '—';
      const detail = document.createElement('small'); detail.textContent = !metric ? (summary ? 'No probes found' : 'Awaiting analysis') : total === 0 ? 'Nothing to cover' : `${observed.toLocaleString()} of ${total.toLocaleString()} observed`;
      const meter = document.createElement('span'); meter.className = 'meter'; const fill = document.createElement('i');
      fill.style.setProperty('--value', `${valid ? Math.max(0, Math.min(100, 100 * observed / total)) : 0}%`); meter.appendChild(fill);
      card.append(label, value, detail, meter); if (metric) card.addEventListener('click', () => openCategory(key)); grid.appendChild(card);
    }
  }
  function openCategory(category) { state.pendingCustomPoint = null; state.pendingCategory = category; navigate('explore'); sendReportSelection(); }
  function sendReportSelection() {
    const target = $('report-frame').contentWindow; if (!target) return;
    if (state.pendingCustomPoint) target.postMessage({ type: 'androlog.selectCustomPoint', pointId: state.pendingCustomPoint }, '*');
    else if (state.pendingCategory) target.postMessage({ type: 'androlog.selectCategory', category: state.pendingCategory }, '*');
  }
  $('report-frame').addEventListener('load', sendReportSelection);
  function updateSettings(values) {
    if (values.andrologVersion) { $('app-version').textContent = `Version ${values.andrologVersion}`; document.title = `AndroLog · ${values.andrologVersion}`; }
    for (const key of ['platforms','buildTools','java']) $(`settings-${key}`).value = values[key] || '';
    const ready = Boolean(values.platforms && values.buildTools); $('sdk-dot').classList.toggle('ready', ready); $('sdk-summary').textContent = ready ? 'SDK paths configured' : 'Android SDK setup';
  }
  api.settings().then(updateSettings).catch(e => toast(e.message, true));
  document.querySelectorAll('[data-setting]').forEach(btn => btn.addEventListener('click', async () => { try { const result = await api.chooseSetting(btn.dataset.setting); if (result) $(`settings-${btn.dataset.setting}`).value = result; } catch (e) { toast(e.message, true); } }));
  $('settings-save').addEventListener('click', async () => { try { const settings = {}; for (const key of ['platforms','buildTools','java']) settings[key] = $(`settings-${key}`).value; updateSettings(await api.saveSettings(settings)); $('settings-status').textContent = 'Settings saved on this computer.'; toast('Settings saved.'); } catch (e) { toast(e.message, true); } });
  const identity = (method, index) => `${method}\n${index}`;
  const editorDrafts = new Map();
  const pointKind = point => point?.kind || (point?.variables?.length ? 'value' : 'marker');
  async function browseCode() {
    if (!state.apk) { toast('Choose the original APK first.', true); return; }
    const catalog = await run('inspect', () => api.inspectCode()); if (!catalog) return;
    if (state.catalog && state.catalog.apkSha256 !== catalog.apkSha256) {
      state.points.clear(); $('custom-count').textContent = 'Place a log in the code';
      toast('The APK changed. Choose the log points again.');
    }
    if (!(catalog.lazy && state.catalog?.lazy && catalog.apkSha256 === state.catalog.apkSha256 && catalog.sessionId === state.catalog.sessionId)) {
      state.catalog = catalog; codeBrowser.classLoads.clear(); codeBrowser.variableLoads.clear();
    }
    codeBrowser.view++;
    editorDrafts.clear();
    state.draft = new Map([...state.points].map(([key, point]) => [key, { ...point, ...(point.variables ? { variables: point.variables.map(variable => ({ ...variable })) } : {}) }])); state.selected = null; state.selectedClass = null;
    $('class-search').value = ''; $('point-message').value = ''; $('point-message').disabled = true; $('point-label').textContent = 'Select a statement'; $('remove-point').disabled = true;
    const first = [...new Set(state.catalog.methods.map(m => m.className))].sort()[0];
    if (first && !state.catalog.lazy) { revealPackage(first); state.openClasses.add(first); renderClass(first); }
    else { $('code-class').textContent = 'Choose a class'; showCodeNotice('Select a class or method in the package tree to read its code.'); renderClasses(); }
    updateVariables(); $('code-dialog').showModal(); updateDraftCount();
  }
  $('open-custom').addEventListener('click', browseCode);
  function renderClasses() {
    const query = $('class-search').value.toLowerCase().trim();
    const root = { packages: new Map(), classes: new Map() };
    for (const method of state.catalog.methods) {
      const name = method.className;
      if (!name.toLowerCase().includes(query) && !method.signature.toLowerCase().includes(query)) continue;
      const parts = name.includes('.') ? name.slice(0, name.lastIndexOf('.')).split('.') : ['(default package)'];
      let node = root, path = '';
      for (const part of parts) {
        path = path ? `${path}.${part}` : part;
        if (!node.packages.has(part)) node.packages.set(part, { path, packages: new Map(), classes: new Map() });
        node = node.packages.get(part);
      }
      if (!node.classes.has(name)) node.classes.set(name, []);
      node.classes.get(name).push(method);
    }
    const list = $('classes'); const scroll = list.scrollTop; list.replaceChildren();
    function appendNode(node, parent) {
      for (const [part, child] of [...node.packages].sort(([a], [b]) => a.localeCompare(b))) {
        const folder = document.createElement('details'); folder.className = 'tree-package'; folder.dataset.package = child.path; folder.open = Boolean(query) || state.openPackages.has(child.path);
        const label = document.createElement('summary'); label.textContent = part; label.title = child.path; folder.appendChild(label);
        folder.addEventListener('toggle', () => { if (!query && folder.isConnected) folder.open ? state.openPackages.add(child.path) : state.openPackages.delete(child.path); });
        const children = document.createElement('div'); children.className = 'tree-children'; appendNode(child, children); folder.appendChild(children); parent.appendChild(folder);
      }
      for (const [name, methods] of [...node.classes].sort(([a], [b]) => a.localeCompare(b))) {
        const branch = document.createElement('details'); branch.className = 'tree-class'; branch.open = Boolean(query) || state.openClasses.has(name);
        const heading = document.createElement('summary'); heading.title = name;
        const item = document.createElement('button'); item.className = 'class-item'; item.type = 'button'; item.textContent = name.slice(name.lastIndexOf('.') + 1); item.classList.toggle('active', name === state.selectedClass);
        item.addEventListener('click', event => { event.preventDefault(); event.stopPropagation(); revealPackage(name); state.openClasses.add(name); renderClass(name); });
        heading.appendChild(item); branch.appendChild(heading);
        let populated = false;
        function appendMethods() {
          if (populated) return; populated = true;
          for (const method of methods.sort((a, b) => a.signature.localeCompare(b.signature))) {
            const button = document.createElement('button'); button.type = 'button'; button.className = 'tree-method';
            button.textContent = method.signature.replace(/^<[^:]+:\s*/, '').replace(/>$/, ''); button.title = method.signature;
            button.addEventListener('click', () => { revealPackage(name); state.openClasses.add(name); renderClass(name, method.signature); }); branch.appendChild(button);
          }
        }
        branch.addEventListener('toggle', () => { if (!branch.isConnected) return; if (!query) branch.open ? state.openClasses.add(name) : state.openClasses.delete(name); if (branch.open) appendMethods(); });
        if (branch.open || !state.catalog.lazy) appendMethods();
        parent.appendChild(branch);
      }
    }
    appendNode(root, list); list.scrollTop = scroll;
    if (!root.packages.size) { const empty = document.createElement('p'); empty.className = 'tree-empty'; empty.textContent = 'No matching code'; list.appendChild(empty); }
  }
  function revealPackage(name) {
    const parts = name.includes('.') ? name.slice(0, name.lastIndexOf('.')).split('.') : ['(default package)'];
    let path = ''; for (const part of parts) { path = path ? `${path}.${part}` : part; state.openPackages.add(path); }
  }
  $('class-search').addEventListener('input', renderClasses);
  function showCodeNotice(message, retry) {
    const notice = document.createElement('p'); notice.className = 'tree-empty'; notice.textContent = message;
    $('code-lines').replaceChildren(notice);
    if (retry) { const button = document.createElement('button'); button.type = 'button'; button.textContent = 'Try again'; button.addEventListener('click', retry); $('code-lines').appendChild(button); }
  }
  function loadClass(name, methods) {
    const catalog = state.catalog;
    if (!codeBrowser.classLoads.has(name)) {
      const request = Promise.resolve().then(() => api.inspectClass(name)).then(result => {
        if (state.catalog !== catalog) return;
        if (result?.className !== name || !Array.isArray(result.methods)) throw new Error('The class code could not be read.');
        const loaded = new Map(result.methods.map(method => [method.signature, method]));
        for (const method of methods) if (!Array.isArray(loaded.get(method.signature)?.statements)) throw new Error('The class code is incomplete.');
        for (const method of methods) method.statements = loaded.get(method.signature).statements;
      }).finally(() => { if (codeBrowser.classLoads.get(name) === request) codeBrowser.classLoads.delete(name); });
      codeBrowser.classLoads.set(name, request);
    }
    return codeBrowser.classLoads.get(name);
  }
  function loadVariables(method) {
    const catalog = state.catalog;
    if (!codeBrowser.variableLoads.has(method.signature)) {
      const request = Promise.resolve().then(() => api.inspectVariables(method.signature)).then(result => {
        if (state.catalog !== catalog) return;
        if (result?.signature !== method.signature || !Array.isArray(result.locals) || !Array.isArray(result.statements)) throw new Error('The available variables could not be read.');
        const masks = new Map(result.statements.map(statement => [statement.index, statement.localMask]));
        for (const statement of method.statements) if (statement.selectable && typeof masks.get(statement.index) !== 'string') throw new Error('The available variables are incomplete.');
        method.locals = result.locals;
        for (const statement of method.statements) statement.localMask = masks.get(statement.index) || '';
        method.variablesLoaded = true;
      }).finally(() => { if (codeBrowser.variableLoads.get(method.signature) === request) codeBrowser.variableLoads.delete(method.signature); });
      codeBrowser.variableLoads.set(method.signature, request);
    }
    return codeBrowser.variableLoads.get(method.signature);
  }
  function renderClass(name, selectedMethod = null) {
    const view = codeBrowser.view, request = ++codeBrowser.classRequest;
    state.selectedClass = name; state.selected = null; $('point-message').value = ''; $('point-message').disabled = true; $('remove-point').disabled = true; $('point-label').textContent = 'Select a statement'; $('code-class').textContent = name; updateVariables();
    renderClasses();
    const methods = state.catalog.methods.filter(method => method.className === name);
    if (state.catalog.lazy && methods.some(method => !Array.isArray(method.statements))) {
      showCodeNotice('Reading this class…');
      loadClass(name, methods).then(() => {
        if (view === codeBrowser.view && request === codeBrowser.classRequest) renderClassStatements(methods, selectedMethod);
      }).catch(error => {
        if (view === codeBrowser.view && request === codeBrowser.classRequest) showCodeNotice(error.message || 'Could not read this class.', () => renderClass(name, selectedMethod));
      });
    } else renderClassStatements(methods, selectedMethod);
  }
  function renderClassStatements(methods, selectedMethod) {
    const lines = $('code-lines'); lines.replaceChildren(); let target = null;
    for (const method of methods) {
      const heading = document.createElement('div'); heading.className = 'code-method'; heading.textContent = method.signature; lines.appendChild(heading); if (method.signature === selectedMethod) target = heading;
      for (const statement of method.statements) {
        const key = identity(method.signature, statement.index); const row = document.createElement('button'); row.type = 'button'; row.className = 'code-line'; row.disabled = !statement.selectable;
        const num = document.createElement('span'); num.className = 'num'; num.textContent = `${state.draft.has(key) ? '● ' : ''}${statement.index}`; num.classList.toggle('marked', state.draft.has(key));
        const text = document.createElement('span'); text.textContent = statement.text; row.append(num, text);
        row.addEventListener('click', () => selectPoint(method, statement, row, num)); lines.appendChild(row);
      }
    }
    lines.scrollTop = target ? target.offsetTop - lines.offsetTop : 0;
  }
  function selectPoint(method, statement, row, number) {
    $('code-lines').querySelectorAll('.code-line.active').forEach(el => el.classList.remove('active')); row.classList.add('active');
    const key = identity(method.signature, statement.index), point = state.draft.get(key);
    if (!editorDrafts.has(key)) { const mode = pointKind(point); editorDrafts.set(key, { mode, marker: null, value: null, [mode]: point || null }); }
    state.selected = { key, method, statement, number, editor: editorDrafts.get(key) };
    $('point-label').textContent = `Before statement ${statement.index}`; $('point-label').title = method.signature;
    renderPointEditor(); readSelectedVariables();
    if (state.selected.editor.mode === 'marker') $('point-message').focus();
  }
  function readSelectedVariables() {
    const selection = state.selected, view = codeBrowser.view;
    if (!selection || selection.editor.mode !== 'value') return;
    const method = selection.method;
    if (state.catalog.lazy && !method.variablesLoaded && !selection.variablesPending) {
      selection.variablesError = null;
      selection.variablesPending = true;
      updateVariables();
      loadVariables(method).then(() => {
        if (state.selected === selection && view === codeBrowser.view) { selection.variablesPending = false; updateVariables(); }
      }).catch(error => {
        if (state.selected === selection && view === codeBrowser.view) { selection.variablesPending = false; selection.variablesError = error.message || 'Could not read variables.'; updateVariables(); }
      });
    }
  }
  function renderPointEditor() {
    const selected = state.selected;
    $('point-message').value = selected?.editor.marker?.message || '';
    $('point-message').disabled = !selected;
    $('remove-point').disabled = !selected || !state.draft.has(selected.key);
    updateVariables();
  }
  function saveSelectedPoint(point) {
    const { key, statement, number, editor } = state.selected;
    editor[editor.mode] = point;
    if (point) state.draft.set(key, point); else state.draft.delete(key);
    const marked = Boolean(point); number.textContent = `${marked ? '● ' : ''}${statement.index}`; number.classList.toggle('marked', marked); $('remove-point').disabled = !marked; updateDraftCount();
  }
  function chooseLogKind(mode) {
    if (!state.selected) return;
    state.selected.editor.mode = mode;
    saveSelectedPoint(state.selected.editor[mode]);
    renderPointEditor(); readSelectedVariables();
    if (mode === 'marker') $('point-message').focus();
  }
  $('log-kind-marker').addEventListener('click', () => chooseLogKind('marker'));
  $('log-kind-value').addEventListener('click', () => chooseLogKind('value'));
  $('log-kind-marker').parentElement.addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight'].includes(event.key) || !state.selected) return;
    event.preventDefault(); const mode = state.selected.editor.mode === 'marker' ? 'value' : 'marker'; chooseLogKind(mode); $(`log-kind-${mode}`).focus();
  });
  function availableVariables() {
    const unique = new Map();
    const selected = state.selected;
    let locals = selected?.statement.locals || [];
    if (typeof selected?.statement.localMask === 'string') {
      // The catalog stores each method's variable descriptions once. Expand only
      // this statement's little-endian availability bits when its picker is used.
      locals = [];
      const dictionary = selected.method.locals || [];
      try {
        const mask = atob(selected.statement.localMask);
        for (let byte = 0; byte < mask.length && byte * 8 < dictionary.length; byte++) {
          const bits = mask.charCodeAt(byte);
          for (let bit = 0; bit < 8; bit++) if (bits & (1 << bit)) locals.push(dictionary[byte * 8 + bit]);
        }
      } catch { /* An invalid mask must not offer potentially uninitialized values. */ }
    }
    for (const local of locals) {
      if (local && typeof local.name === 'string' && /^[^\s{}\u0000-\u001f\u007f]{1,200}$/u.test(local.name) && supportedLocalType(local.type)) unique.set(local.name, { name: local.name, type: local.type });
    }
    return [...unique.values()];
  }
  function closeVariables(focus = false) {
    $('variable-picker').hidden = true; $('insert-variable').setAttribute('aria-expanded', 'false'); variableSelection = null;
    if (focus) $('insert-variable').focus();
  }
  function updateVariables() {
    closeVariables();
    const selected = state.selected, mode = selected?.editor.mode || 'marker', value = selected?.editor.value;
    for (const kind of ['marker', 'value']) { const button = $(`log-kind-${kind}`); button.disabled = !selected; button.setAttribute('aria-checked', String(kind === mode)); button.tabIndex = kind === mode ? 0 : -1; }
    $('marker-editor').hidden = mode !== 'marker'; $('value-editor').hidden = mode !== 'value';
    const available = mode !== 'value' || selected?.variablesPending || selected?.variablesError ? [] : availableVariables(); $('insert-variable').disabled = !available.length;
    const variable = value?.variables?.length === 1 ? value.variables[0] : null;
    $('variable-name').textContent = variable?.name || (value?.variables?.length ? `${value.variables.length} saved variables` : 'No variable selected');
    $('variable-type').textContent = variable ? variableTypeLabel(variable.type) : '';
    $('insert-variable').textContent = value ? 'Change variable' : 'Choose variable';
    const legacy = mode === 'value' && value && !value.kind && (!variable || value.message !== `{${variable.name}}`);
    $('legacy-template').hidden = !legacy; $('legacy-template').textContent = legacy ? `Saved template: ${value.message} · Choose a variable to replace it.` : '';
    $('variable-hint').textContent = !selected ? 'Select a statement to place a log.' : mode === 'marker' ? 'Coverage will show whether this statement was reached.' : selected.variablesPending ? 'Reading available variables…' : selected.variablesError ? `${selected.variablesError} Select this statement again to retry.` : available.length ? 'Coverage will show the values recorded before this statement.' : 'No initialized supported local is available here.';
    $('insert-variable').title = $('variable-hint').textContent;
  }
  function variableTypeLabel(type) { return type === 'java.lang.String' ? 'String' : localTypes.has(type) ? type : `${type} · uses toString()`; }
  $('insert-variable').addEventListener('click', () => {
    if (!$('variable-picker').hidden) { closeVariables(true); return; }
    const available = availableVariables(); if (!available.length || state.selected?.editor.mode !== 'value') return;
    variableSelection = state.selected;
    $('variable-list').replaceChildren();
    for (const variable of available) {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'variable-choice'; button.dataset.variable = variable.name;
      const name = document.createElement('span'); name.textContent = variable.name;
      const type = document.createElement('small');
      type.textContent = variableTypeLabel(variable.type);
      button.title = type.textContent;
      button.append(name, type);
      button.addEventListener('click', () => {
        if (!state.selected || state.selected !== variableSelection) return;
        const { method, statement } = state.selected;
        saveSelectedPoint({ kind: 'value', method: method.signature, index: statement.index, statement: statement.text, message: `{${variable.name}}`, variables: [{ ...variable }] });
        renderPointEditor(); $('insert-variable').focus();
      });
      $('variable-list').appendChild(button);
    }
    $('variable-picker').hidden = false; $('insert-variable').setAttribute('aria-expanded', 'true'); $('variable-list').firstElementChild?.focus();
  });
  $('variable-close').addEventListener('click', () => closeVariables(true));
  $('variable-picker').addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); closeVariables(true); }
  });
  document.addEventListener('click', event => {
    if (!$('variable-picker').hidden && !$('variable-picker').contains(event.target) && !$('insert-variable').contains(event.target)) closeVariables();
  });
  function updateDraftCount() { $('dialog-count').textContent = state.draft.size ? `${state.draft.size} log ${state.draft.size === 1 ? 'point' : 'points'} selected` : 'No log points selected'; }
  $('point-message').addEventListener('input', () => {
    if (!state.selected || state.selected.editor.mode !== 'marker') return; const { method, statement } = state.selected; const message = $('point-message').value;
    saveSelectedPoint(message.trim() ? { kind: 'marker', method: method.signature, index: statement.index, statement: statement.text, message } : null);
  });
  $('remove-point').addEventListener('click', () => { if (state.selected) { state.selected.editor.marker = null; state.selected.editor.value = null; saveSelectedPoint(null); renderPointEditor(); } });
  function closeCode() { codeBrowser.view++; closeVariables(); $('code-dialog').close(); }
  $('code-close').addEventListener('click', closeCode); $('code-cancel').addEventListener('click', closeCode);
  $('code-dialog').addEventListener('cancel', event => { event.preventDefault(); closeCode(); });
  $('code-apply').addEventListener('click', () => {
    for (const point of state.draft.values()) if (/[\n\r\0]/.test(point.message) || new TextEncoder().encode(point.message).length > 1000) { toast('Each message must fit on one line and within 1,000 UTF-8 bytes.', true); return; }
    state.points = new Map(state.draft); $('custom-count').textContent = state.points.size ? `${state.points.size} log ${state.points.size === 1 ? 'point' : 'points'} selected` : 'Place a log in the code'; closeCode();
  });
  drawMetrics(null); updateAvailability();
})();
