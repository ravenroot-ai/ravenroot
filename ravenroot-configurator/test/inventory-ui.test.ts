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
});
