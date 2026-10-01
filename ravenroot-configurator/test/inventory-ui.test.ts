// @vitest-environment jsdom
import { describe, expect, test } from "vitest";
import { CONTRACTS, templateSelection } from "../src/registry.js";
import { NODE_COVERAGE } from "../src/inventory.js";

describe("guided registry", () => {
  test("offers every operator-owned coverage contract and no secret defaults", () => {
    const contractIds = new Set(CONTRACTS.map((contract) => contract.id));
    for (const row of NODE_COVERAGE.filter((entry) => entry.kind === "operator-configured")) {
      expect(row.contracts.length).toBeGreaterThan(0);
      for (const contract of row.contracts) expect(contractIds.has(contract)).toBe(true);
    }
    for (const contract of CONTRACTS) {
      expect(contract.fields.filter((field) => field.sensitive).every((field) => field.suggestion === undefined)).toBe(true);
      expect(() => templateSelection(contract.id)).not.toThrow();
    }
  });

  test("renders the guided target, profile, credential, bundle, and preview stages", async () => {
    document.body.innerHTML = '<main id="app"></main>';
    await import("../src/ui.js");
    expect(document.querySelectorAll("section")).toHaveLength(5);
    expect(document.querySelectorAll("#contract option")).toHaveLength(CONTRACTS.length);
    expect(document.body.textContent).toContain("Prebuilt bundles");
  });

  test("renders operator identity and JSON as values without creating active markup", () => {
    const contract = document.querySelector<HTMLSelectElement>("#contract") as HTMLSelectElement;
    contract.value = "bundle.service-grant";
    (document.querySelector<HTMLButtonElement>("#add-contract") as HTMLButtonElement).click();
    const identityPayload = `default\" autofocus onfocus=\"globalThis.uiInjected=true`;
    const identity = document.querySelector<HTMLInputElement>("[data-axis]") as HTMLInputElement;
    identity.value = identityPayload;
    identity.dispatchEvent(new Event("input"));
    const jsonPayload = `</textarea><img src=x onerror=\"globalThis.uiInjected=true\">`;
    const documentInput = document.querySelector<HTMLTextAreaElement>("#configurations textarea") as HTMLTextAreaElement;
    documentInput.value = JSON.stringify({ capabilities: [], note: jsonPayload });
    documentInput.dispatchEvent(new Event("input"));

    (document.querySelector<HTMLButtonElement>("#add-contract") as HTMLButtonElement).click();

    expect((document.querySelector<HTMLInputElement>("[data-axis]") as HTMLInputElement).value).toBe(identityPayload);
    expect(JSON.parse((document.querySelector<HTMLTextAreaElement>("#configurations textarea") as HTMLTextAreaElement).value).note).toBe(jsonPayload);
    expect(document.querySelector("#configurations img")).toBeNull();
    expect((globalThis as typeof globalThis & { uiInjected?: boolean }).uiInjected).not.toBe(true);
  });
});
