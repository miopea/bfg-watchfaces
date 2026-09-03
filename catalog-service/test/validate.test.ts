import { describe, expect, it } from "vitest";
import { CONTRACT } from "../src/contract";
import { flatten, validateFace } from "../src/validate";
import { FIXTURE, submission } from "./helpers";

/**
 * The validator, on its own.
 *
 * The single most important test here is the first one: a face the app really
 * produces must be accepted. Everything else is a way of being wrong that
 * refuses something, and a validator that refuses everything passes every test
 * about refusing.
 */
describe("validateFace", () => {
  it("accepts a face the app actually produces", () => {
    expect(validateFace(submission())).toEqual([]);
  });

  it("reports every problem at once, not the first", () => {
    const bad = submission();
    const params = bad["params"] as Record<string, unknown>;
    params["scale"] = 9999;
    params["dialColor"] = "not a colour";
    params["engine"] = "NOT_AN_ENGINE";
    const problems = validateFace(bad);
    expect(problems.length).toBeGreaterThanOrEqual(3);
    expect(problems.map((p) => p.field)).toEqual(
      expect.arrayContaining(["scale", "dialColor", "engine"]),
    );
  });

  it("refuses a TEXTURE face, which is the catalog's whole IP shield", () => {
    const bad = submission();
    (bad["params"] as Record<string, unknown>)["engine"] = "TEXTURE";
    const problems = validateFace(bad);
    expect(problems.some((p) => p.field === "engine" && p.message.includes("parameters only"))).toBe(true);
  });

  it("refuses a face that references an imported image", () => {
    const bad = submission();
    (bad["params"] as Record<string, unknown>)["texture"] = "local-image-42";
    expect(validateFace(bad).some((p) => p.field === "texture")).toBe(true);
  });

  // A SHA-1 of imported bytes is what TextureStore ids look like. It must not
  // slip through the built-in allowance just by being well-formed.
  it("refuses an imported image even when its id looks like a real hash", () => {
    const bad = submission();
    const params = bad["params"] as Record<string, unknown>;
    params["engine"] = "TEXTURE";
    params["texture"] = "a".repeat(40);
    expect(validateFace(bad).some((p) => p.field === "texture")).toBe(true);
  });

  it("accepts a TEXTURE face whose picture ships inside the app", () => {
    const ok = submission();
    const params = ok["params"] as Record<string, unknown>;
    params["engine"] = "TEXTURE";
    params["texture"] = CONTRACT.builtInDials[0];
    const problems = validateFace(ok);
    expect(problems.filter((p) => p.field === "engine" || p.field === "texture")).toEqual([]);
  });

  // The picture is only drawn by the TEXTURE engine, so naming one anywhere
  // else is a face whose image would be silently ignored.
  it("refuses a built-in dial named by an engine that would not draw it", () => {
    const bad = submission();
    const params = bad["params"] as Record<string, unknown>;
    params["engine"] = "ROSETTE";
    params["texture"] = CONTRACT.builtInDials[0];
    expect(validateFace(bad).some((p) => p.field === "texture")).toBe(true);
  });

  it("publishes at least one built-in dial, or the feature is unreachable", () => {
    expect(CONTRACT.builtInDials.length).toBeGreaterThan(0);
  });

  it("refuses a generatorVersion this build cannot render", () => {
    const bad = submission();
    (bad["params"] as Record<string, unknown>)["generatorVersion"] = 999;
    expect(validateFace(bad).some((p) => p.field === "generatorVersion")).toBe(true);
  });

  it("refuses a fractional value in an integral field", () => {
    // A fractional freq is not a finer setting. The field is an Int, so the
    // next read of it is silently a different number.
    const bad = submission();
    (bad["params"] as Record<string, unknown>)["freq"] = 6.9997;
    expect(validateFace(bad).some((p) => p.field === "freq")).toBe(true);
  });

  it("refuses a parameter nobody has heard of", () => {
    const bad = submission();
    (bad["params"] as Record<string, unknown>)["backdoor"] = 1;
    expect(validateFace(bad).some((p) => p.field === "backdoor")).toBe(true);
  });

  it("refuses a face with a parameter missing", () => {
    const bad = submission();
    delete (bad["params"] as Record<string, unknown>)["sheen"];
    expect(validateFace(bad).some((p) => p.field === "sheen")).toBe(true);
  });

  /**
   * The security bound, not a taste one. `fontFamily` is interpolated straight
   * into an XML attribute by the emitter with no escaping.
   */
  it("refuses a font family that would close the XML attribute it is written into", () => {
    const bad = submission();
    const layout = (bad["params"] as Record<string, unknown>)["layout"] as Record<string, unknown>;
    layout["fontFamily"] = 'Roboto" onload="evil';
    expect(validateFace(bad).some((p) => p.field === "fontFamily")).toBe(true);
  });

  it("refuses a provider that is not a ComponentName", () => {
    const bad = submission();
    (bad["params"] as Record<string, unknown>)["providers"] = { LEFT: 'a" x="y' };
    expect(validateFace(bad).some((p) => p.field.startsWith("providers"))).toBe(true);
  });

  it("refuses a launch target that is not a ComponentName", () => {
    const bad = submission();
    (bad["params"] as Record<string, unknown>)["complications"] = [
      'SHORTCUT_MUSIC+open:evil" onload="x',
    ];
    expect(validateFace(bad).some((p) => p.field.includes("launcher"))).toBe(true);
  });

  it("accepts all three complication token shapes", () => {
    // Plain, +app: (the provider filling the slot) and +open: (what pressing it
    // launches). The fixture already carries one of each; this pins that they
    // are all understood rather than tolerated by accident.
    const tokens = FIXTURE.params["complications"] as string[];
    expect(tokens.some((t) => !t.includes("+"))).toBe(true);
    expect(tokens.some((t) => t.includes("+app:"))).toBe(true);
    expect(tokens.some((t) => t.includes("+open:"))).toBe(true);
    expect(validateFace(submission())).toEqual([]);
  });

  it("refuses a name carrying a bidirectional override", () => {
    // A format character that makes one string render as another. A carousel
    // label is not a place for those.
    expect(validateFace(submission({ name: "Midnight‮" })).some((p) => p.field === "name")).toBe(true);
  });

  it("refuses a slug that is not a legal Watch Face Push package segment", () => {
    expect(validateFace(submission({ slug: "9lives" })).some((p) => p.field === "slug")).toBe(true);
    expect(validateFace(submission({ slug: "Has-Caps" })).some((p) => p.field === "slug")).toBe(true);
  });

  it("flattens layout the way FaceCodec does", () => {
    const flat = flatten(FIXTURE.params);
    expect(flat["timeSize"]).toBe(104);
    expect(flat["layout"]).toBeUndefined();
  });
});
