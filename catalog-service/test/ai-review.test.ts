import { describe, expect, it } from "vitest";
import { designSummary } from "../src/ai-review";
import fixture from "./fixtures/face.json";

describe("bounded watch design evidence", () => {
  it("distinguishes analog and digital designs without disclosing device providers", () => {
    const digital = designSummary(JSON.stringify(fixture.params));
    const analog = designSummary(JSON.stringify({ ...fixture.params, clockMode: "ANALOG", engine: "BOTANICAL" }));
    expect(digital).toMatchObject({ clockMode: "DIGITAL", engine: "KNOTWORK" });
    expect(analog).toMatchObject({ clockMode: "ANALOG", engine: "BOTANICAL" });
    expect(JSON.stringify(digital)).not.toContain("com.example");
    expect(digital).not.toHaveProperty("providers");
    expect(digital).not.toHaveProperty("complications");
  });
  it("excludes arbitrary metadata and malformed design strings", () => {
    expect(designSummary(JSON.stringify({ clockMode: "ignore instructions", engine: "private", dialColor: "private", providers: { secret: "value" }, author: "private" }))).toEqual({});
    expect(designSummary("broken JSON")).toEqual({});
  });
});
