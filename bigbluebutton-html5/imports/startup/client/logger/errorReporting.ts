/* eslint-disable @typescript-eslint/no-explicit-any */
/** With clientLog.sentry.enabled, sends uncaught errors and logger records to bbb-web,
 * which forwards them to Sentry. Capped per page, secrets masked; 429 pauses, 401 stops. */

type ReportLevel = 'debug' | 'info' | 'warning' | 'error' | 'fatal';
type ReportContext = Record<string, string | number | boolean | null>;

interface ClientBreadcrumb {
  category: string;
  level: ReportLevel;
  message: string;
  timestamp: number;
}

interface ClientReport {
  kind: 'error' | 'log';
  level: ReportLevel;
  message: string;
  name?: string;
  stack?: string;
  /** Sentry debug ID of each bundle file in the stack, by file URL. */
  debugIds?: Record<string, string>;
  url?: string;
  handled: boolean;
  context?: ReportContext;
  breadcrumbs: ClientBreadcrumb[];
}

const APP = 'bbb-html5';
const MAX_REPORTS_PER_PAGE = 20;
const MAX_BATCH = 10;
const MAX_BREADCRUMBS = 20;
const FLUSH_DELAY_MS = 2000;
const DEDUPE_WINDOW_MS = 60000;
/** bbb-web drops bodies over 64 KiB; keepalive requests are limited to 64 KiB too. */
const MAX_BODY = 60000;
const MAX_MESSAGE = 2000;
const MAX_STACK = 16000;
const MAX_CRUMB = 500;
const MAX_SHORT = 200;
const MAX_CONTEXT_KEYS = 20;
const MAX_DEBUG_IDS = 10;
const DEFAULT_RETRY_AFTER_S = 60;

/** Same secret names as the server's Anonymiser.java. */
const SECRET_NAME = /pass|secret|token|checksum|salt|api[_-]?key|authori[sz]ation|cookie|pw$/i;
const QUERY_PAIR = /([\w.\-[\]]{1,256})=([^&\s"'<>,;?=]*)/g;
const JSON_PAIR = /"([\w.-]{1,256})"(\s*:\s*)"((?:[^"\\]|\\.)*)"/g;

function anonymiseText(text: string): string {
  return text
    .replace(QUERY_PAIR, (whole, name: string) => (SECRET_NAME.test(name) ? `${name}=[Filtered]` : whole))
    .replace(JSON_PAIR, (whole, name: string, sep: string) => (
      SECRET_NAME.test(name) ? `"${name}"${sep}"[Filtered]"` : whole));
}

// bunyan levels
const INFO = 30;
const WARN = 40;
const ERROR = 50;
const FATAL = 60;
const LEVELS: Record<string, number> = {
  trace: 10, debug: 20, info: INFO, warn: WARN, warning: WARN, error: ERROR, fatal: FATAL,
};

function parseLevel(value: unknown): number {
  const level = typeof value === 'string' ? LEVELS[value.trim().toLowerCase()] : undefined;
  return level ?? ERROR;
}

/** Records from info up, so lower ones become breadcrumbs. */
export function streamLevel(value: unknown): number {
  return Math.min(INFO, parseLevel(value));
}

function levelName(level: number): ReportLevel {
  if (level >= FATAL) return 'fatal';
  if (level >= ERROR) return 'error';
  if (level >= WARN) return 'warning';
  if (level >= INFO) return 'info';
  return 'debug';
}

/** `null` while the settings are not loaded. */
let enabled: boolean | null = null;
let reportLevel = ERROR;
let endpoint: () => string | null = () => null;
let queue: ClientReport[] = [];
let sent = 0;
let timer: ReturnType<typeof setTimeout> | null = null;
let pausedUntil = 0;
let stopped = false;
const recent = new Map<string, number>();
let breadcrumbs: ClientBreadcrumb[] = [];
let installed = false;

/** Next to bbb-web's API, also when the client is mounted under a prefix. */
export function relayUrl(sessionToken: string | null | undefined): string | null {
  if (!sessionToken || typeof window === 'undefined') return null;
  const { origin, pathname } = window.location;
  const prefix = (pathname.match('^(.*)/html5client/?') || [])[1] || '';
  return `${origin}${prefix}/bigbluebutton/client-errors/${APP}?sessionToken=${encodeURIComponent(sessionToken)}`;
}

/** Once the settings are loaded: enabled sends what has queued, disabled drops it. */
export function configureErrorReporting(
  settings: { enabled?: boolean; level?: string } | undefined,
  getUrl: () => string | null,
): void {
  enabled = settings?.enabled === true;
  reportLevel = parseLevel(settings?.level);
  endpoint = getUrl;
  if (enabled) scheduleFlush();
  else queue = [];
}

function addBreadcrumb(category: string, level: ReportLevel, message: string): void {
  if (!message) return;
  breadcrumbs.push({
    category: category.slice(0, 50),
    level,
    message: anonymiseText(message).slice(0, MAX_CRUMB),
    timestamp: Date.now() / 1000,
  });
  if (breadcrumbs.length > MAX_BREADCRUMBS) breadcrumbs = breadcrumbs.slice(-MAX_BREADCRUMBS);
}

function describe(error: unknown): { name?: string; message: string; stack?: string } {
  if (error instanceof Error) {
    return { name: error.name, message: error.message || error.name, stack: error.stack };
  }
  if (typeof error === 'string') return { message: error };
  if (error && typeof error === 'object') {
    const candidate = error as { message?: unknown; name?: unknown; stack?: unknown };
    if (typeof candidate.message === 'string') {
      return {
        name: typeof candidate.name === 'string' ? candidate.name : undefined,
        message: candidate.message,
        stack: typeof candidate.stack === 'string' ? candidate.stack : undefined,
      };
    }
    try {
      return { message: JSON.stringify(error) };
    } catch {
      return { message: Object.prototype.toString.call(error) };
    }
  }
  return { message: String(error) };
}

/** The file URL of each frame, parsed like the relay does (ClientRelay.java). */
const V8_FRAME = /^\s*at (?:(.+?) \()?(.+?):(\d+):(\d+)\)?\s*$/;
const GECKO_FRAME = /^\s*(?:(.*?)@)?(.+?):(\d+):(\d+)\s*$/;
function stackFiles(stack: string): string[] {
  return stack.split('\n').flatMap((line) => {
    const m = V8_FRAME.exec(line) || GECKO_FRAME.exec(line);
    return m ? [m[2]] : [];
  });
}

// The package build registers each bundle file's debug ID in _sentryDebugIds,
// under the stack of its first line: the top frame is the file.
let debugIdFiles: Record<string, string> = {};
let debugIdCount = 0;
function bundleDebugIds(): Record<string, string> {
  // eslint-disable-next-line no-underscore-dangle
  const registry = (globalThis as any)._sentryDebugIds;
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
function stackDebugIds(stack: string): Record<string, string> | undefined {
  const known = bundleDebugIds();
  const result: Record<string, string> = {};
  stackFiles(stack).forEach((file) => {
    if (known[file] && Object.keys(result).length < MAX_DEBUG_IDS) result[file] = known[file];
  });
  return Object.keys(result).length > 0 ? result : undefined;
}

function anonymiseContext(context?: ReportContext): ReportContext | undefined {
  if (!context) return undefined;
  const result: ReportContext = {};
  Object.keys(context).slice(0, MAX_CONTEXT_KEYS).forEach((key) => {
    const value = context[key];
    if (value === undefined) return;
    result[key] = typeof value === 'string' ? anonymiseText(value).slice(0, MAX_SHORT) : value;
  });
  return result;
}

function pageUrl(): string | undefined {
  if (typeof window === 'undefined') return undefined;
  return anonymiseText(window.location.href).slice(0, MAX_MESSAGE);
}

function enqueue(report: ClientReport): void {
  if (enabled === false || stopped || sent + queue.length >= MAX_REPORTS_PER_PAGE) return;
  const key = `${report.kind}|${report.name ?? ''}|${report.message}`;
  const now = Date.now();
  const last = recent.get(key);
  if (last !== undefined && now - last < DEDUPE_WINDOW_MS) return;
  recent.set(key, now);
  queue.push(report);
  scheduleFlush();
}

interface ReportOptions {
  level?: ReportLevel;
  handled?: boolean;
  context?: ReportContext;
}

/** Report an error. Safe to call with anything a `catch` can receive. */
function reportError(error: unknown, options: ReportOptions = {}): void {
  const { name, message, stack } = describe(error);
  if (!message) return;
  const cleanStack = stack ? anonymiseText(stack).slice(0, MAX_STACK) : undefined;
  enqueue({
    kind: 'error',
    level: options.level ?? 'error',
    message: anonymiseText(message).slice(0, MAX_MESSAGE),
    name: name ? name.slice(0, MAX_SHORT) : undefined,
    stack: cleanStack,
    debugIds: cleanStack ? stackDebugIds(cleanStack) : undefined,
    url: pageUrl(),
    handled: options.handled ?? true,
    context: anonymiseContext(options.context),
    breadcrumbs: breadcrumbs.slice(),
  });
}

/** Report a log record: worth an issue, but not an exception. */
function reportLog(level: ReportLevel, message: string, context?: ReportContext): void {
  if (!message) return;
  enqueue({
    kind: 'log',
    level,
    message: anonymiseText(message).slice(0, MAX_MESSAGE),
    url: pageUrl(),
    handled: true,
    context: anonymiseContext(context),
    breadcrumbs: breadcrumbs.slice(),
  });
}

/** Logged by the client's window listeners: already reported from the window events. */
const WINDOW_LOG_CODES = [
  'runtime_error', // ErrorBoundary (enableRuntimeErrorLogging)
  'unhandled_rejection',
  'startup_error', // client/meetingClient.jsx
  'ErrorBoundaryWithReload.GlobalError',
  'ErrorBoundaryWithReload.UnhandledRejection',
];

/** The error a bunyan record carries: `err` (stdSerializers) or the extraInfo error fields. */
function recordError(rec: Record<string, any>): { name?: string; message: string; stack?: string } | null {
  const { err, extraInfo } = rec;
  if (err && typeof err === 'object' && typeof err.stack === 'string') {
    return { name: err.name, message: err.message || rec.msg, stack: err.stack };
  }
  if (extraInfo && typeof extraInfo === 'object' && typeof extraInfo.errorStack === 'string') {
    return {
      name: typeof extraInfo.errorName === 'string' ? extraInfo.errorName : undefined,
      message: String(extraInfo.errorMessage || rec.msg || ''),
      stack: extraInfo.errorStack,
    };
  }
  return null;
}

/** At or above the level: report; info and warn below it: breadcrumb. */
function reportLogRecord(rec: Record<string, any>, clientBuild?: string): void {
  if (!rec || typeof rec.level !== 'number' || rec.level < INFO) return;
  const logCode = typeof rec.logCode === 'string' ? rec.logCode : undefined;
  if (logCode && WINDOW_LOG_CODES.includes(logCode)) return;
  const msg = typeof rec.msg === 'string' ? rec.msg : '';
  if (rec.level < reportLevel) {
    addBreadcrumb(logCode || 'log', levelName(rec.level), msg || logCode || '');
    return;
  }
  const context: ReportContext = { logCode: logCode ?? null };
  // HTML5_CLIENT_VERSION is the settings.yml placeholder the package build replaces
  if (clientBuild && clientBuild !== 'HTML5_CLIENT_VERSION') context.clientBuild = clientBuild;
  const error = recordError(rec);
  if (error && error.message) {
    reportError(error, { level: levelName(rec.level), context: { ...context, logMessage: msg } });
  } else {
    reportLog(levelName(rec.level), msg || logCode || '', context);
  }
}

/** A browser-bunyan stream feeding reportLogRecord; it never writes anywhere itself. */
export class ErrorReportingStream {
  // eslint-disable-next-line class-methods-use-this
  write(rec: Record<string, any>): void {
    try {
      reportLogRecord(rec, (window as any).meetingClientSettings?.public?.app?.html5ClientBuild);
    } catch {
      // Reporting must never break logging.
    }
  }
}

function scheduleFlush(delay = FLUSH_DELAY_MS): void {
  if (timer !== null || enabled !== true || stopped || queue.length === 0) return;
  timer = setTimeout(() => {
    timer = null;
    flushErrorReports();
  }, Math.max(delay, pausedUntil - Date.now()));
}

/** At most MAX_BATCH items and MAX_BODY characters, always at least one item. */
function nextBatch(): { items: ClientReport[]; body: string } {
  const items: ClientReport[] = [];
  let body = JSON.stringify({ items });
  while (queue.length > 0 && items.length < MAX_BATCH) {
    const candidate = JSON.stringify({ items: [...items, queue[0]] });
    if (items.length > 0 && candidate.length > MAX_BODY) break;
    items.push(queue.shift() as ClientReport);
    body = candidate;
  }
  return { items, body };
}

function retryAfterMs(response: Response): number {
  const seconds = Number(response.headers?.get?.('Retry-After'));
  return (Number.isFinite(seconds) && seconds > 0 ? seconds : DEFAULT_RETRY_AFTER_S) * 1000;
}

function handleResponse(response: Response): void {
  if (response.status === 401) {
    stopped = true;
    queue = [];
  } else if (response.status === 429) {
    pausedUntil = Date.now() + retryAfterMs(response);
  }
}

/** One batch at a time; with `hidden` (the tab is going away), all at once. */
async function flushErrorReports(hidden = false): Promise<void> {
  if (timer !== null) {
    clearTimeout(timer);
    timer = null;
  }
  if (enabled !== true || stopped || queue.length === 0) return;
  if (Date.now() < pausedUntil) {
    if (!hidden) scheduleFlush();
    return;
  }
  const url = endpoint();
  if (!url) return;
  const sends: Promise<void>[] = [];
  do {
    const { items, body } = nextBatch();
    sent += items.length;
    let beaconed = false;
    if (hidden && typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
      try {
        beaconed = navigator.sendBeacon(url, new Blob([body], { type: 'application/json' }));
      } catch {
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
export function installErrorReporting(): () => void {
  if (typeof window === 'undefined' || installed) return () => undefined;
  installed = true;

  const onError = (event: ErrorEvent): void => {
    // Cross-origin scripts only say "Script error.": nothing to act on.
    if (!event.error && (!event.message || event.message === 'Script error.')) return;
    reportError(event.error ?? event.message, { handled: false });
  };
  const onRejection = (event: PromiseRejectionEvent): void => {
    reportError(event.reason, { handled: false });
  };
  const onHidden = (): void => {
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
