import "./styles.css";
import { validateBundleFiles } from "./bundles.js";
import { CONTRACTS, templateSelection } from "./registry.js";
import { createPackage } from "./package-format.js";
import { validateValue } from "./schema.js";
import type { BundlePayload, ConfigurationSelection, PackageTarget, SecretInput, TargetKind, ValueSchema } from "./types.js";

const app = document.querySelector<HTMLElement>("#app");
if (!app) throw new Error("Missing app root");

app.innerHTML = `
  <h1>Ravenroot configuration packager</h1>
  <p class="lede">Build a versioned, self-contained package from readable operator settings. Secrets stay in this browser and previews are redacted.</p>
  <section class="panel"><h2>1. Target</h2><div class="grid">
    <label>Target kind<select id="target-kind"><option value="compose">Local Compose</option><option value="kubernetes">Kubernetes / Helm</option><option value="prestart">Embedding pre-start</option></select></label>
    <label>Target identity<input id="target-id" required placeholder="production-eu" /></label>
    <label>Tenant identities<input id="tenants" required placeholder="tenant-a,tenant-b" /></label>
  </div><div id="target-options" class="grid"></div></section>
  <section class="panel"><h2>2. Node or backend family</h2><div class="row">
    <label>Contract<select id="contract"></select></label><button id="add-contract">Add configuration</button>
  </div><div id="configurations"></div></section>
  <section class="panel"><h2>3. Credentials</h2><p><small>Add an existing target secret reference or an embedded value encrypted with Argon2id and AES-256-GCM.</small></p>
    <div class="grid"><label>Mode<select id="secret-mode"><option value="target-reference">Target-side reference</option><option value="embedded">Encrypted embedded value</option></select></label>
    <label>Binding id<input id="secret-id" placeholder="broker-password" /></label><label>Runtime environment key<input id="secret-key" placeholder="RAVENROOT_AMQP091_CREDENTIAL_..." /></label></div>
    <div id="secret-coordinates" class="grid"></div>
    <button id="add-secret" class="secondary">Add secret binding</button><div id="secrets"></div>
    <label>Package password<input id="password" type="password" autocomplete="new-password" minlength="12" /></label></section>
  <section class="panel"><h2>4. Prebuilt bundles</h2><p><small>Select each closed bundle directory required by the chosen families. Source builds are never run.</small></p>
    <input id="bundles" type="file" webkitdirectory multiple /><div id="bundle-status"></div></section>
  <section class="panel"><h2>5. Preview and export</h2><div class="row"><button id="preview" class="secondary">Refresh redacted preview</button><button id="export">Export .rrcfg</button></div>
    <p id="message"></p><pre id="plan">No package prepared.</pre></section>`;

const contractSelect = document.querySelector<HTMLSelectElement>("#contract") as HTMLSelectElement;
for (const contract of CONTRACTS) {
  const option = document.createElement("option"); option.value = contract.id; option.textContent = `${contract.family} — ${contract.title}`; contractSelect.append(option);
}

const selections: ConfigurationSelection[] = [];
const secrets: SecretInput[] = [];
let bundles: BundlePayload[] = [];
let targetOptions: Record<string, string | boolean> = {};

const TARGET_FIELDS: Readonly<Record<TargetKind, readonly { name: string; label: string; kind?: "boolean" | "command"; placeholder?: string }[]>> = {
  compose: [{ name: "verifyBaseUrl", label: "Verification base URL", placeholder: "http://127.0.0.1:8080" }],
  kubernetes: [
    { name: "namespace", label: "Namespace", placeholder: "default" }, { name: "release", label: "Helm release", placeholder: "ravenroot" },
    { name: "deploymentName", label: "Deployment", placeholder: "ravenroot-ravenroot" }, { name: "chart", label: "Chart path", placeholder: "deploy/helm/ravenroot" },
    { name: "baseImage", label: "Pinned base image", placeholder: "ravenroot@sha256:…" }, { name: "derivedImage", label: "Derived image", placeholder: "registry.example/ravenroot:configured" },
    { name: "pushImage", label: "Push derived image", kind: "boolean" }, { name: "verifyBaseUrl", label: "Verification base URL", placeholder: "https://ravenroot.example" }
  ],
  prestart: [{ name: "restartCommandJson", label: "Restart command (one argument per line)", kind: "command" },
    { name: "verifyCommandJson", label: "Effective configuration verification command (one argument per line)", kind: "command" },
    { name: "verifyBaseUrl", label: "Verification base URL", placeholder: "http://127.0.0.1:8080" }]
};

function message(value: string, error = false): void {
  const node = document.querySelector<HTMLElement>("#message") as HTMLElement; node.textContent = value; node.className = error ? "error" : "success";
}

function targetKind(): TargetKind { return (document.querySelector<HTMLSelectElement>("#target-kind") as HTMLSelectElement).value as TargetKind; }

function renderTargetOptions(): void {
  targetOptions = {};
  const root = document.querySelector<HTMLElement>("#target-options") as HTMLElement; root.replaceChildren();
  for (const field of TARGET_FIELDS[targetKind()]) {
    const label = document.createElement("label"); label.append(document.createTextNode(field.label));
    const input = document.createElement(field.kind === "command" ? "textarea" : "input") as HTMLInputElement | HTMLTextAreaElement;
    if (input instanceof HTMLInputElement && field.kind === "boolean") input.type = "checkbox";
    if (field.placeholder) input.setAttribute("placeholder", field.placeholder);
    input.dataset.targetOption = field.name;
    input.addEventListener("input", () => {
      if (input instanceof HTMLInputElement && field.kind === "boolean") targetOptions[field.name] = input.checked;
      else if (field.kind === "command") targetOptions[field.name] = JSON.stringify(input.value.split("\n").map((part) => part.trim()).filter(Boolean));
      else if (input.value) targetOptions[field.name] = input.value; else delete targetOptions[field.name];
    });
    label.append(input); root.append(label);
  }
  renderSecretCoordinates();
}

function emptyValue(schema: ValueSchema): unknown {
  switch (schema.kind) {
    case "string": return schema.allowed?.[0] ?? "";
    case "integer": return schema.minimum ?? 0;
    case "boolean": return false;
    case "null": return null;
    case "nullable": return null;
    case "union": return emptyValue(schema.choices[0]!);
    case "array": return [];
    case "map": return {};
    case "object": return Object.fromEntries(Object.entries(schema.properties).filter(([key]) => !(schema.optional ?? []).includes(key)).map(([key, value]) => [key, emptyValue(value)]));
  }
}

function schemaControl(schema: ValueSchema, current: unknown, update: (value: unknown) => void, labelText: string): HTMLElement {
  const composite = schema.kind === "object" || schema.kind === "map" || schema.kind === "array" || schema.kind === "union";
  const wrapper = document.createElement(composite ? "fieldset" : "label");
  if (wrapper instanceof HTMLFieldSetElement) { const legend = document.createElement("legend"); legend.textContent = labelText; wrapper.append(legend); }
  else wrapper.append(document.createTextNode(labelText));
  if (schema.kind === "union") {
    const select = document.createElement("select");
    schema.choices.forEach((_, index) => { const option = document.createElement("option"); option.value = String(index); option.textContent = `Variant ${index + 1}`; select.append(option); });
    const slot = document.createElement("div");
    const matching = schema.choices.findIndex((choice) => { try { validateValue(choice, current); return true; } catch { return false; } });
    select.value = String(Math.max(0, matching));
    const refresh = (replace: boolean): void => { const value = replace ? emptyValue(schema.choices[Number(select.value)]!) : current; if (replace) update(value); slot.replaceChildren(schemaControl(schema.choices[Number(select.value)]!, value, update, labelText)); };
    select.addEventListener("change", () => refresh(true)); wrapper.append(select, slot); refresh(false); return wrapper;
  }
  if (schema.kind === "object") {
    const object = (current && typeof current === "object" && !Array.isArray(current) ? current : emptyValue(schema)) as Record<string, unknown>;
    for (const [name, child] of Object.entries(schema.properties)) wrapper.append(schemaControl(child, object[name], (value) => { object[name] = value; update(object); }, name));
    return wrapper;
  }
  if (schema.kind === "array") {
    const array = Array.isArray(current) ? current : [];
    const list = document.createElement("div");
    const refresh = (): void => {
      list.replaceChildren(...array.map((item, index) => {
        const row = document.createElement("div"); row.className = "row";
        row.append(schemaControl(schema.items, item, (value) => { array[index] = value; update(array); }, `${labelText} ${index + 1}`));
        const remove = document.createElement("button"); remove.type = "button"; remove.className = "secondary"; remove.textContent = "Remove";
        remove.addEventListener("click", () => { array.splice(index, 1); update(array); refresh(); }); row.append(remove); return row;
      }));
    };
    const add = document.createElement("button"); add.type = "button"; add.className = "secondary"; add.textContent = `Add ${labelText}`;
    add.addEventListener("click", () => { array.push(emptyValue(schema.items)); update(array); refresh(); }); wrapper.append(list, add); refresh(); return wrapper;
  }
  if (schema.kind === "map") {
    const map = (current && typeof current === "object" && !Array.isArray(current) ? current : {}) as Record<string, unknown>;
    const list = document.createElement("div");
    const refresh = (): void => {
      list.replaceChildren(...Object.entries(map).map(([name, item]) => {
        const row = document.createElement("div"); row.className = "panel";
        const key = document.createElement("input"); key.value = name; key.setAttribute("aria-label", `${labelText} key`);
        key.addEventListener("change", () => { const next = key.value.trim(); if (next && next !== name) { map[next] = map[name]; delete map[name]; update(map); refresh(); } });
        row.append(key, schemaControl(schema.values, item, (value) => { map[name] = value; update(map); }, name));
        const remove = document.createElement("button"); remove.type = "button"; remove.className = "secondary"; remove.textContent = "Remove";
        remove.addEventListener("click", () => { delete map[name]; update(map); refresh(); }); row.append(remove); return row;
      }));
    };
    const add = document.createElement("button"); add.type = "button"; add.className = "secondary"; add.textContent = `Add ${labelText} entry`;
    add.addEventListener("click", () => { let index = 1; while (`entry-${index}` in map) index += 1; map[`entry-${index}`] = emptyValue(schema.values); update(map); refresh(); }); wrapper.append(list, add); refresh(); return wrapper;
  }
  if (schema.kind === "nullable") {
    const enabled = document.createElement("input"); enabled.type = "checkbox"; enabled.checked = current !== null;
    const slot = document.createElement("span");
    const refresh = (): void => { slot.replaceChildren(); if (enabled.checked) slot.append(schemaControl(schema.value, current ?? emptyValue(schema.value), update, labelText)); else update(null); };
    enabled.addEventListener("change", refresh); wrapper.append(enabled, slot); refresh(); return wrapper;
  }
  if (schema.kind === "null") { wrapper.append(document.createTextNode("none")); return wrapper; }
  if (schema.kind === "boolean") {
    const input = document.createElement("input"); input.type = "checkbox"; input.checked = Boolean(current); input.addEventListener("input", () => update(input.checked)); wrapper.append(input); return wrapper;
  }
  if (schema.kind === "string" && schema.allowed) {
    const input = document.createElement("select"); for (const allowed of schema.allowed) { const option = document.createElement("option"); option.value = allowed; option.textContent = allowed; input.append(option); }
    input.value = String(current ?? ""); input.addEventListener("input", () => update(input.value)); wrapper.append(input); return wrapper;
  }
  const input = document.createElement("input"); input.type = schema.kind === "integer" ? "number" : "text"; input.value = String(current ?? "");
  if (schema.kind === "integer") { if (schema.minimum !== undefined) input.min = String(schema.minimum); if (schema.maximum !== undefined) input.max = String(schema.maximum); }
  input.addEventListener("input", () => update(schema.kind === "integer" ? Number(input.value) : input.value)); wrapper.append(input);
  if (schema.kind === "string" && schema.format === "base64") {
    const file = document.createElement("input"); file.type = "file"; file.addEventListener("change", async () => {
      const selected = file.files?.[0]; if (!selected) return; const bytes = new Uint8Array(await selected.arrayBuffer());
      let binary = ""; for (const byte of bytes) binary += String.fromCharCode(byte); input.value = btoa(binary); update(input.value);
    }); wrapper.append(file);
  }
  return wrapper;
}

function configurationView(): void {
  const root = document.querySelector<HTMLElement>("#configurations") as HTMLElement; root.replaceChildren();
  selections.forEach((selection, index) => {
    const contract = CONTRACTS.find((candidate) => candidate.id === selection.contractId)!; const panel = document.createElement("div"); panel.className = "panel";
    const heading = document.createElement("div"); heading.className = "row"; const title = document.createElement("strong"); title.textContent = contract.title;
    const remove = document.createElement("button"); remove.className = "secondary"; remove.type = "button"; remove.textContent = "Remove"; remove.addEventListener("click", () => { selections.splice(index, 1); configurationView(); }); heading.append(title, remove); panel.append(heading);
    const grid = document.createElement("div"); grid.className = "grid";
    for (const axis of contract.identity) { const label = document.createElement("label"); label.append(document.createTextNode(axis)); const input = document.createElement("input"); input.dataset.axis = axis; input.value = selection.identity[axis] ?? ""; input.addEventListener("input", () => { (selection.identity as Record<string, string>)[axis] = input.value; }); label.append(input); grid.append(label); }
    if (contract.encoding === "base64-json" && contract.schema) grid.append(schemaControl(contract.schema, selection.values.document, (value) => { (selection.values as Record<string, unknown>).document = value; }, "Configuration"));
    else for (const field of contract.fields) {
      if (field.schema) { grid.append(schemaControl(field.schema, selection.values[field.name], (value) => { (selection.values as Record<string, unknown>)[field.name] = value; }, field.label)); continue; }
      const schema: ValueSchema = field.type === "integer" ? { kind: "integer", minimum: field.minimum, maximum: field.maximum } : field.type === "boolean" ? { kind: "boolean" }
        : field.type === "csv" ? { kind: "array", items: { kind: "string", minimumLength: 1, maximumLength: 4096 }, unique: true }
          : { kind: "string", minimumLength: field.required ? 1 : 0, maximumLength: 4096, allowed: field.allowed };
      grid.append(schemaControl(schema, selection.values[field.name], (value) => { (selection.values as Record<string, unknown>)[field.name] = value; }, field.label));
    }
    const nodes = document.createElement("p"); for (const node of contract.nodeIds) { const chip = document.createElement("span"); chip.className = "chip"; chip.textContent = node; nodes.append(chip); }
    panel.append(grid, nodes); root.append(panel);
  });
}

function renderSecretCoordinates(): void {
  const root = document.querySelector<HTMLElement>("#secret-coordinates") as HTMLElement; root.replaceChildren();
  const mode = (document.querySelector<HTMLSelectElement>("#secret-mode") as HTMLSelectElement).value;
  const field = (id: string, labelText: string, password = false): void => { const label = document.createElement("label"); label.append(document.createTextNode(labelText)); const input = document.createElement("input"); input.id = id; if (password) { input.type = "password"; input.autocomplete = "new-password"; } label.append(input); root.append(label); };
  if (mode === "embedded") field("secret-value", "Secret value", true);
  else if (targetKind() === "kubernetes") { field("secret-kubernetes-name", "Kubernetes Secret name"); field("secret-kubernetes-key", "Kubernetes Secret key"); }
  else field("secret-compose-variable", "Target environment variable");
}

function secretView(): void { const root = document.querySelector<HTMLElement>("#secrets") as HTMLElement; root.replaceChildren(...secrets.map((secret) => { const item = document.createElement("p"); item.textContent = `${secret.bindingId}: ${secret.mode} → ${secret.environmentKey}`; return item; })); }

document.querySelector("#target-kind")?.addEventListener("change", renderTargetOptions);
document.querySelector("#secret-mode")?.addEventListener("change", renderSecretCoordinates);
document.querySelector("#add-contract")?.addEventListener("click", () => { selections.push(templateSelection(contractSelect.value)); configurationView(); });
document.querySelector("#add-secret")?.addEventListener("click", () => {
  const mode = (document.querySelector<HTMLSelectElement>("#secret-mode") as HTMLSelectElement).value;
  const bindingId = (document.querySelector<HTMLInputElement>("#secret-id") as HTMLInputElement).value.trim(); const environmentKey = (document.querySelector<HTMLInputElement>("#secret-key") as HTMLInputElement).value.trim();
  if (!bindingId || !environmentKey) { message("Secret binding id and environment key are required.", true); return; }
  if (mode === "embedded") { const value = (document.querySelector<HTMLInputElement>("#secret-value") as HTMLInputElement).value; if (!value) { message("Secret value is required.", true); return; } secrets.push({ mode, bindingId, environmentKey, value }); }
  else if (targetKind() === "kubernetes") {
    const kubernetesSecret = (document.querySelector<HTMLInputElement>("#secret-kubernetes-name") as HTMLInputElement).value.trim(); const kubernetesKey = (document.querySelector<HTMLInputElement>("#secret-kubernetes-key") as HTMLInputElement).value.trim();
    if (!kubernetesSecret || !kubernetesKey) { message("Kubernetes Secret name and key are required.", true); return; } secrets.push({ mode: "target-reference", bindingId, environmentKey, kubernetesSecret, kubernetesKey });
  } else { const composeVariable = (document.querySelector<HTMLInputElement>("#secret-compose-variable") as HTMLInputElement).value.trim(); if (!composeVariable) { message("Target environment variable is required.", true); return; } secrets.push({ mode: "target-reference", bindingId, environmentKey, composeVariable }); }
  renderSecretCoordinates(); secretView();
});

document.querySelector<HTMLInputElement>("#bundles")?.addEventListener("change", async (event) => { try {
  const files = [...((event.target as HTMLInputElement).files ?? [])]; const grouped = new Map<string, Record<string, Uint8Array>>();
  for (const file of files) { const parts = file.webkitRelativePath.split("/"); const group = parts.length > 1 ? parts[0] as string : "bundle"; const name = parts.at(-1) as string; const entries = grouped.get(group) ?? {}; entries[name] = new Uint8Array(await file.arrayBuffer()); grouped.set(group, entries); }
  bundles = await Promise.all([...grouped.values()].map(validateBundleFiles)); (document.querySelector<HTMLElement>("#bundle-status") as HTMLElement).textContent = bundles.map((bundle) => `${bundle.manifest.id}@${bundle.manifest.version}`).join(", "); message(`${bundles.length} closed bundle(s) validated.`);
} catch (error) { message(error instanceof Error ? error.message : String(error), true); } });

async function packageNow(download: boolean): Promise<void> { try {
  const target: PackageTarget = { kind: targetKind(), id: (document.querySelector<HTMLInputElement>("#target-id") as HTMLInputElement).value.trim(), tenantIds: (document.querySelector<HTMLInputElement>("#tenants") as HTMLInputElement).value.split(",").map((item) => item.trim()).filter(Boolean), options: targetOptions };
  const packagePassword = (document.querySelector<HTMLInputElement>("#password") as HTMLInputElement).value; const result = await createPackage({ target, configurations: selections, secrets, bundles, ...(packagePassword ? { password: packagePassword } : {}) });
  (document.querySelector<HTMLElement>("#plan") as HTMLElement).textContent = JSON.stringify({ digest: result.digest, ...result.manifest.plan }, null, 2); message(`Package ready: ${result.manifest.entries.length} verified entries.`);
  if (download) { const link = document.createElement("a"); link.href = URL.createObjectURL(new Blob([result.bytes as BlobPart], { type: "application/vnd.ravenroot.config+zip" })); link.download = `${target.id}.rrcfg`; link.click(); setTimeout(() => URL.revokeObjectURL(link.href), 1000); }
} catch (error) { message(error instanceof Error ? error.message : String(error), true); } }

document.querySelector("#preview")?.addEventListener("click", () => void packageNow(false)); document.querySelector("#export")?.addEventListener("click", () => void packageNow(true));
renderTargetOptions();
