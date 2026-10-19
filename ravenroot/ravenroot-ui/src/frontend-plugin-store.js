import { productError } from './product-error.js';
import { validateFrontendPluginManifest } from './frontend-plugin-api.js';

export const FRONTEND_PLUGIN_MANIFEST = 'ravenroot-frontend-plugin.json';
const DATABASE = 'ravenroot-frontend-plugins-v1';
const STORE = 'packages';

function digest(bytes) {
  return crypto.subtle.digest('SHA-256', bytes).then(buffer => `sha256-${btoa(String.fromCharCode(...new Uint8Array(buffer)))}`);
}

export async function packageFromFiles(fileList) {
  const files = [...fileList];
  const manifestFile = files.find(file => file.name === FRONTEND_PLUGIN_MANIFEST);
  if (!manifestFile) throw productError("workspace.error.frontend.plugin.store.package.directory.must.contain", new TypeError(`Package directory must contain ${FRONTEND_PLUGIN_MANIFEST}`), { value1: FRONTEND_PLUGIN_MANIFEST });
  const manifest = validateFrontendPluginManifest(JSON.parse(await manifestFile.text()));
  const root = manifestFile.webkitRelativePath?.slice(0, -FRONTEND_PLUGIN_MANIFEST.length) || '';
  const entries = {};
  for (const file of files) {
    const path = file.webkitRelativePath?.startsWith(root) ? file.webkitRelativePath.slice(root.length) : file.name;
    if (!path || path === FRONTEND_PLUGIN_MANIFEST) continue;
    const bytes = await file.arrayBuffer();
    if (bytes.byteLength > 1024 * 1024) throw productError("workspace.error.frontend.plugin.store.package.file.exceeds.1.mib", new TypeError(`Package file '${path}' exceeds 1 MiB`), { value1: path });
    if (manifest.integrity[path] && await digest(bytes) !== manifest.integrity[path]) {
      throw productError("workspace.error.frontend.plugin.store.package.file.failed.its.integrity.check", new TypeError(`Package file '${path}' failed its integrity check`), { value1: path });
    }
    entries[path] = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  }
  const required = new Set([...manifest.layouts, ...manifest.renderers].map(item => item.entry));
  for (const entry of required) if (!Object.hasOwn(entries, entry)) throw productError("workspace.error.frontend.plugin.store.package.entry.is.missing", new TypeError(`Package entry '${entry}' is missing`), { value1: entry });
  return Object.freeze({ id: manifest.id, manifest, files: Object.freeze(entries), enabled: true,
    installedAt: new Date().toISOString() });
}

function openDatabase(indexedDB = globalThis.indexedDB) {
  if (!indexedDB) return Promise.reject(productError("workspace.error.frontend.plugin.store.indexeddb.is.unavailable.frontend.plugins.cannot.be.installed.in", new Error('IndexedDB is unavailable; frontend plugins cannot be installed in this browser')));
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE, { keyPath: 'id' });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || productError("workspace.error.frontend.plugin.store.could.not.open.frontend.plugin.storage", new Error('Could not open frontend plugin storage')));
    request.onblocked = () => reject(productError("workspace.error.frontend.plugin.store.frontend.plugin.storage.upgrade.is.blocked", new Error('Frontend plugin storage upgrade is blocked')));
  });
}

async function transaction(mode, run, indexedDB) {
  const database = await openDatabase(indexedDB);
  return new Promise((resolve, reject) => {
    const tx = database.transaction(STORE, mode);
    let result;
    try { result = run(tx.objectStore(STORE)); } catch (error) { database.close(); reject(error); return; }
    tx.oncomplete = () => { database.close(); resolve(result); };
    tx.onerror = () => { database.close(); reject(tx.error || productError("workspace.error.frontend.plugin.store.frontend.plugin.storage.failed", new Error('Frontend plugin storage failed'))); };
    tx.onabort = tx.onerror;
  });
}

export function installFrontendPackage(package_, indexedDB) {
  return transaction('readwrite', store => store.put(package_), indexedDB);
}

export function removeFrontendPackage(id, indexedDB) {
  return transaction('readwrite', store => store.delete(id), indexedDB);
}

export async function setFrontendPackageEnabled(id, enabled, indexedDB) {
  const package_ = await frontendPackage(id, indexedDB);
  if (!package_) throw productError("workspace.error.frontend.plugin.store.frontend.package.is.not.installed", new Error(`Frontend package '${id}' is not installed`), { value1: id });
  return installFrontendPackage({ ...package_, enabled: Boolean(enabled) }, indexedDB);
}

export async function frontendPackage(id, indexedDB) {
  const database = await openDatabase(indexedDB);
  return new Promise((resolve, reject) => {
    const tx = database.transaction(STORE, 'readonly');
    const request = tx.objectStore(STORE).get(id);
    request.onsuccess = () => resolve(request.result || null);
    request.onerror = () => reject(request.error || productError("workspace.error.frontend.plugin.store.could.not.read.frontend.package", new Error('Could not read frontend package')));
    tx.oncomplete = () => database.close();
  });
}

export async function listFrontendPackages(indexedDB) {
  const database = await openDatabase(indexedDB);
  return new Promise((resolve, reject) => {
    const tx = database.transaction(STORE, 'readonly');
    const request = tx.objectStore(STORE).getAll();
    request.onsuccess = () => resolve((request.result || []).sort((a, b) => a.manifest.name.localeCompare(b.manifest.name)));
    request.onerror = () => reject(request.error || productError("workspace.error.frontend.plugin.store.could.not.list.frontend.packages", new Error('Could not list frontend packages')));
    tx.oncomplete = () => database.close();
  });
}
