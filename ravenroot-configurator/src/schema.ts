import type { ValueSchema } from "./types.js";

const NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,159}";

function inferred(value: unknown, name = ""): ValueSchema {
  if (value === null) return { kind: "nullable", value: { kind: "string", minimumLength: 1, maximumLength: 256 } };
  if (typeof value === "string") {
    if (name.toLowerCase().includes("sha256")) return { kind: "string", format: "sha256" };
    if (name.toLowerCase().endsWith("base64")) return { kind: "string", format: "base64", minimumLength: 4 };
    if (name === "origin" || name.endsWith("Origin") || name === "endpoint" || name === "destination" || name.endsWith("Endpoint")) {
      return { kind: "string", format: "uri", minimumLength: 1, maximumLength: 2048 };
    }
    if (name === "root" || name === "path" || name.startsWith("absolute") || name.endsWith("Directory") || name.endsWith("Executable")) {
      return { kind: "string", format: "absolute-path", minimumLength: 1, maximumLength: 4096 };
    }
    return { kind: "string", minimumLength: value === "" ? 0 : 1, maximumLength: 4096 };
  }
  if (typeof value === "number") return { kind: "integer", minimum: 0, maximum: Number.MAX_SAFE_INTEGER };
  if (typeof value === "boolean") return { kind: "boolean" };
  if (Array.isArray(value)) {
    const candidates = value.length ? value.map((item) => inferred(item, `${name}Item`))
      : [{ kind: "string", minimumLength: 1, maximumLength: 4096 } as const];
    const distinct = [...new Map(candidates.map((candidate) => [JSON.stringify(candidate), candidate])).values()];
    const item: ValueSchema = distinct.length === 1 ? distinct[0]! : { kind: "union", choices: distinct };
    return { kind: "array", items: item, minimumItems: 0, maximumItems: 1024, unique: true };
  }
  if (value && typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>);
    if (name === "profiles" || name === "statements" || name === "tenants") {
      if (!entries.length) return { kind: "map", values: { kind: "object", properties: {} }, minimumEntries: 0, maximumEntries: 256, keyPattern: NAME };
      return { kind: "map", values: inferred(entries[0]![1], entries[0]![0]), minimumEntries: 1, maximumEntries: 256, keyPattern: NAME };
    }
    if (["headers", "fixedHeaders", "guilds", "events"].includes(name)) {
      return { kind: "map", values: { kind: "array", items: { kind: "string", minimumLength: 1, maximumLength: 512 }, maximumItems: 256, unique: true }, maximumEntries: 128, keyPattern: NAME };
    }
    if (name === "statusOptions") return { kind: "map", values: { kind: "string", minimumLength: 1, maximumLength: 128 }, maximumEntries: 64, keyPattern: NAME };
    const properties = Object.fromEntries(entries.map(([key, child]) => [key, inferred(child, key)]));
    return { kind: "object", properties };
  }
  throw new Error(`Cannot infer a schema for ${name || "value"}`);
}

export function schemaFromTemplate(template: Readonly<Record<string, unknown>>): ValueSchema {
  return inferred(template);
}

function fail(path: string, message: string): never { throw new Error(`${path} ${message}`); }

export function validateValue(schema: ValueSchema, value: unknown, path = "value"): void {
  if (schema.kind === "union") {
    for (const choice of schema.choices) { try { validateValue(choice, value, path); return; } catch {} }
    fail(path, "does not match an allowed shape");
  }
  if (schema.kind === "nullable") {
    if (value !== null) validateValue(schema.value, value, path);
    return;
  }
  if (schema.kind === "null") { if (value !== null) fail(path, "must be null"); return; }
  if (schema.kind === "string") {
    if (typeof value !== "string") fail(path, "must be a string");
    if (schema.minimumLength !== undefined && value.length < schema.minimumLength) fail(path, `must contain at least ${schema.minimumLength} characters`);
    if (schema.maximumLength !== undefined && value.length > schema.maximumLength) fail(path, `must contain at most ${schema.maximumLength} characters`);
    if (schema.allowed && !schema.allowed.includes(value)) fail(path, `must be one of ${schema.allowed.join(", ")}`);
    if (schema.nonBlank && value.trim().length === 0) fail(path, "must contain non-whitespace text");
    if (schema.pattern && !new RegExp(`^(?:${schema.pattern})$`).test(value)) fail(path, "has an invalid format");
    if (schema.format === "sha256" && !/^[0-9a-f]{64}$/.test(value)) fail(path, "must be a lowercase SHA-256 digest");
    if (schema.format === "base64") {
      try { if (btoa(atob(value)) !== value) fail(path, "must be canonical Base64"); }
      catch { fail(path, "must be canonical Base64"); }
    }
    if (schema.format === "uri") {
      let uri: URL; try { uri = new URL(value); } catch { fail(path, "must be an absolute URI"); }
      if (!uri.protocol || uri.username || uri.password) fail(path, "must be an absolute URI without credentials");
      if (schema.schemes && !schema.schemes.includes(uri.protocol.slice(0, -1).toLowerCase())) fail(path, `must use ${schema.schemes.join(" or ")}`);
      if (schema.requireHost && !uri.hostname) fail(path, "must include a host");
      if (schema.allowFragment === false && uri.hash) fail(path, "must not include a fragment");
      if (schema.authorityOnly) {
        const match = /^[A-Za-z][A-Za-z0-9+.-]*:\/\/[^/?#]+(.*)$/.exec(value);
        const suffix = match?.[1];
        if (suffix === undefined || (suffix !== "" && !(schema.allowRootPath && suffix === "/"))) fail(path, "must contain only an authority");
      }
    }
    if (schema.format === "duration" && (!/^P(?=\d|T\d)(?:\d+D)?(?:T(?=\d)(?:\d+H)?(?:\d+M)?(?:\d+(?:\.\d+)?S)?)?$/.test(value) || !/[1-9]/.test(value))) fail(path, "must be a positive ISO-8601 duration");
    if (schema.format === "absolute-path" && !value.startsWith("/")) fail(path, "must be an absolute path");
    return;
  }
  if (schema.kind === "integer") {
    if (!Number.isSafeInteger(value)) fail(path, "must be an integer");
    if (schema.minimum !== undefined && (value as number) < schema.minimum) fail(path, `must be at least ${schema.minimum}`);
    if (schema.maximum !== undefined && (value as number) > schema.maximum) fail(path, `must be at most ${schema.maximum}`);
    return;
  }
  if (schema.kind === "boolean") { if (typeof value !== "boolean") fail(path, "must be true or false"); return; }
  if (schema.kind === "array") {
    if (!Array.isArray(value)) fail(path, "must be an array");
    if (schema.minimumItems !== undefined && value.length < schema.minimumItems) fail(path, `requires at least ${schema.minimumItems} items`);
    if (schema.maximumItems !== undefined && value.length > schema.maximumItems) fail(path, `allows at most ${schema.maximumItems} items`);
    value.forEach((item, index) => validateValue(schema.items, item, `${path}[${index}]`));
    if (schema.unique && new Set(value.map((item) => JSON.stringify(item))).size !== value.length) fail(path, "must contain unique items");
    return;
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(path, "must be an object");
  const record = value as Record<string, unknown>;
  if (schema.kind === "map") {
    const entries = Object.entries(record);
    if (schema.minimumEntries !== undefined && entries.length < schema.minimumEntries) fail(path, `requires at least ${schema.minimumEntries} entries`);
    if (schema.maximumEntries !== undefined && entries.length > schema.maximumEntries) fail(path, `allows at most ${schema.maximumEntries} entries`);
    for (const [key, child] of entries) {
      if (schema.keyPattern && !new RegExp(`^(?:${schema.keyPattern})$`).test(key)) fail(`${path}.${key}`, "has an invalid key");
      validateValue(schema.values, child, `${path}.${key}`);
    }
    return;
  }
  const optional = new Set(schema.optional ?? []);
  for (const key of Object.keys(record)) if (!(key in schema.properties)) fail(`${path}.${key}`, "is not supported");
  for (const [key, childSchema] of Object.entries(schema.properties)) {
    if (!(key in record)) { if (!optional.has(key)) fail(`${path}.${key}`, "is required"); }
    else validateValue(childSchema, record[key], `${path}.${key}`);
  }
}
