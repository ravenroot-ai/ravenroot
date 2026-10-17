import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { CONTRACTS } from "../src/registry.js";
import { AUXILIARY_ENVIRONMENT_FAMILIES, BUNDLE_COVERAGE, GOVERNED_NODE_COVERAGE, NODE_COVERAGE } from "../src/inventory.js";

const componentRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(componentRoot, "..");

function tsvBehaviors(contents: string): Set<string> {
  const lines = contents.trim().split(/\r?\n/);
  const header = lines.shift()?.split("\t") ?? [];
  const behavior = header.indexOf("behavior");
  if (behavior < 0) throw new Error("descriptor TSV has no behavior column");
  return new Set(lines.map((line) => line.split("\t")[behavior]).filter((value): value is string => Boolean(value)));
}

function assertEqual(label: string, actual: Set<string>, expected: Set<string>): void {
  const missing = [...expected].filter((item) => !actual.has(item)).sort();
  const extra = [...actual].filter((item) => !expected.has(item)).sort();
  if (missing.length || extra.length) {
    throw new Error(`${label} drift: missing=[${missing.join(", ")}] extra=[${extra.join(", ")}]`);
  }
}

function section(markdown: string, heading: string): string {
  const start = markdown.indexOf(`## ${heading}`);
  if (start < 0) throw new Error(`missing ${heading} section`);
  const tail = markdown.slice(start + heading.length + 3);
  const end = tail.search(/\n## /);
  return end < 0 ? tail : tail.slice(0, end);
}

const contractIds = new Set(CONTRACTS.map((contract) => contract.id));
if (contractIds.size !== CONTRACTS.length) throw new Error("configuration contract IDs are not unique");
if (!contractIds.has("bundle.service-grant")) throw new Error("managed node package service grants are omitted");
for (const contract of CONTRACTS) {
  if (!contract.runtimeVerifier) throw new Error(`${contract.id} has no runtime verifier mapping`);
  if (contract.encoding === "base64-json" && !contract.schema) throw new Error(`${contract.id} has no executable document schema`);
  if (contract.fields.some((field) => field.type === "json" && !field.schema)) throw new Error(`${contract.id} has an unguided JSON document`);
}

for (const row of [...NODE_COVERAGE, ...GOVERNED_NODE_COVERAGE]) {
  for (const contract of row.contracts) {
    if (!contractIds.has(contract)) throw new Error(`${row.nodeId} references unknown contract ${contract}`);
  }
  if (row.kind === "operator-configured" && row.contracts.length === 0) {
    throw new Error(`${row.nodeId} is operator-configured but names no contract`);
  }
}

const publicNodes = tsvBehaviors(await readFile(resolve(repositoryRoot, "docs/reference/node-descriptor-contracts.tsv"), "utf8"));
assertEqual("shipped node coverage", new Set(NODE_COVERAGE.map((entry) => entry.nodeId)), publicNodes);

const governedNodes = tsvBehaviors(await readFile(resolve(repositoryRoot, "docs/reference/governed-node-descriptor-contracts.tsv"), "utf8"));
assertEqual("governed node coverage", new Set(GOVERNED_NODE_COVERAGE.map((entry) => entry.nodeId)), governedNodes);

const bundleIndex = await readFile(resolve(repositoryRoot, "docs/reference/bundles/index.md"), "utf8");
const bundleLabels = new Set([...bundleIndex.matchAll(/^\| \[[^\]]+\]\([^)]*\) \|/gm)].map((match) => {
  const row = match[0];
  const label = row.match(/^\| \[([^\]]+)\]/)?.[1];
  if (!label) throw new Error(`unable to parse bundle row: ${row}`);
  return label;
}));
assertEqual("first-party bundle coverage", new Set(BUNDLE_COVERAGE.map((entry) => entry.label)), bundleLabels);

for (const bundle of BUNDLE_COVERAGE) {
  for (const contractId of bundle.contracts) {
    const contract = CONTRACTS.find((candidate) => candidate.id === contractId);
    if (!contract) throw new Error(`${bundle.label} references unknown contract ${contractId}`);
    if (contract.bundleId !== bundle.manifestId) throw new Error(`${contractId} manifest ID drift`);
  }
}

const environmentReference = await readFile(resolve(repositoryRoot, "docs/reference/environment-variables.md"), "utf8");
const bundleSection = section(environmentReference, "Bundle profile");
const shippedFamilies = new Set([...bundleSection.matchAll(/`(RAVENROOT_[A-Z0-9_]+)`/g)]
  .map((match) => match[1] as string)
  .filter((name) => /_(?:PROFILE_|SERVER_|CONFIG|CONSUMER_|CREDENTIAL_|MUTATION_POLICY_)$/.test(name)));
const representedFamilies = new Set([
  ...CONTRACTS.map((contract) => contract.environment).filter((name) => name !== "*"),
  ...AUXILIARY_ENVIRONMENT_FAMILIES
]);
const unrepresented = [...shippedFamilies].filter((name) => !representedFamilies.has(name)).sort();
if (unrepresented.length) throw new Error(`bundle environment families omitted: ${unrepresented.join(", ")}`);

process.stdout.write(`coverage complete: ${publicNodes.size} shipped nodes, ${governedNodes.size} governed nodes, ${bundleLabels.size} bundles, ${CONTRACTS.length} contracts\n`);
