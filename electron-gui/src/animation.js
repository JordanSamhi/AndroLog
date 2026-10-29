'use strict';
// Anime.js animates only the actual backend stage; it never estimates progress.
(() => {
  const svg = document.getElementById('apk-animation');
  if (!svg) return;
  const targets = {
    lid: document.getElementById('apk-lid'), sheet: document.getElementById('apk-sheet'),
    probe: document.getElementById('apk-probe'), seal: document.getElementById('apk-seal')
  };
  const effects = [null, document.getElementById('apk-scan'), document.getElementById('apk-probe-halo'),
    document.getElementById('apk-rebuild-shine'), document.getElementById('apk-sign-halo')];
  const states = [
    { lid: [0, 0, 1], sheet: [0, 35, 0], probe: [26, -15, 0], seal: [0, 8, 0] },
    { lid: [-12, -42, 1], sheet: [0, -24, 1], probe: [26, -15, 0], seal: [0, 8, 0] },
    { lid: [-12, -42, 1], sheet: [0, -24, 1], probe: [0, 0, 1], seal: [0, 8, 0] },
    { lid: [0, 0, 1], sheet: [0, 35, 0], probe: [-18, 16, 0], seal: [0, 8, 0] },
    { lid: [0, 0, 1], sheet: [0, 35, 0], probe: [26, -15, 0], seal: [0, 0, 1] },
    { lid: [0, 0, 1], sheet: [0, 35, 0], probe: [26, -15, 0], seal: [0, 0, 1] }
  ];
  const labels = ['Ready', 'Reading APK', 'Adding logs', 'Rebuilding APK', 'Signing APK', 'APK ready'];
  const motion = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  const ambient = ['apk-orbit', 'apk-sparks', 'apk-shadow'].map(id => document.getElementById(id));
  const packets = document.getElementById('apk-packets'), tape = document.getElementById('apk-tape');
  let activeEffects = [], currentStage = 0, analysisActive = false, analysisStage = 0, analysisEffects = [];
  function stopEffect() {
    for (const effect of activeEffects) effect.cancel?.(); activeEffects = [];
    for (const element of effects.filter(Boolean)) {
      window.anime?.utils?.remove?.(element);
      element.style.opacity = '0'; element.style.transform = '';
    }
    for (const element of ambient) window.anime?.utils?.remove?.(element);
    ambient[0].style.transform = ''; ambient[1].style.opacity = '0'; ambient[1].style.transform = ''; ambient[2].style.opacity = '.65';
    for (const element of [packets, tape]) { window.anime?.utils?.remove?.(element); element.style.opacity = '0'; element.style.transform = ''; }
  }
  function setStage(stage, instant = false) {
    stage = Math.max(0, Math.min(states.length - 1, Number(stage) || 0)); currentStage = stage;
    svg.dataset.stage = String(stage); svg.setAttribute('aria-label', labels[stage]); stopEffect();
    const animated = !instant && !motion?.matches && typeof window.anime?.animate === 'function';
    for (const [key, element] of Object.entries(targets)) {
      const [x, y, opacity] = states[stage][key];
      window.anime?.utils?.remove?.(element);
      if (animated) window.anime.animate(element, { translateX: x, translateY: y, opacity, duration: 560, ease: 'out(3)' });
      else { element.style.transform = `translate(${x}px, ${y}px)`; element.style.opacity = String(opacity); }
    }
    if (animated && effects[stage]) {
      const params = { opacity: [0, .8, 0], duration: 1450, loop: true, ease: 'inOut(2)' };
      if (stage === 1) params.translateY = [0, 34];
      activeEffects.push(window.anime.animate(effects[stage], params));
      activeEffects.push(window.anime.animate(ambient[0], { rotate: [0, 360], duration: 14000, loop: true, ease: 'linear' }));
      activeEffects.push(window.anime.animate(ambient[1], { opacity: [0, .85, .15, .85, 0], translateY: [8, -8], duration: 2400, loop: true, ease: 'inOut(2)' }));
      activeEffects.push(window.anime.animate(ambient[2], { opacity: [.45, .8, .45], duration: 2600, loop: true, ease: 'inOut(2)' }));
      if (stage === 2) activeEffects.push(window.anime.animate(packets, { translateX: [12, -36], translateY: [-12, 35], scale: [1, .5], opacity: [0, 1, 1, 0], duration: 1500, loop: true, ease: 'inOut(2)' }));
      if (stage === 3) activeEffects.push(window.anime.animate(tape, { opacity: [0, .85, .85, 0], translateY: [-16, 0], duration: 1900, loop: true, ease: 'out(3)' }));
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
