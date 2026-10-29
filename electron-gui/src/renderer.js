'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const api = window.androlog;
  const state = { apk: null, log: null, catalog: null, points: new Map(), draft: new Map(), selected: null, selectedClass: null, result: false, report: null, busy: false, stageKind: '', logText: '', pendingCategory: null, reportGeneration: 0, logRead: 0, instrumentTag: null, instrumentProbes: [], preferredPlan: null, customLoading: false, openPackages: new Set(), openClasses: new Set() };
  const metricInfo = [
    ['statements', 'Statements', '#528ee5'], ['methods', 'Methods', '#f08262'], ['classes', 'Classes', '#31b38b'], ['activities', 'Activities', '#e7bc42'],
    ['services', 'Services', '#4fb4a8'], ['broadcast-receivers', 'Broadcast receivers', '#e4956a'], ['content-providers', 'Content providers', '#9a83e9']
  ];
  const basename = value => value ? value.split(/[\\/]/).pop() : '';
  let toastTimer;
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
    state.report = null; state.pendingCategory = null; $('report-frame').hidden = true; $('report-frame').removeAttribute('src'); $('empty-report').hidden = false;
    $('explore-report').disabled = true; $('save-report').disabled = true; $('report-export').disabled = true; $('coverage-meta').textContent = 'Run an analysis to see your statistics';
    drawMetrics(null);
  }
  function setBusy(busy, kind = '') {
    state.busy = busy; state.stageKind = kind; document.body.classList.toggle('busy', busy); $('operation-panel').hidden = !busy;
    if (busy && kind === 'instrument') window.andrologAnimation?.setStage(0, true);
    $('analysis-progress').hidden = !(busy && kind === 'coverage');
    window.andrologAnimation?.setAnalysis?.(0, busy && kind === 'coverage');
    if (busy && kind === 'coverage') { $('analysis-title').textContent = 'Preparing analysis'; $('analysis-description').textContent = 'Reading the APK and runtime log…'; document.querySelectorAll('.analysis-stage i').forEach(el => el.classList.remove('done')); }
    $('cancel-coverage').hidden = !(busy && kind === 'coverage');
    $('analyze').textContent = busy && kind === 'coverage' ? 'Analyzing…' : 'Analyze run →';
    for (const id of ['choose-apk','coverage-choose-apk','choose-log','custom-build','open-custom','instrument','save-apk','analyze','settings-save','save-report','report-export','preset-essentials','preset-all','preset-custom']) $(id).disabled = busy;
    document.querySelectorAll('[data-probe], #log-tag, #coverage-tag, #package-prefix, #exclude-libraries, [data-setting]').forEach(el => { el.disabled = busy; });
    if (!busy) updateAvailability();
    if (busy) { $('operation-title').textContent = kind === 'inspect' ? 'Reading your APK' : kind === 'coverage' ? 'Analyzing your run' : 'Preparing your APK'; $('operation-description').textContent = 'Starting the Java analysis…'; document.querySelectorAll('.stage-rail span').forEach(el => el.classList.remove('done')); }
  }
  function updateAvailability() {
    if (state.busy) return;
    $('instrument').disabled = !state.apk; $('open-custom').disabled = !state.apk; $('save-apk').disabled = !state.result;
    $('analyze').disabled = !(state.apk && state.log) || state.customLoading; $('save-report').disabled = !state.report; $('report-export').disabled = !state.report; $('explore-report').disabled = !state.report;
  }
  async function run(kind, task) {
    if (state.busy) return null;
    setBusy(true, kind); state.logText = ''; $('operation-log').textContent = 'Starting…';
    try { return await task(); } catch (error) { toast(error.message || String(error), true); return null; } finally { setBusy(false); }
  }
  api.onUpdate(update => {
    if (update.type === 'log') { state.logText = (state.logText + update.text).slice(-180000); $('operation-log').textContent = state.logText; $('operation-log').scrollTop = $('operation-log').scrollHeight; }
    if (update.type === 'stage') {
      if (state.stageKind === 'instrument') window.andrologAnimation?.setStage(update.stage);
      if (state.stageKind === 'coverage') { window.andrologAnimation?.setAnalysis?.(update.stage, true); const labels = ['Reading your APK', 'Building the baseline', 'Matching runtime logs', 'Creating the report', 'Finishing']; $('analysis-title').textContent = labels[Math.min(Math.max(update.stage - 1, 0), labels.length - 1)]; $('analysis-description').textContent = 'Comparing the selected APK with your runtime log.'; document.querySelectorAll('.analysis-stage i').forEach((el, i) => el.classList.toggle('done', i < update.stage)); }
      document.querySelectorAll('.stage-rail span').forEach((el, i) => el.classList.toggle('done', i < update.stage));
      const stages = state.stageKind === 'instrument' ? ['Reading your APK', 'Adding your log points', 'Rebuilding the APK', 'Aligning and signing'] : state.stageKind === 'inspect' ? ['Reading your APK', 'Preparing the code browser'] : ['Reading your APK', 'Building the baseline', 'Matching runtime logs', 'Creating the report', 'Finishing'];
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
      state.logRead++; state.customLoading = false; state.apk = selected; state.catalog = null; state.points.clear(); state.result = false; state.instrumentTag = null; state.instrumentProbes = []; state.preferredPlan = null; $('messages-panel').hidden = true; $('next-steps').hidden = true; clearReport();
      window.andrologAnimation?.setStage(0, true);
      $('apk-name').textContent = basename(selected); $('apk-path').textContent = selected; $('apk-path').title = selected;
      $('choose-apk').textContent = 'Change APK ↗'; $('coverage-apk').textContent = basename(selected); $('coverage-apk').title = selected;
      $('custom-count').textContent = 'Place a log in the code'; $('save-apk').hidden = true; $('action-title').textContent = 'Ready to instrument';
      $('action-description').textContent = 'Your original APK stays unchanged.'; updateAvailability(); if (state.log) refreshMessages();
    } catch (error) { toast(error.message, true); }
  }
  $('choose-apk').addEventListener('click', chooseApk); $('coverage-choose-apk').addEventListener('click', chooseApk);
  $('choose-log').addEventListener('click', async () => { try { const selected = await api.chooseLog(); if (!selected) return; state.log = selected; $('log-name').textContent = basename(selected); $('log-name').title = selected; clearReport(); updateAvailability(); refreshMessages(); } catch (e) { toast(e.message, true); } });
  async function refreshMessages() {
    if (!state.log) return;
    const request = ++state.logRead; state.customLoading = true; updateAvailability(); $('messages-panel').hidden = false; $('messages-summary').textContent = 'Reading custom log points…'; $('messages-list').replaceChildren();
    try {
      const plans = api.customPlans ? await api.customPlans($('log-tag').value.trim()) : [];
      if (request !== state.logRead) return;
      const select = $('custom-build'), previous = state.preferredPlan || select.value; select.replaceChildren();
      const none = document.createElement('option'); none.value = ''; none.textContent = plans.length ? 'Choose an instrumented build' : 'No saved build for this APK and tag'; select.appendChild(none);
      for (const plan of plans) { const option = document.createElement('option'); option.value = plan.id; option.textContent = `${new Date(plan.createdAt).toLocaleString()} · ${plan.count} ${plan.count === 1 ? 'point' : 'points'}`; select.appendChild(option); }
      select.value = plans.some(plan => plan.id === previous) ? previous : plans.length === 1 ? plans[0].id : '';
      state.preferredPlan = select.value || null;
      const result = await api.readCustom($('log-tag').value.trim(), select.value); if (request !== state.logRead) return;
      const summary = result.summary;
      if (summary) {
        $('messages-summary').textContent = `${summary.observed} of ${summary.points.length} log points observed`;
        $('custom-note').textContent = 'Not observed means no matching marker was found in this log. Verify that it came from the selected build; incomplete logs can miss execution.';
        for (const point of summary.points) {
          const row = document.createElement('div'); row.className = `message-row point-row ${point.count ? 'observed' : 'unobserved'}`;
          const detail = document.createElement('div'); const message = document.createElement('strong'); message.textContent = point.message;
          const location = document.createElement('small'); location.textContent = `${point.method} · statement ${point.index}`; location.title = point.statement;
          const status = document.createElement('span'); status.className = 'point-status'; status.textContent = point.count ? `Observed · ${point.count.toLocaleString()}×` : 'Not observed';
          detail.append(message, location); row.append(detail, status); $('messages-list').appendChild(row);
        }
      } else {
        $('messages-summary').textContent = result.total ? `${result.total.toLocaleString()} message records · point status unavailable` : 'No custom log points available for this run.';
        $('custom-note').textContent = plans.length ? 'Choose the instrumented build used for this log to see which points were observed.' : 'Point status requires metadata saved when this APK was instrumented on this computer. Older logs show messages only.';
        for (const item of result.messages) {
          const row = document.createElement('div'); row.className = 'message-row';
          const message = document.createElement('span'); message.textContent = item.message;
          const count = document.createElement('strong'); count.textContent = `${item.count.toLocaleString()}×`;
          row.append(message, count); $('messages-list').appendChild(row);
        }
      }
    } catch (error) { if (request === state.logRead) $('messages-summary').textContent = error.message || 'Could not read custom messages.'; } finally { if (request === state.logRead) { state.customLoading = false; updateAvailability(); } }
  }
  $('custom-build').addEventListener('change', () => { state.preferredPlan = $('custom-build').value || null; if (state.report) { clearReport(); updateAvailability(); } refreshMessages(); });
  $('refresh-messages').addEventListener('click', refreshMessages);
  for (const [id, values] of [['preset-essentials', [1,1,0,1,0]], ['preset-all', [1,1,1,1,1]], ['preset-custom', [0,0,0,0,0]]]) {
    $(id).addEventListener('click', () => { document.querySelectorAll('[data-probe]').forEach((el, i) => { el.checked = Boolean(values[i]); }); if (state.report) clearReport(); updateAvailability(); if (id === 'preset-custom' && state.apk && !state.points.size) browseCode(); });
  }
  document.querySelectorAll('[data-probe], #package-prefix, #exclude-libraries').forEach(el => el.addEventListener('change', () => { if (state.report) { clearReport(); updateAvailability(); } }));
  for (const id of ['log-tag', 'coverage-tag']) {
    $(id).addEventListener('input', event => {
      $(id === 'log-tag' ? 'coverage-tag' : 'log-tag').value = event.target.value;
      state.logRead++; state.customLoading = false; state.preferredPlan = null; $('custom-build').replaceChildren(); $('messages-panel').hidden = true; updateAvailability();
      if (state.report) { clearReport(); updateAvailability(); }
    });
    $(id).addEventListener('change', refreshMessages);
  }
  $('instrument').addEventListener('click', async () => {
    const plan = state.points.size ? { apkSha256: state.catalog.apkSha256, points: [...state.points.values()] } : null;
    const selectedOptions = options();
    const output = await run('instrument', () => api.instrument(selectedOptions, plan));
    if (!output) { window.andrologAnimation?.setStage(0); return; }
    window.andrologAnimation?.setStage(5);
    state.preferredPlan = output.planId || null; state.instrumentTag = selectedOptions.tag; state.instrumentProbes = selectedOptions.probes.slice(); $('next-steps').hidden = true;
    state.result = true; $('save-apk').hidden = false; $('action-title').textContent = 'Your APK is ready.'; $('action-description').textContent = 'Instrumented, aligned, and signed. Choose where to save your new APK.'; updateAvailability(); if (state.log) refreshMessages(); toast('Your instrumented APK is ready to save.');
  });
  function quoteShell(value) {
    return api.platform === 'win32' ? `'${value.replace(/'/g, "''")}'` : `'${value.replace(/'/g, "'\\''")}'`;
  }
  function showNextSteps(file, tag) {
    $('terminal-instructions').textContent = api.platform === 'win32'
      ? 'Connect an Android device or emulator, then run these commands in PowerShell.'
      : 'Connect an Android device or emulator, then run these commands in a terminal.';
    $('install-command').textContent = `adb install -r ${quoteShell(file)}`;
    $('logcat-command').textContent = api.platform === 'win32'
      ? `cmd /c "adb logcat -v threadtime -s ${tag}:V > androlog-runtime.log"`
      : `adb logcat -v threadtime -s ${quoteShell(`${tag}:V`)} > androlog-runtime.log`;
    $('next-coverage').textContent = state.instrumentProbes.some(Boolean)
      ? 'Next, open Coverage, choose the original APK and androlog-runtime.log, and analyze using the same logging options. ADB is included in Android SDK Platform Tools.'
      : 'Next, open Coverage and choose androlog-runtime.log to see your custom points. You can also analyze the run to explore them in the report. ADB is included in Android SDK Platform Tools.';
    $('next-steps').hidden = false;
    const reduced = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    $('next-steps').scrollIntoView?.({ behavior: reduced ? 'auto' : 'smooth', block: 'start' });
  }
  $('save-apk').addEventListener('click', async () => {
    try { const file = await api.saveApk(); if (file) {
      $('action-title').textContent = 'Saved. Ready to run.'; $('action-description').textContent = file;
      showNextSteps(file, state.instrumentTag); toast(`Saved ${basename(file)}`);
    } } catch (e) { toast(e.message, true); }
  });
  $('analyze').addEventListener('click', async () => {
    const result = await run('coverage', () => api.analyze(options(), $('custom-build').value)); if (!result) return;
    state.report = result; state.reportGeneration++; $('coverage-meta').textContent = `${basename(state.apk)} · ${basename(state.log)}`;
    $('empty-report').hidden = true; $('report-frame').hidden = false; $('report-frame').src = `androlog://report/current?run=${state.reportGeneration}`;
    drawMetrics(result.coverageSummary); updateAvailability(); refreshMessages(); toast('Your coverage report is ready.');
  });
  $('explore-report').addEventListener('click', () => navigate('explore'));
  for (const id of ['save-report','report-export']) $(id).addEventListener('click', async () => { try { const file = await api.saveReport(); if (file) toast(`Saved ${basename(file)}`); } catch (e) { toast(e.message, true); } });
  function drawMetrics(summary) {
    const grid = $('stats-grid'); grid.replaceChildren();
    for (const [key, name, color] of metricInfo) {
      const metric = summary?.[key]; const total = metric && Number(metric.total); const observed = metric && Number(metric.covered);
      const valid = Number.isFinite(total) && Number.isFinite(observed) && total > 0;
      const card = document.createElement('button'); card.className = 'stat'; card.type = 'button'; card.disabled = !metric;
      const label = document.createElement('span'); label.className = 'stat-title'; label.textContent = name;
      const value = document.createElement('strong'); value.textContent = valid ? `${(100 * observed / total).toFixed(1)}%` : '—';
      const detail = document.createElement('small'); detail.textContent = !metric ? (summary ? 'Probe not selected' : 'Awaiting analysis') : total === 0 ? 'Nothing to cover' : `${observed.toLocaleString()} of ${total.toLocaleString()} observed`;
      const meter = document.createElement('span'); meter.className = 'meter'; const fill = document.createElement('i');
      fill.style.setProperty('--value', `${valid ? Math.max(0, Math.min(100, 100 * observed / total)) : 0}%`); fill.style.setProperty('--color', color); meter.appendChild(fill);
      card.append(label, value, detail, meter); if (metric) card.addEventListener('click', () => openCategory(key)); grid.appendChild(card);
    }
  }
  function openCategory(category) { state.pendingCategory = category; navigate('explore'); sendCategory(); }
  function sendCategory() { if (state.pendingCategory && $('report-frame').contentWindow) { $('report-frame').contentWindow.postMessage({ type: 'androlog.selectCategory', category: state.pendingCategory }, '*'); } }
  $('report-frame').addEventListener('load', sendCategory);
  function updateSettings(values) {
    for (const key of ['platforms','buildTools','java']) $(`settings-${key}`).value = values[key] || '';
    const ready = Boolean(values.platforms && values.buildTools); $('sdk-dot').classList.toggle('ready', ready); $('sdk-summary').textContent = ready ? 'SDK paths configured' : 'Android SDK setup';
  }
  api.settings().then(updateSettings).catch(e => toast(e.message, true));
  document.querySelectorAll('[data-setting]').forEach(btn => btn.addEventListener('click', async () => { try { const result = await api.chooseSetting(btn.dataset.setting); if (result) $(`settings-${btn.dataset.setting}`).value = result; } catch (e) { toast(e.message, true); } }));
  $('settings-save').addEventListener('click', async () => { try { const settings = {}; for (const key of ['platforms','buildTools','java']) settings[key] = $(`settings-${key}`).value; updateSettings(await api.saveSettings(settings)); $('settings-status').textContent = 'Settings saved on this computer.'; toast('Settings saved.'); } catch (e) { toast(e.message, true); } });
  const identity = (method, index) => `${method}\n${index}`;
  async function browseCode() {
    if (!state.apk) { toast('Choose the original APK first.', true); return; }
    const catalog = await run('inspect', () => api.inspectCode()); if (!catalog) return;
    if (state.catalog && state.catalog.apkSha256 !== catalog.apkSha256) {
      state.points.clear(); $('custom-count').textContent = 'Place a log in the code';
      toast('The APK changed. Choose the log points again.');
    }
    state.catalog = catalog;
    state.draft = new Map([...state.points].map(([key, point]) => [key, { ...point }])); state.selected = null; state.selectedClass = null;
    $('class-search').value = ''; $('point-message').value = ''; $('point-message').disabled = true; $('point-label').textContent = 'Select a statement'; $('remove-point').disabled = true;
    const first = [...new Set(state.catalog.methods.map(m => m.className))].sort()[0]; if (first) { revealPackage(first); state.openClasses.add(first); renderClass(first); } else renderClasses(); $('code-dialog').showModal(); updateDraftCount();
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
        branch.addEventListener('toggle', () => { if (!query && branch.isConnected) branch.open ? state.openClasses.add(name) : state.openClasses.delete(name); });
        for (const method of methods.sort((a, b) => a.signature.localeCompare(b.signature))) {
          const button = document.createElement('button'); button.type = 'button'; button.className = 'tree-method';
          button.textContent = method.signature.replace(/^<[^:]+:\s*/, '').replace(/>$/, ''); button.title = method.signature;
          button.addEventListener('click', () => { revealPackage(name); state.openClasses.add(name); renderClass(name, method.signature); }); branch.appendChild(button);
        }
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
  function renderClass(name, selectedMethod = null) {
    state.selectedClass = name; state.selected = null; $('point-message').value = ''; $('point-message').disabled = true; $('remove-point').disabled = true; $('point-label').textContent = 'Select a statement'; $('code-class').textContent = name;
    const lines = $('code-lines'); lines.replaceChildren(); let target = null;
    for (const method of state.catalog.methods.filter(m => m.className === name)) {
      const heading = document.createElement('div'); heading.className = 'code-method'; heading.textContent = method.signature; lines.appendChild(heading); if (method.signature === selectedMethod) target = heading;
      for (const statement of method.statements) {
        const key = identity(method.signature, statement.index); const row = document.createElement('button'); row.type = 'button'; row.className = 'code-line'; row.disabled = !statement.selectable;
        const num = document.createElement('span'); num.className = 'num'; num.textContent = `${state.draft.has(key) ? '● ' : ''}${statement.index}`; num.classList.toggle('marked', state.draft.has(key));
        const text = document.createElement('span'); text.textContent = statement.text; row.append(num, text);
        row.addEventListener('click', () => selectPoint(method, statement, row, num)); lines.appendChild(row);
      }
    }
    renderClasses(); lines.scrollTop = target ? target.offsetTop - lines.offsetTop : 0;
  }
  function selectPoint(method, statement, row, number) {
    $('code-lines').querySelectorAll('.code-line.active').forEach(el => el.classList.remove('active')); row.classList.add('active');
    const key = identity(method.signature, statement.index); state.selected = { key, method, statement, number };
    $('point-label').textContent = `Before statement ${statement.index}`; $('point-label').title = method.signature;
    $('point-message').disabled = false; $('point-message').value = state.draft.get(key)?.message || ''; $('remove-point').disabled = !state.draft.has(key); $('point-message').focus();
  }
  function updateDraftCount() { $('dialog-count').textContent = state.draft.size ? `${state.draft.size} log ${state.draft.size === 1 ? 'point' : 'points'} selected` : 'No log points selected'; }
  $('point-message').addEventListener('input', () => {
    if (!state.selected) return; const { key, method, statement, number } = state.selected; const message = $('point-message').value;
    if (message.trim()) state.draft.set(key, { method: method.signature, index: statement.index, statement: statement.text, message }); else state.draft.delete(key);
    const marked = state.draft.has(key); number.textContent = `${marked ? '● ' : ''}${statement.index}`; number.classList.toggle('marked', marked); $('remove-point').disabled = !marked; updateDraftCount();
  });
  $('remove-point').addEventListener('click', () => { $('point-message').value = ''; $('point-message').dispatchEvent(new Event('input')); });
  function closeCode() { $('code-dialog').close(); }
  $('code-close').addEventListener('click', closeCode); $('code-cancel').addEventListener('click', closeCode);
  $('code-apply').addEventListener('click', () => {
    for (const point of state.draft.values()) if (/[\n\r\0]/.test(point.message) || new TextEncoder().encode(point.message).length > 1000) { toast('Each message must fit on one line and within 1,000 UTF-8 bytes.', true); return; }
    state.points = new Map(state.draft); $('custom-count').textContent = state.points.size ? `${state.points.size} log ${state.points.size === 1 ? 'point' : 'points'} selected` : 'Place a log in the code'; closeCode();
  });
  drawMetrics(null); updateAvailability();
})();
