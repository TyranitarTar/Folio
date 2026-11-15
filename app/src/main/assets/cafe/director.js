(function(root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.CafeDirector = api;
})(typeof window === 'undefined' ? globalThis : window, function() {
  'use strict';
  const routines = {
    morning: ['groom', 'eat', 'drink', 'window', 'perch_watch', 'sofa'],
    playful: ['ball', 'feather', 'tunnel', 'box', 'scratch', 'drink', 'groom'],
    climbing: ['step', 'perch', 'perch_groom', 'perch_sleep', 'bookshelf', 'bookshelf_sleep', 'down', 'sleep'],
    tour: ['eat', 'drink', 'eat_far', 'drink_far', 'scratch', 'step', 'perch', 'perch_watch',
      'perch_groom', 'perch_sleep', 'bookshelf', 'bookshelf_sleep', 'box', 'tunnel', 'ball',
      'feather', 'window', 'door', 'groom', 'sleep', 'sofa']
  };
  class Director {
    constructor(game, actions, random = Math.random) {
      this.game = game; this.actions = actions; this.random = random;
      this.mode = 'random'; this.index = 0; this.wait = 0; this.last = null;
    }
    setMode(mode) {
      if (!['manual', 'random', ...Object.keys(routines)].includes(mode)) return false;
      if (mode === this.mode) return true;
      this.mode = mode; this.index = 0; this.wait = 0; this.game.auto = false;
      this.game.pending = null;
      return true;
    }
    command(id, target = null) {
      this.setMode('manual');
      return this.game.command(id, target);
    }
    stop() { this.setMode('manual'); this.game.stop(); }
    update(dt) {
      this.game.update(dt);
      // The original engine records every movement step for offline review.
      // Bound that debug log for an indefinitely open phone companion.
      if (this.game.history.length > 256) this.game.history.splice(0, this.game.history.length - 256);
      if (this.game.busy || this.game.pending || this.mode === 'manual') { this.wait = 0; return; }
      this.wait += dt;
      if (this.wait < 7000) return;
      this.wait = 0;
      let id;
      if (this.mode === 'random') {
        const choices = Object.keys(this.actions).filter(k => k !== this.last);
        id = choices[Math.min(choices.length - 1, Math.max(0, Math.floor(this.random() * choices.length)))];
      } else {
        const route = routines[this.mode];
        id = route[this.index++ % route.length];
      }
      if (this.game.command(id)) this.last = id;
    }
  }
  return { Director, routines };
});
