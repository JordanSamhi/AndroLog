'use strict';
// The illustration follows actual backend stages; it does not estimate progress.
(() => {
  const svg = document.getElementById('apk-animation');
  if (!svg) return;
  const targets = {
    lid: document.getElementById('apk-lid'), sheet: document.getElementById('apk-sheet'),
    probe: document.getElementById('apk-probe'), seal: document.getElementById('apk-seal')
  };
  const states = [
    { lid: [0, 0, 1], sheet: [0, 35, 0], probe: [26, -15, 0], seal: [0, 8, 0] },
    { lid: [-12, -42, 1], sheet: [0, -24, 1], probe: [26, -15, 0], seal: [0, 8, 0] },
    { lid: [-12, -42, 1], sheet: [0, -24, 1], probe: [0, 0, 1], seal: [0, 8, 0] },
    { lid: [0, 0, 1], sheet: [0, 35, 0], probe: [-18, 16, 0], seal: [0, 8, 0] },
    { lid: [0, 0, 1], sheet: [0, 35, 0], probe: [26, -15, 0], seal: [0, 0, 1] },
    { lid: [0, 0, 1], sheet: [0, 35, 0], probe: [26, -15, 0], seal: [0, 0, 1] }
  ];
  const labels = ['Ready', 'Reading APK', 'Adding logs', 'Rebuilding APK', 'Signing APK', 'APK ready'];
  const prefersReducedMotion = () => typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  function setStage(stage, instant = false) {
    stage = Math.max(0, Math.min(states.length - 1, Number(stage) || 0));
    svg.dataset.stage = String(stage); svg.setAttribute('aria-label', labels[stage]);
    for (const [key, element] of Object.entries(targets)) {
      const [x, y, opacity] = states[stage][key];
      if (window.anime?.utils?.remove) window.anime.utils.remove(element);
      if (!instant && !prefersReducedMotion() && window.anime?.animate) {
        window.anime.animate(element, { translateX: x, translateY: y, opacity, duration: 560, ease: 'out(3)' });
      } else {
        element.style.transform = `translate(${x}px, ${y}px)`; element.style.opacity = String(opacity);
      }
    }
  }
  window.andrologAnimation = Object.freeze({ setStage }); setStage(0, true);
})();
