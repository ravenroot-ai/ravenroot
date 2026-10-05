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

  test("renders operator identity and guided scalar values without creating active markup", () => {
    const contract = document.querySelector<HTMLSelectElement>("#contract") as HTMLSelectElement;
    contract.value = "bundle.service-grant";
    (document.querySelector<HTMLButtonElement>("#add-contract") as HTMLButtonElement).click();
    const identityPayload = `default\" autofocus onfocus=\"globalThis.uiInjected=true`;
    const identity = document.querySelector<HTMLInputElement>("[data-axis]") as HTMLInputElement;
    identity.value = identityPayload;
    identity.dispatchEvent(new Event("input"));
    const scalarPayload = `\"><img src=x onerror=\"globalThis.uiInjected=true\">`;
    const documentInput = [...document.querySelectorAll<HTMLInputElement>("#configurations input[type=text]")]
      .find((input) => !input.dataset.axis) as HTMLInputElement;
    documentInput.value = scalarPayload;
    documentInput.dispatchEvent(new Event("input"));

    (document.querySelector<HTMLButtonElement>("#add-contract") as HTMLButtonElement).click();

    expect((document.querySelector<HTMLInputElement>("[data-axis]") as HTMLInputElement).value).toBe(identityPayload);
    expect([...document.querySelectorAll<HTMLInputElement>("#configurations input[type=text]")].some((input) => input.value === scalarPayload)).toBe(true);
    expect(document.querySelector("#configurations img")).toBeNull();
    expect((globalThis as typeof globalThis & { uiInjected?: boolean }).uiInjected).not.toBe(true);
  });

  test("provides guided controls for every contract and target including separate Kubernetes Secret coordinates", () => {
    const contract = document.querySelector<HTMLSelectElement>("#contract") as HTMLSelectElement;
    const add = document.querySelector<HTMLButtonElement>("#add-contract") as HTMLButtonElement;
    for (const definition of CONTRACTS) { contract.value = definition.id; add.click(); }
    expect(document.querySelector("#configurations textarea")).toBeNull();
    expect(document.querySelectorAll("#configurations fieldset, #configurations label").length).toBeGreaterThan(CONTRACTS.length);

    const kind = document.querySelector<HTMLSelectElement>("#target-kind") as HTMLSelectElement;
    kind.value = "kubernetes"; kind.dispatchEvent(new Event("change"));
    expect(document.querySelector('[data-target-option="namespace"]')).not.toBeNull();
    expect(document.querySelector('[data-target-option="derivedImage"]')).not.toBeNull();
    expect(document.querySelector("#secret-kubernetes-name")).not.toBeNull();
    expect(document.querySelector("#secret-kubernetes-key")).not.toBeNull();
    expect(document.querySelector("#configurations")?.textContent).toContain("sample suggestion");
    expect(document.querySelector("#configurations")?.textContent).toContain("otherwise runtime default");
    expect(document.querySelector("#configurations")?.textContent).toContain("operator input required");
    expect(document.querySelector("#configurations")?.textContent).toContain("Connector constraints");
    expect(document.querySelector("#configurations")?.textContent).toContain("Required runtime capability: outbound-http");
    expect(document.querySelector("#configurations")?.textContent).toContain("External requirement: A configured sandbox supervisor");
    expect(document.querySelector("#configurations")?.textContent).toContain("at most 2097152 decoded bytes");
    kind.value = "prestart"; kind.dispatchEvent(new Event("change"));
    expect(document.querySelector('[data-target-option="restartCommandJson"]')).not.toBeNull();
    expect(document.querySelector('[data-target-option="verifyCommandJson"]')).not.toBeNull();
    kind.value = "compose"; kind.dispatchEvent(new Event("change"));
    expect(document.querySelector('[data-target-option="verifyBaseUrl"]')).not.toBeNull();
  });
});
