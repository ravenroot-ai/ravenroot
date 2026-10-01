import "./styles.css";
import { validateBundleFiles } from "./bundles.js";
import { CONTRACTS, templateSelection } from "./registry.js";
import { createPackage } from "./package-format.js";
import type { BundlePayload, ConfigurationSelection, SecretInput, TargetKind } from "./types.js";

const app = document.querySelector<HTMLElement>("#app");
if (!app) throw new Error("Missing app root");

app.innerHTML = `
  <h1>Ravenroot configuration packager</h1>
  <p class="lede">Build a versioned, self-contained package from readable operator settings. Secrets stay in this browser and previews are redacted.</p>
  <section class="panel"><h2>1. Target</h2><div class="grid">
    <label>Target kind<select id="target-kind"><option value="compose">Local Compose</option><option value="kubernetes">Kubernetes / Helm</option><option value="prestart">Embedding pre-start</option></select></label>
    <label>Target identity<input id="target-id" required placeholder="production-eu" /></label>
    <label>Tenant identities<input id="tenants" required placeholder="tenant-a,tenant-b" /></label>
  </div><label>Adapter options (JSON)<textarea id="target-options">{}</textarea></label></section>
  <section class="panel"><h2>2. Node or backend family</h2><div class="row">
    <label>Contract<select id="contract"></select></label><button id="add-contract">Add configuration</button>
  </div><div id="configurations"></div></section>
  <section class="panel"><h2>3. Credentials</h2><p><small>Add an existing target secret reference or an embedded value encrypted with Argon2id and AES-256-GCM.</small></p>
    <div class="grid"><label>Mode<select id="secret-mode"><option value="target-reference">Target-side reference</option><option value="embedded">Encrypted embedded value</option></select></label>
    <label>Binding id<input id="secret-id" placeholder="broker-password" /></label><label>Runtime environment key<input id="secret-key" placeholder="RAVENROOT_AMQP091_CREDENTIAL_..." /></label>
    <label>Value or target variable<input id="secret-value" type="password" autocomplete="new-password" /></label></div>
    <button id="add-secret" class="secondary">Add secret binding</button><div id="secrets"></div>
    <label>Package password<input id="password" type="password" autocomplete="new-password" minlength="12" /></label></section>
  <section class="panel"><h2>4. Prebuilt bundles</h2><p><small>Select each closed bundle directory required by the chosen families. Source builds are never run.</small></p>
    <input id="bundles" type="file" webkitdirectory multiple /><div id="bundle-status"></div></section>
  <section class="panel"><h2>5. Preview and export</h2><div class="row"><button id="preview" class="secondary">Refresh redacted preview</button><button id="export">Export .rrcfg</button></div>
    <p id="message"></p><pre id="plan">No package prepared.</pre></section>`;

const contractSelect = document.querySelector<HTMLSelectElement>("#contract") as HTMLSelectElement;
for (const contract of CONTRACTS) {
  const option = document.createElement("option");
  option.value = contract.id;
  option.textContent = `${contract.family} — ${contract.title}`;
  contractSelect.append(option);
}

const selections: ConfigurationSelection[] = [];
const secrets: SecretInput[] = [];
let bundles: BundlePayload[] = [];

function configurationView(): void {
  const root = document.querySelector<HTMLElement>("#configurations") as HTMLElement;
  root.replaceChildren();
  selections.forEach((selection, index) => {
    const contract = CONTRACTS.find((candidate) => candidate.id === selection.contractId)!;
    const panel = document.createElement("div");
    panel.className = "panel";
    const axes = contract.identity.map((axis) => `<label>${axis}<input data-axis="${axis}" value="${selection.identity[axis] ?? ""}" /></label>`).join("");
    const fields = contract.encoding === "base64-json"
      ? `<label>Decoded strict JSON document<textarea data-json>${JSON.stringify(selection.values.document, null, 2)}</textarea></label>`
      : contract.fields.map((field) => field.type === "json" ? `<label>${field.label}<textarea data-field-json="${field.name}">${JSON.stringify(selection.values[field.name], null, 2)}</textarea></label>`
        : `<label>${field.label}${field.runtimeDefault !== undefined ? ` <small>Runtime default: ${field.runtimeDefault}</small>` : ""}
          <input data-field="${field.name}" type="${field.type === "integer" ? "number" : field.type === "boolean" ? "checkbox" : "text"}"
          ${field.type === "boolean" && selection.values[field.name] ? "checked" : ""} value="${field.type === "boolean" ? "true" : String(selection.values[field.name] ?? "")}" /></label>`).join("");
    panel.innerHTML = `<div class="row"><strong>${contract.title}</strong><button class="secondary" data-remove>Remove</button></div><div class="grid">${axes}${fields}</div>
      <p>${contract.nodeIds.map((node) => `<span class="chip">${node}</span>`).join("")}</p>`;
    panel.querySelector("[data-remove]")?.addEventListener("click", () => { selections.splice(index, 1); configurationView(); });
    panel.querySelectorAll<HTMLInputElement>("[data-axis]").forEach((input) => input.addEventListener("input", () => {
      (selection.identity as Record<string, string>)[input.dataset.axis as string] = input.value;
    }));
    panel.querySelectorAll<HTMLInputElement>("[data-field]").forEach((input) => input.addEventListener("input", () => {
      const field = contract.fields.find((candidate) => candidate.name === input.dataset.field)!;
      (selection.values as Record<string, unknown>)[field.name] = field.type === "boolean" ? input.checked : field.type === "integer" ? Number(input.value) : field.type === "csv" ? input.value.split(",").map((item) => item.trim()).filter(Boolean) : input.value;
    }));
    panel.querySelectorAll<HTMLTextAreaElement>("[data-field-json]").forEach((input) => input.addEventListener("input", () => {
      try { (selection.values as Record<string, unknown>)[input.dataset.fieldJson as string] = JSON.parse(input.value); message(""); }
      catch { message("Configuration JSON is not valid.", true); }
    }));
    panel.querySelector<HTMLTextAreaElement>("[data-json]")?.addEventListener("input", (event) => {
      try { (selection.values as Record<string, unknown>).document = JSON.parse((event.target as HTMLTextAreaElement).value); message(""); }
      catch { message("Configuration JSON is not valid.", true); }
    });
    root.append(panel);
  });
}

function secretView(): void {
  const root = document.querySelector<HTMLElement>("#secrets") as HTMLElement;
  root.replaceChildren(...secrets.map((secret) => {
    const item = document.createElement("p");
    item.textContent = `${secret.bindingId}: ${secret.mode} → ${secret.environmentKey}`;
    return item;
  }));
}

function message(value: string, error = false): void {
  const node = document.querySelector<HTMLElement>("#message") as HTMLElement;
  node.textContent = value;
  node.className = error ? "error" : "success";
}

document.querySelector("#add-contract")?.addEventListener("click", () => {
  selections.push(templateSelection(contractSelect.value));
  configurationView();
});

document.querySelector("#add-secret")?.addEventListener("click", () => {
  const mode = (document.querySelector<HTMLSelectElement>("#secret-mode") as HTMLSelectElement).value;
  const bindingId = (document.querySelector<HTMLInputElement>("#secret-id") as HTMLInputElement).value.trim();
  const environmentKey = (document.querySelector<HTMLInputElement>("#secret-key") as HTMLInputElement).value.trim();
  const value = (document.querySelector<HTMLInputElement>("#secret-value") as HTMLInputElement).value;
  if (!bindingId || !environmentKey || !value) { message("Secret binding id, environment key, and value/reference are required.", true); return; }
  secrets.push(mode === "embedded" ? { mode, bindingId, environmentKey, value }
    : { mode: "target-reference", bindingId, environmentKey, composeVariable: value, kubernetesSecret: value, kubernetesKey: bindingId });
  (document.querySelector<HTMLInputElement>("#secret-value") as HTMLInputElement).value = "";
  secretView();
});

document.querySelector<HTMLInputElement>("#bundles")?.addEventListener("change", async (event) => {
  try {
    const files = [...((event.target as HTMLInputElement).files ?? [])];
    const grouped = new Map<string, Record<string, Uint8Array>>();
    for (const file of files) {
      const parts = file.webkitRelativePath.split("/");
      const group = parts.length > 1 ? parts[0] as string : "bundle";
      const name = parts.at(-1) as string;
      const entries = grouped.get(group) ?? {};
      entries[name] = new Uint8Array(await file.arrayBuffer());
      grouped.set(group, entries);
    }
    bundles = await Promise.all([...grouped.values()].map(validateBundleFiles));
    (document.querySelector<HTMLElement>("#bundle-status") as HTMLElement).textContent = bundles.map((bundle) => `${bundle.manifest.id}@${bundle.manifest.version}`).join(", ");
    message(`${bundles.length} closed bundle(s) validated.`);
  } catch (error) { message(error instanceof Error ? error.message : String(error), true); }
});

async function packageNow(download: boolean): Promise<void> {
  try {
    const target = {
      kind: (document.querySelector<HTMLSelectElement>("#target-kind") as HTMLSelectElement).value as TargetKind,
      id: (document.querySelector<HTMLInputElement>("#target-id") as HTMLInputElement).value.trim(),
      tenantIds: (document.querySelector<HTMLInputElement>("#tenants") as HTMLInputElement).value.split(",").map((item) => item.trim()).filter(Boolean),
      options: JSON.parse((document.querySelector<HTMLTextAreaElement>("#target-options") as HTMLTextAreaElement).value) as Record<string, string | number | boolean>
    };
    const packagePassword = (document.querySelector<HTMLInputElement>("#password") as HTMLInputElement).value;
    const result = await createPackage({ target, configurations: selections, secrets, bundles,
      ...(packagePassword ? { password: packagePassword } : {}) });
    (document.querySelector<HTMLElement>("#plan") as HTMLElement).textContent = JSON.stringify({ digest: result.digest, ...result.manifest.plan }, null, 2);
    message(`Package ready: ${result.manifest.entries.length} verified entries.`);
    if (download) {
      const link = document.createElement("a");
      link.href = URL.createObjectURL(new Blob([result.bytes as BlobPart], { type: "application/vnd.ravenroot.config+zip" }));
      link.download = `${target.id}.rrcfg`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    }
  } catch (error) { message(error instanceof Error ? error.message : String(error), true); }
}

document.querySelector("#preview")?.addEventListener("click", () => void packageNow(false));
document.querySelector("#export")?.addEventListener("click", () => void packageNow(true));
