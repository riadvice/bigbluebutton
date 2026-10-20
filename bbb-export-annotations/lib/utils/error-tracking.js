/* eslint-disable require-jsdoc, valid-jsdoc */

/** Optional error reporting to a Sentry-compatible server: call init() at
 * start-up, pass log records to report(). Settings: the environment's
 * BBB_SENTRY_<KEY>[_<COMPONENT>], else the config's sentry block. */
import * as Sentry from '@sentry/node';
import fs from 'fs';
import {isMainThread} from 'worker_threads';

const RELEASE_FILE = '/etc/bigbluebutton/bigbluebutton-release';
const FLUSH_TIMEOUT = 2000;

const FILTERED = '[Filtered]';

const LEVELS = ['debug', 'info', 'warn', 'error', 'fatal'];
const SEVERITIES = {
  debug: 'debug',
  info: 'info',
  warn: 'warning',
  error: 'error',
  fatal: 'fatal',
};

const SECRET_NAME =
  /pass|secret|token|checksum|salt|api[_-]?key|authori[sz]ation|cookie|pw$/i;
const PERSONAL_NAME = /name$|e-?mail|address$|^ip$|^message$|^text$|^msg$/i;

// The bounded name keeps the scan linear. A value stops at '?' and '=' so
// a URL value (url=https://h/p?token=x) leaves its query pairs to match.
const QUERY_PAIR = /([\w.\-[\]]{1,256})=([^&\s"'<>,;?=]*)/g;
const JSON_PAIR = /"([\w.-]{1,256})"(\s*:\s*)"((?:[^"\\]|\\.)*)"/g;
const IPV4 = new RegExp('(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}' +
  '(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?![\\d.])', 'g');

/** Filters secrets and masks personal data by pair names. */
class Anonymiser {
  constructor(mode) {
    this.mode = mode;
  }

  static parseMode(value) {
    return (value || '').trim().toLowerCase() === 'off' ? 'off' : 'mask';
  }

  isFiltered(name) {
    if (SECRET_NAME.test(name)) return true;
    return this.mode !== 'off' && PERSONAL_NAME.test(name);
  }

  anonymiseText(text) {
    if (typeof text !== 'string' || text === '') return text;
    let result = text.replace(QUERY_PAIR, (pair, name) => {
      return this.isFiltered(name) ? `${name}=${FILTERED}` : pair;
    });
    result = result.replace(JSON_PAIR, (pair, name, colon) => {
      return this.isFiltered(name) ? `"${name}"${colon}"${FILTERED}"` : pair;
    });
    if (this.mode !== 'off') {
      result = result.replace(IPV4, FILTERED);
    }
    return result;
  }

  anonymiseValue(name, value, depth = 0) {
    if (name !== null && value !== null && value !== undefined) {
      if (this.isFiltered(name)) return FILTERED;
    }
    if (typeof value === 'string') return this.anonymiseText(value);
    if (depth > 10 || value === null || typeof value !== 'object') {
      return value;
    }
    if (Array.isArray(value)) {
      return value.map((item) => this.anonymiseValue(null, item, depth + 1));
    }
    const copy = {};
    for (const [key, item] of Object.entries(value)) {
      copy[key] = this.anonymiseValue(key, item, depth + 1);
    }
    return copy;
  }

  anonymiseEvent(event) {
    if (typeof event.message === 'string') {
      event.message = this.anonymiseText(event.message);
    }
    if (event.logentry) {
      event.logentry.message = this.anonymiseText(event.logentry.message);
      // The raw arguments of a log call are often names; the text is enough.
      delete event.logentry.params;
    }
    for (const exception of event.exception?.values || []) {
      exception.value = this.anonymiseText(exception.value);
    }
    for (const breadcrumb of event.breadcrumbs || []) {
      this.anonymiseBreadcrumb(breadcrumb);
    }
    if (event.extra) {
      event.extra = this.anonymiseValue(null, event.extra);
    }
    const request = event.request;
    if (request) {
      request.url = this.anonymiseText(request.url);
      request.query_string = this.anonymiseValue(null, request.query_string);
      request.headers = this.anonymiseValue(null, request.headers);
      request.data = this.anonymiseValue(null, request.data);
      delete request.cookies;
    }
    return event;
  }

  anonymiseBreadcrumb(breadcrumb) {
    breadcrumb.message = this.anonymiseText(breadcrumb.message);
    if (breadcrumb.data) {
      breadcrumb.data = this.anonymiseValue(null, breadcrumb.data);
    }
    return breadcrumb;
  }

  // The raw arguments of the log call are dropped; the text is enough.
  anonymiseLog(log) {
    log.message = this.anonymiseText(String(log.message));
    const attributes = {};
    for (const [name, value] of Object.entries(log.attributes || {})) {
      if (name.startsWith('sentry.message.parameter')) continue;
      attributes[name] = typeof value === 'string' ?
        this.anonymiseText(value) : value;
    }
    log.attributes = attributes;
    return log;
  }
}

function stringify(value) {
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    return String(value);
  }
}

/** A bounded copy of the value that can be serialised. */
function plain(value, depth) {
  if (typeof value === 'bigint' || typeof value === 'symbol') {
    return String(value);
  }
  if (typeof value === 'function') return '[Function]';
  if (value === null || typeof value !== 'object') return value;
  if (value instanceof Error) return String(value);
  if (typeof value.toJSON === 'function') return plain(value.toJSON(), depth);
  if (depth === 0) return Array.isArray(value) ? '[Array]' : '[Object]';
  if (Array.isArray(value)) {
    return value.slice(0, 100).map((item) => plain(item, depth - 1));
  }
  const copy = {};
  for (const [key, item] of Object.entries(value).slice(0, 100)) {
    copy[key] = plain(item, depth - 1);
  }
  return copy;
}

/** Nested objects become dotted attribute names, lists their JSON text. */
function logAttributes(data, prefix = '', result = {}) {
  for (const [key, value] of Object.entries(data)) {
    const name = prefix + key;
    if (value === null || value === undefined) continue;
    if (Array.isArray(value)) {
      result[name] = stringify(value);
    } else if (typeof value === 'object') {
      logAttributes(value, `${name}.`, result);
    } else {
      result[name] = value;
    }
  }
  return result;
}

function trimmed(value) {
  const result = value === null || value === undefined ? '' :
    String(value).trim();
  return result === '' ? null : result;
}

function setting(component, name, env, config) {
  const suffix = component.toUpperCase().replace(/[^A-Z0-9]/g, '_');
  const key = name.toLowerCase().replace(/_(.)/g, (_, c) => c.toUpperCase());
  return trimmed(env[`BBB_SENTRY_${name}_${suffix}`]) ??
    trimmed(env[`BBB_SENTRY_${name}`]) ?? trimmed(config?.[key]);
}

function dsnFor(component, env, config) {
  const dsn = setting(component, 'DSN', env, config);
  return dsn === null || dsn.toLowerCase() === 'off' ? null : dsn;
}

/** fatal means error when the logger has no fatal level. */
function level(value, fallback, hasFatal = true) {
  let name = (trimmed(value) || '').toLowerCase();
  if (name === 'warning') name = 'warn';
  if (name === 'fatal' && !hasFatal) name = 'error';
  return LEVELS.includes(name) ? name : fallback;
}

function sampleRate(value) {
  const text = trimmed(value);
  const rate = text === null ? NaN : Number(text);
  return rate >= 0 && rate <= 1 ? rate : 1.0;
}

function serverName(bbbUrl) {
  try {
    return new URL(trimmed(bbbUrl)).hostname || null;
  } catch {
    return null;
  }
}

function bbbRelease() {
  try {
    for (const line of fs.readFileSync(RELEASE_FILE, 'utf8').split('\n')) {
      if (line.startsWith('BIGBLUEBUTTON_RELEASE=')) {
        return line.substring('BIGBLUEBUTTON_RELEASE='.length).trim();
      }
    }
  } catch {
    // A development checkout has no release file.
  }
  return 'unknown';
}

let enabled = false;
let eventLevel = 'error';
let breadcrumbLevel = 'info';
let componentName;
let recordAnonymiser;

/** Never throws: a bad setting only leaves reporting off. */
export function init(component, options = {}, env = process.env) {
  const {logger = console, hasFatal = true, config} = options;
  const get = (name) => setting(component, name, env, config);
  try {
    const dsn = dsnFor(component, env, config);
    if (dsn === null) return false;
    const dsnHost = new URL(dsn).hostname;
    const issues = level(get('LEVEL'), 'error', hasFatal);
    const breadcrumbs = level(get('BREADCRUMB_LEVEL'), 'info', hasFatal);
    const anonymise = Anonymiser.parseMode(get('ANONYMISE'));
    const anonymiser = new Anonymiser(anonymise);
    const server = serverName(env.BBB_URL);

    Sentry.init({
      dsn,
      environment: get('ENVIRONMENT') ?? 'production',
      release: `${component}@${bbbRelease()}`,
      ...(server && {serverName: server}),
      sampleRate: sampleRate(get('SAMPLE_RATE')),
      initialScope: {tags: {component}},
      sendDefaultPii: false,
      sendClientReports: false,
      shutdownTimeout: FLUSH_TIMEOUT,
      // Errors only: no tracing, sessions, request data or module patching.
      skipOpenTelemetrySetup: true,
      registerEsmLoaderHooks: false,
      defaultIntegrations: false,
      integrations: [
        Sentry.eventFiltersIntegration(),
        Sentry.linkedErrorsIntegration(),
        Sentry.dedupeIntegration(),
        // Exits like Node when the component has no handler; Node turns an
        // unhandled rejection into an uncaught exception, so it lands here.
        Sentry.onUncaughtExceptionIntegration(),
        Sentry.contextLinesIntegration(),
        Sentry.nodeContextIntegration(),
      ],
      // Log entries only for the records that become issues (report()).
      enableLogs: true,
      beforeSend: (event) => anonymiser.anonymiseEvent(event),
      beforeBreadcrumb: (crumb) => anonymiser.anonymiseBreadcrumb(crumb),
      beforeSendLog: (log) => anonymiser.anonymiseLog(log),
    });
    eventLevel = issues;
    breadcrumbLevel = breadcrumbs;
    componentName = component;
    recordAnonymiser = anonymiser;
    enabled = true;

    if (isMainThread) {
      logger.info(`Error reporting to ${dsnHost} enabled for ${component} ` +
        `(issues from ${issues.toUpperCase()}, breadcrumbs from ` +
        `${breadcrumbs.toUpperCase()}, personal data: ${anonymise})`);
    }
    return true;
  } catch (error) {
    logger.warn(`Error reporting not started for ${component}: ${error}`);
    return false;
  }
}

/** Splits log call arguments into text, an error and structured data. */
function parseRecord(args) {
  const words = [];
  const data = {};
  let error;
  for (const arg of args) {
    if (arg instanceof Error) {
      if (error) words.push(String(arg));
      error = error || arg;
    } else if (arg !== null && typeof arg === 'object' &&
        !Array.isArray(arg)) {
      for (const [key, value] of Object.entries(arg)) {
        if (!error && value instanceof Error) {
          error = value;
        } else {
          data[key] = value;
        }
      }
    } else if (arg !== undefined) {
      words.push(stringify(arg));
    }
  }
  return {text: words.join(' '), error, data: plain(data, 4)};
}

/** The logger hook: issues and log entries from LEVEL, breadcrumbs below. */
export function report(recordLevel, args, loggerName) {
  const rank = LEVELS.indexOf(recordLevel);
  if (!enabled || rank < 0 || rank < LEVELS.indexOf(breadcrumbLevel)) return;
  try {
    const {text, error, data} = parseRecord(args);
    const severity = SEVERITIES[recordLevel];
    const hasData = Object.keys(data).length > 0;
    if (rank < LEVELS.indexOf(eventLevel)) {
      Sentry.addBreadcrumb({
        category: loggerName || 'log',
        level: severity,
        message: text || error?.message,
        ...(hasData && {data}),
      });
      return;
    }
    // Not Sentry.withScope: without the OpenTelemetry context manager it
    // hands out the global scope; this record's level and extras would stick.
    const scope = Sentry.getCurrentScope().clone();
    scope.setLevel(severity);
    if (hasData) scope.setExtras(data);
    scope.addEventProcessor((event) => {
      if (error && text) event.message = text;
      if (loggerName) event.logger = loggerName;
      return event;
    });
    if (error) {
      scope.captureException(error);
    } else {
      scope.captureMessage(text);
    }
    // Anonymised by name here, where the data names are still known.
    Sentry.logger[recordLevel](text || error?.message || '', {
      ...logAttributes(recordAnonymiser.anonymiseValue(null, data)),
      component: componentName,
    }, {scope});
  } catch {
    // Reporting must never break logging.
  }
}

/** Sends what is queued for at most 2 seconds; call it before exiting. */
export async function flush() {
  if (!enabled) return true;
  try {
    return await Sentry.flush(FLUSH_TIMEOUT);
  } catch {
    return false;
  }
}
