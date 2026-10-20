/** Sends uncaught and error-boundary errors to bbb-web, which forwards them to Sentry.
 * No session needed. Capped per page, secrets masked; 429 pauses, 401 stops. */

const APP = 'bbb-learning-dashboard';
const MAX_REPORTS_PER_PAGE = 20;
const MAX_BATCH = 10;
const FLUSH_DELAY_MS = 2000;
const DEDUPE_WINDOW_MS = 60000;
/** bbb-web drops bodies over 64 KiB; keepalive requests are limited to 64 KiB too. */
const MAX_BODY = 60000;
const MAX_MESSAGE = 2000;
const MAX_STACK = 16000;
const MAX_SHORT = 200;
const MAX_DEBUG_IDS = 10;
const DEFAULT_RETRY_AFTER_S = 60;

/** Same secret names as the server's Anonymiser.java. */
const SECRET_NAME = /pass|secret|token|checksum|salt|api[_-]?key|authori[sz]ation|cookie|pw$/i;
const QUERY_PAIR = /([\w.\-[\]]{1,256})=([^&\s"'<>,;?=]*)/g;
const JSON_PAIR = /"([\w.-]{1,256})"(\s*:\s*)"((?:[^"\\]|\\.)*)"/g;

function anonymiseText(text) {
  return text
    .replace(QUERY_PAIR, (whole, name) => (SECRET_NAME.test(name) ? `${name}=[Filtered]` : whole))
    .replace(JSON_PAIR, (whole, name, sep) => (
      SECRET_NAME.test(name) ? `"${name}"${sep}"[Filtered]"` : whole));
}

let queue = [];
let sent = 0;
let timer = null;
let pausedUntil = 0;
let stopped = false;
const recent = new Map();
let installed = false;

/** The file URL of each frame, parsed like the relay does (ClientRelay.java). */
const V8_FRAME = /^\s*at (?:(.+?) \()?(.+?):(\d+):(\d+)\)?\s*$/;
const GECKO_FRAME = /^\s*(?:(.*?)@)?(.+?):(\d+):(\d+)\s*$/;
function stackFiles(stack) {
  return stack.split('\n').flatMap((line) => {
    const m = V8_FRAME.exec(line) || GECKO_FRAME.exec(line);
    return m ? [m[2]] : [];
  });
}

// The package build registers each bundle file's debug ID in _sentryDebugIds,
// under the stack of its first line: the top frame is the file.
let debugIdFiles = {};
let debugIdCount = 0;
function bundleDebugIds() {
  // eslint-disable-next-line no-underscore-dangle
  const registry = window._sentryDebugIds;
  if (!registry || typeof registry !== 'object') return debugIdFiles;
  const stacks = Object.keys(registry);
  if (stacks.length !== debugIdCount) {
    debugIdCount = stacks.length;
    debugIdFiles = {};
    stacks.forEach((stack) => {
      const file = stackFiles(stack)[0];
      if (file && typeof registry[stack] === 'string') debugIdFiles[anonymiseText(file)] = registry[stack];
    });
  }
  return debugIdFiles;
}

/** So Sentry finds the source maps of the files in the stack. */
function stackDebugIds(stack) {
  const known = bundleDebugIds();
  const result = {};
  stackFiles(stack).forEach((file) => {
    if (known[file] && Object.keys(result).length < MAX_DEBUG_IDS) result[file] = known[file];
  });
  return Object.keys(result).length > 0 ? result : undefined;
}

/** Same origin as the dashboard, next to bbb-web's API. */
function relayUrl() {
  return `${window.location.origin}/bigbluebutton/client-errors/${APP}`;
}

function describe(error) {
  if (error instanceof Error) {
    return { name: error.name, message: error.message || error.name, stack: error.stack };
  }
  if (typeof error === 'string') return { message: error };
  if (error && typeof error === 'object') {
    if (typeof error.message === 'string') {
      return {
        name: typeof error.name === 'string' ? error.name : undefined,
        message: error.message,
        stack: typeof error.stack === 'string' ? error.stack : undefined,
      };
    }
    try {
      return { message: JSON.stringify(error) };
    } catch (e) {
      return { message: Object.prototype.toString.call(error) };
    }
  }
  return { message: String(error) };
}

function scheduleFlush(delay = FLUSH_DELAY_MS) {
  if (timer !== null || stopped || queue.length === 0) return;
  timer = setTimeout(() => {
    timer = null;
    // eslint-disable-next-line no-use-before-define
    flushErrorReports();
  }, Math.max(delay, pausedUntil - Date.now()));
}

function enqueue(report) {
  if (stopped || sent + queue.length >= MAX_REPORTS_PER_PAGE) return;
  const key = `${report.kind}|${report.name || ''}|${report.message}`;
  const now = Date.now();
  const last = recent.get(key);
  if (last !== undefined && now - last < DEDUPE_WINDOW_MS) return;
  recent.set(key, now);
  queue.push(report);
  scheduleFlush();
}

/** Report an error. Safe to call with anything a `catch` can receive. */
export function reportError(error, { level = 'error', handled = true, context } = {}) {
  const { name, message, stack } = describe(error);
  if (!message) return;
  const cleanStack = stack ? anonymiseText(stack).slice(0, MAX_STACK) : undefined;
  enqueue({
    kind: 'error',
    level,
    message: anonymiseText(message).slice(0, MAX_MESSAGE),
    name: name ? name.slice(0, MAX_SHORT) : undefined,
    stack: cleanStack,
    debugIds: cleanStack ? stackDebugIds(cleanStack) : undefined,
    url: anonymiseText(window.location.href).slice(0, MAX_MESSAGE),
    handled,
    context,
  });
}

/** At most MAX_BATCH items and MAX_BODY characters, always at least one item. */
function nextBatch() {
  const items = [];
  let body = JSON.stringify({ items });
  while (queue.length > 0 && items.length < MAX_BATCH) {
    const candidate = JSON.stringify({ items: [...items, queue[0]] });
    if (items.length > 0 && candidate.length > MAX_BODY) break;
    items.push(queue.shift());
    body = candidate;
  }
  return { items, body };
}

function handleResponse(response) {
  if (response.status === 401) {
    stopped = true;
    queue = [];
  } else if (response.status === 429) {
    const seconds = Number(response.headers && response.headers.get('Retry-After'));
    const wait = Number.isFinite(seconds) && seconds > 0 ? seconds : DEFAULT_RETRY_AFTER_S;
    pausedUntil = Date.now() + wait * 1000;
  }
}

/** One batch at a time; with `hidden` (the tab is going away), all at once. */
async function flushErrorReports(hidden = false) {
  if (timer !== null) {
    clearTimeout(timer);
    timer = null;
  }
  if (stopped || queue.length === 0) return;
  if (Date.now() < pausedUntil) {
    if (!hidden) scheduleFlush();
    return;
  }
  const url = relayUrl();
  const sends = [];
  do {
    const { items, body } = nextBatch();
    sent += items.length;
    let beaconed = false;
    if (hidden && typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
      try {
        beaconed = navigator.sendBeacon(url, new Blob([body], { type: 'application/json' }));
      } catch (e) {
        beaconed = false;
      }
    }
    if (!beaconed) {
      sends.push(fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
        keepalive: body.length <= MAX_BODY,
      }).then(handleResponse, () => undefined));
    }
  } while (hidden && queue.length > 0);
  await Promise.all(sends);
  if (!hidden) scheduleFlush();
}

/** Hooks uncaught errors, rejections and a flush on hide; returns the undo function. */
export function installErrorReporting() {
  if (typeof window === 'undefined' || installed) return () => undefined;
  installed = true;

  const onError = (event) => {
    // Cross-origin scripts only say "Script error.": nothing to act on.
    if (!event.error && (!event.message || event.message === 'Script error.')) return;
    reportError(event.error || event.message, { handled: false });
  };
  const onRejection = (event) => reportError(event.reason, { handled: false });
  const onHidden = () => {
    if (document.visibilityState === 'hidden') flushErrorReports(true);
  };

  window.addEventListener('error', onError);
  window.addEventListener('unhandledrejection', onRejection);
  document.addEventListener('visibilitychange', onHidden);
  window.addEventListener('pagehide', onHidden);

  return () => {
    window.removeEventListener('error', onError);
    window.removeEventListener('unhandledrejection', onRejection);
    document.removeEventListener('visibilitychange', onHidden);
    window.removeEventListener('pagehide', onHidden);
    installed = false;
  };
}
