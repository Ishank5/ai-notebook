import { apiFetch } from "./api";

// Simple polling in place of a push channel: every few seconds while the tab is visible,
// when the tab comes back into view, and straight after an action (refreshAll).
const loaders = new Set();

/** Reloads everything on screen now, for example right after an action. */
export function refreshAll() {
  loaders.forEach((load) => load());
}

/**
 * Loads `path` now and then again `every` ms after each load while the tab is visible. `every` is
 * a number, or a function of the last data that returns one, so a page can poll faster while
 * something is still processing. A failed reload keeps what is on screen; onError is only called
 * when nothing has loaded yet. Returns a stop function.
 */
export function poll(path, onData, onError, every = 10000) {
  let stopped = false;
  let loaded = false;
  let busy = false;
  let again = false;
  let last = null;
  let timer = null;

  const nextIn = () => (typeof every === "function" ? every(last) : every);
  const visible = () => document.visibilityState === "visible";

  const schedule = () => {
    clearTimeout(timer);
    if (!stopped) timer = setTimeout(tick, nextIn());
  };

  const load = async () => {
    if (stopped) return;
    if (busy) { again = true; return; }
    busy = true;
    try {
      const data = await apiFetch(path);
      if (!stopped) { loaded = true; last = data; onData(data); }
    } catch (err) {
      if (!stopped && !loaded) onError?.(err);
    } finally {
      busy = false;
      if (again && !stopped) { again = false; load(); } else schedule();
    }
  };

  function tick() {
    if (visible()) load();
    else schedule();
  }

  load();
  document.addEventListener("visibilitychange", tick);
  loaders.add(load);
  return () => {
    stopped = true;
    clearTimeout(timer);
    document.removeEventListener("visibilitychange", tick);
    loaders.delete(load);
  };
}
