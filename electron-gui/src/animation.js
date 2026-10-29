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
  let activeEffect = null, currentStage = 0;
  function stopEffect() {
    activeEffect?.cancel?.(); activeEffect = null;
    for (const element of effects.filter(Boolean)) {
      window.anime?.utils?.remove?.(element);
      element.style.opacity = '0'; element.style.transform = '';
    }
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
      activeEffect = window.anime.animate(effects[stage], params);
    }
  }
  motion?.addEventListener?.('change', () => setStage(currentStage, Boolean(motion.matches)));
  window.andrologAnimation = Object.freeze({ setStage }); setStage(0, true);
})();
