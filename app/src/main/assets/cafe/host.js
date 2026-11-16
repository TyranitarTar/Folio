(function() {
  'use strict';
  const canvas = document.getElementById('room'), ctx = canvas.getContext('2d');
  let game, renderer, director, ready = false, configured = false, destroyed = false, hostActive = false;
  let paused = false, handle = null, wakeHandle = null, last = null, frames = 0, paints = 0, lastReport = '', lastCounts = '';
  let settings = {mode:'random', pace:'walk', ambient:true, reduced:false, close:false, counts:{}};
  const signal = (method, data) => { try { window.FolioRoom?.[method](JSON.stringify(data)); } catch (_) {} };
  const snapshot = () => ({ready, active:hostActive && !document.hidden, paused, frames,
    mode:director?.mode || settings.mode, paints, sceneBuilds:renderer?.sceneBuilds || 0, canvas:[canvas.width,canvas.height], dpr:window.devicePixelRatio || 1, ...game?.snapshot()});
  function report(force = false) {
    if (!ready || !configured) return;
    const value = {ready:true, paused, mode:director.mode, busy:game.busy, action:game.action,
      pending:game.pending?.id || null, support:game.actor.support, resting:game.actor.resting || ''};
    const text = JSON.stringify(value);
    if (force || text !== lastReport) { lastReport = text; signal('status', value); }
    const counts = {};
    for (const id of Object.keys(window.LoftGame.ACTIONS)) {
      const n = game.counts[id];
      if (Number.isSafeInteger(n) && n > 0 && n <= 100000) counts[id] = n;
    }
    const saved = JSON.stringify(counts);
    if (saved !== lastCounts) { lastCounts = saved; signal('discoveries', counts); }
  }
  function render() {
    if (ready && !destroyed && hostActive && !document.hidden) { paints++; renderer.render(ctx, game, canvas.width, canvas.height,
      {soft:true, ambient:settings.ambient && !settings.reduced, zoom:settings.close ? 1.45 : 1,
        focus:game.world.project(game.actor.at, game.actor.z).map((v,i) => i ? v-70 : v)}); }
  }
  function resized() {
    const r = canvas.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
    const w=Math.max(1,Math.round(r.width*dpr)),h=Math.max(1,Math.round(r.height*dpr));
    if(canvas.width!==w)canvas.width=w;if(canvas.height!==h)canvas.height=h;
    render();
  }
  function suspend() { if (handle !== null) cancelAnimationFrame(handle); if(wakeHandle!==null)clearTimeout(wakeHandle); handle = null; wakeHandle=null; last = null; }
  function schedule() {
    if (ready && !destroyed && hostActive && !paused && !document.hidden && handle === null && wakeHandle===null) {
      const delay=game.busy?1000/24:settings.reduced?1000:settings.ambient?125:250;
      wakeHandle=setTimeout(()=>{wakeHandle=null;handle=requestAnimationFrame(tick);},delay);
    }
  }
  function tick(now) {
    handle = null;
    if (destroyed || !hostActive || paused || document.hidden) { last = null; return; }
    const dt = last === null ? 0 : Math.max(0, Math.min(1000, now-last));
    last = now;
    director.update(dt); frames++;
    render();
    report(); schedule();
  }
  function configure(value) {
    settings = {...settings, ...value};
    if (!ready) return;
    if (Object.hasOwn(value, 'mode')) director.setMode(value.mode);
    if (Object.hasOwn(game.meta.movement_modes, settings.pace)) game.pace = settings.pace;
    if (value.counts && typeof value.counts === 'object') {
      configured = true;
      for (const id of Object.keys(window.LoftGame.ACTIONS)) {
        const n = value.counts[id];
        if (Number.isSafeInteger(n) && n >= 0 && n <= 100000) game.counts[id] = n;
      }
    }
    render(); report();
  }
  function command(id) {
    if (!ready || destroyed) return false;
    if (id === 'pause') {
      paused = !paused;
      if (paused) suspend(); else schedule();
    } else if (id === 'stop') { director.stop(); }
    else if (id === 'home') {
      const counts = game.counts; game.reset(); game.counts = counts;
      director.setMode('manual'); game.pace = settings.pace;
    } else if (!director.command(id)) return false;
    render(); report(true); suspend();schedule(); return true;
  }
  function active(value) {
    hostActive = Boolean(value);
    if (!hostActive) { suspend(); report(); } else { resized(); schedule(); }
  }
  function pointer(ev) {
    if (!ready || destroyed || !hostActive) return;
    const r = canvas.getBoundingClientRect();
    const [x,y] = renderer.screenToWorld((ev.clientX-r.left)*canvas.width/r.width, (ev.clientY-r.top)*canvas.height/r.height);
    const id = renderer.pick(x, y, 12/r.width*736/(settings.close ? 1.45 : 1));
    if (id) { signal('pick', {prop:id}); return; }
    const point = game.world.inverse([x,y]);
    if (point.every(v => v >= 0 && v <= 5)) { director.command('move', point); report(true); }
  }
  const visibility = () => { suspend(); if (!document.hidden) { resized(); schedule(); } };
  const observer = new ResizeObserver(resized);
  function destroy() {
    destroyed = true; suspend(); observer.disconnect();
    window.removeEventListener('resize', resized); document.removeEventListener('visibilitychange', visibility);
    canvas.removeEventListener('pointerup', pointer);
    if (renderer) {
      for (const c of [renderer.shell, renderer.room, renderer.catLayer, renderer.coverLayer]) { c.width = 1; c.height = 1; }
      renderer.images = {}; renderer.hit.clear();
    }
    renderer = null; game = null; director = null; ready = false; canvas.width = 1; canvas.height = 1;
  }
  window.FolioCafe = {configure, command, active, destroy, snapshot};
  async function start() {
    const [env, meta] = await Promise.all(['environment.json', 'animation_data.json'].map(async path => {
      const response = await fetch(path); if (!response.ok) throw Error('Room data unavailable'); return response.json();
    }));
    const images = {};
    await Promise.all([...new Set([...env.assets.map(a=>a.sprite), ...env.tiles.map(t=>t.path), meta.atlas])].map(path =>
      new Promise((resolve,reject) => { const im = new Image(); im.onload=()=>{images[path]=im;resolve();}; im.onerror=reject; im.src=path; })));
    if (destroyed) return;
    game = new window.LoftGame.Game(env, meta);
    director = new window.CafeDirector.Director(game, window.LoftGame.ACTIONS);
    renderer = new window.LoftRender.Renderer(env, meta, images, (w,h) => {
      const c = document.createElement('canvas'); c.width=w; c.height=h; return c;
    });
    ready = true; configure(settings); document.getElementById('loading').hidden = true;
    canvas.addEventListener('pointerup', pointer);
    window.addEventListener('resize', resized); document.addEventListener('visibilitychange', visibility);
    observer.observe(canvas); resized(); report(true); schedule();
  }
  start().catch(() => {
    if (destroyed) return;
    document.getElementById('loading').textContent = 'Værelset kunne ikke åbnes. Prøv igen.';
    signal('failure', {error:'load'});
  });
})();
