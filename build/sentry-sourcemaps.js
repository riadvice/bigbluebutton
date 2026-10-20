#!/usr/bin/env node
// Run by a browser app's build.sh: node sentry-sourcemaps.js <app> <build folder> <URL path of the app>
// Adds Sentry debug IDs to the built JS and moves the .map files to sourcemaps/<app>, out of the package;
// uploads them only when SENTRY_URL, SENTRY_AUTH_TOKEN, SENTRY_ORG, SENTRY_PROJECT and SENTRY_UPLOAD_CMD are set.
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { spawnSync } = require('child_process');

const SNIPPET = '!function(){try{var e="undefined"!=typeof window?window:"undefined"!=typeof global?global'
  + ':"undefined"!=typeof globalThis?globalThis:"undefined"!=typeof self?self:{},n=(new e.Error).stack;'
  + 'n&&(e._sentryDebugIds=e._sentryDebugIds||{},e._sentryDebugIds[n]="__DEBUG_ID__")}catch(e){}}();';
const SCRIPT = /\.(js|mjs|cjs)$/;
const SOURCE_MAPPING_URL = /^\/\/[#@] sourceMappingURL=.*\n?/gm;
const UPLOAD_SETTINGS = ['SENTRY_URL', 'SENTRY_AUTH_TOKEN', 'SENTRY_ORG', 'SENTRY_PROJECT', 'SENTRY_UPLOAD_CMD'];

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(file) : [file];
  });
}

/** A UUID from the map, so the same build gets the same ID. */
function debugId(content) {
  const b = crypto.createHash('sha256').update(content).digest().subarray(0, 16);
  b[6] = (b[6] & 0x0f) | 0x50;
  b[8] = (b[8] & 0x3f) | 0x80;
  const hex = b.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** A leading "use strict"-like directive must stay first. */
function leadingDirective(source) {
  const code = source.replace(/^(?:\s+|\/\/[^\n]*|\/\*[\s\S]*?\*\/)*/, '');
  const m = code.match(/^(["'])use [^"'\n]*\1/);
  return m ? `${m[0]};` : '';
}

/** Injects the debug ID into file and map, moves both to outFile(.map); false when skipped. */
function injectFile(file, outFile) {
  const source = fs.readFileSync(file, 'utf8');
  const mapText = fs.readFileSync(`${file}.map`, 'utf8');
  const map = JSON.parse(mapText);
  if (/^\/\/# debugId=/m.test(source) || typeof map.mappings !== 'string') return false;
  const id = debugId(mapText);
  // The snippet takes one line, so every mapping moves down one line.
  map.mappings = `;${map.mappings}`;
  map.debug_id = id;
  map.debugId = id;
  const injected = `${leadingDirective(source)}${SNIPPET.replace('__DEBUG_ID__', id)}\n`
    + `${source.replace(/\n?$/, '\n')}//# debugId=${id}\n`;

  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, injected);
  fs.writeFileSync(`${outFile}.map`, JSON.stringify(map));
  fs.rmSync(`${file}.map`);
  fs.rmSync(`${file}.map.gz`, { force: true });

  const served = injected.replace(SOURCE_MAPPING_URL, '');
  fs.writeFileSync(file, served);
  if (fs.existsSync(`${file}.gz`)) fs.writeFileSync(`${file}.gz`, zlib.gzipSync(served, { level: 9 }));
  return true;
}

function upload(app, dir, servedPath) {
  const suffix = app.toUpperCase().replace(/[^A-Z0-9]/g, '_');
  const env = { ...process.env };
  env.SENTRY_PROJECT = env[`SENTRY_PROJECT_${suffix}`] || env.SENTRY_PROJECT;
  const unset = (name) => !(env[name] || '').trim();
  if (UPLOAD_SETTINGS.every(unset)) {
    console.log(`${app}: source maps not uploaded (no SENTRY_* upload settings)`);
    return;
  }
  const missing = [...UPLOAD_SETTINGS, 'BIGBLUEBUTTON_RELEASE'].filter(unset);
  if (missing.length > 0) {
    console.warn(`WARNING ${app}: source maps not uploaded, missing ${missing.join(', ')}`);
    return;
  }
  const release = `${app}@${env.BIGBLUEBUTTON_RELEASE.trim()}`;
  // SENTRY_UPLOAD_CMD may hold arguments (`npx --yes <tool>`): run it through the shell.
  const run = (...args) => spawnSync('sh', ['-c', `${env.SENTRY_UPLOAD_CMD} "$@"`, 'sh', ...args], { env, stdio: 'inherit' });
  // Files are added to an existing release; it may exist already, so this one may fail.
  run('releases', 'new', release);
  const result = run('sourcemaps', 'upload', dir, '--release', release, '--url-prefix', `~${servedPath}`);
  if (result.status === 0) {
    console.log(`${app}: source maps uploaded for ${release}`);
  } else {
    console.warn(`WARNING ${app}: source map upload failed (exit ${result.status}), stack traces stay minified`);
  }
}

function main() {
  const [app, buildDir, servedPath] = process.argv.slice(2);
  if (!app || !buildDir || !servedPath) {
    console.error('usage: node sentry-sourcemaps.js <app> <build dir> <served path>');
    process.exit(2);
  }
  const outDir = path.resolve('sourcemaps', app);
  fs.rmSync(outDir, { recursive: true, force: true });
  const count = walk(buildDir)
    .filter((file) => SCRIPT.test(file) && fs.existsSync(`${file}.map`))
    .filter((file) => injectFile(file, path.join(outDir, path.relative(buildDir, file))))
    .length;
  // Other maps (stylesheets) are not shipped either.
  walk(buildDir).filter((file) => /\.map(\.gz)?$/.test(file)).forEach((file) => {
    if (file.endsWith('.map')) {
      const target = path.join(outDir, path.relative(buildDir, file));
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.copyFileSync(file, target);
    }
    fs.rmSync(file);
  });
  console.log(`${app}: debug IDs injected into ${count} file(s), maps moved to ${outDir}`);
  if (count > 0) upload(app, outDir, servedPath.replace(/\/$/, ''));
}

main();
