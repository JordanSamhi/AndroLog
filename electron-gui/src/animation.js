'use strict';
// Anime.js animates only the actual backend stage; it never estimates progress.
(() => {
  const svg = document.getElementById('apk-animation');
  if (!svg) return;
  const scan = document.getElementById('apk-scan'), complete = document.getElementById('apk-complete');
  const labels = ['Ready', 'Reading APK', 'Adding logs', 'Rebuilding APK', 'Signing APK', 'APK ready'];
  const motion = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  let scanAnimation = null, currentStage = 0, analysisActive = false, analysisStage = 0, analysisEffects = [];
  function setStage(stage, instant = false) {
    stage = Math.max(0, Math.min(5, Number(stage) || 0)); currentStage = stage;
    svg.dataset.stage = String(stage); svg.setAttribute('aria-label', labels[stage]);
    document.getElementById('apk-stage-label').textContent = labels[stage];
    scanAnimation?.cancel?.(); scanAnimation = null; window.anime?.utils?.remove?.(scan);
    scan.style.transform = ''; scan.style.opacity = '0'; complete.style.opacity = stage === 5 ? '1' : '0';
    if (stage > 0 && stage < 5) {
      scan.style.opacity = '.8';
      if (!instant && !motion?.matches && typeof window.anime?.animate === 'function') {
        scanAnimation = window.anime.animate(scan, { translateY: [0, 44], opacity: [.15, .9, .15], duration: 1500, loop: true, alternate: true, ease: 'inOut(2)' });
      }
    }
  }
  function setAnalysis(stage, active = true) {
    analysisStage = Math.max(0, Math.min(5, Number(stage) || 0)); analysisActive = active;
    for (const effect of analysisEffects) effect.cancel?.(); analysisEffects = [];
    const stream = document.getElementById('analysis-stream');
    const icons = ['analysis-apk', 'analysis-match', 'analysis-result'].map(id => document.getElementById(id));
    for (const element of [stream, ...icons]) { window.anime?.utils?.remove?.(element); element.style.transform = ''; }
    stream.style.opacity = active ? '1' : '0';
    if (active && !motion?.matches && typeof window.anime?.animate === 'function') {
      analysisEffects.push(window.anime.animate(stream, { strokeDashoffset: [0, -60], duration: 1200, loop: true, ease: 'linear' }));
      const index = analysisStage < 2 ? 0 : analysisStage < 4 ? 1 : 2;
      analysisEffects.push(window.anime.animate(icons[index], { translateY: [0, -5, 0], scale: [1, 1.05, 1], duration: 1600, loop: true, ease: 'inOut(2)' }));
    }
  }
  motion?.addEventListener?.('change', () => { setStage(currentStage, Boolean(motion.matches)); setAnalysis(analysisStage, analysisActive); });
  window.andrologAnimation = Object.freeze({ setStage, setAnalysis }); setStage(0, true); setAnalysis(0, false);
})();
