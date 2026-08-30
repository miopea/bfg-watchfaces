import { CONTRACT, CONTROLS, FIELDS, pattern } from "./contract";

/**
 * The cheap half of validation, run before a submission takes a place in a
 * human queue.
 *
 * ## What this deliberately does NOT do
 *
 * It does not render the face and it does not check the emitted WFF against
 * Google's XSD. Neither can run here: the emitter is Kotlin and the validator
 * is Xerces, and porting either into JavaScript would be a second
 * implementation of the file format — the thing every other decision in this
 * repo has been arranged to avoid.
 *
 * That check still happens, and still happens before anything is published: the
 * moderation pass runs it on the JVM, with the real emitter and the real
 * schema, and auto-rejects what fails. So the promise in R3 holds in substance
 * — nothing reaches a person, let alone the public, without an automated
 * verdict — but it is no longer true at the moment the POST returns. That
 * difference is worth stating because a schema-invalid face installs cleanly
 * and then never appears in the carousel: there is no error, on either side.
 *
 * What is caught here is what a stranger can get wrong or malicious cheaply: a
 * value outside its slider, an enum member that does not exist, a colour that
 * is not a colour, a field nobody has heard of, and — the one that is a
 * security bound rather than a taste one — a font family or ComponentName
 * carrying a quote, which closes the XML attribute the emitter writes it into.
 */

export interface Problem {
  readonly field: string;
  readonly message: string;
}

export interface FaceSubmission {
  readonly name: string;
  readonly author: string;
  readonly slug: string;
  readonly params: Readonly<Record<string, unknown>>;
}

type Json = Record<string, unknown>;

function isObject(v: unknown): v is Json {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/**
 * Flatten a stored face's `params` the way `FaceCodec.fromJson` does: the
 * nested `layout` object's keys move up beside the rest.
 *
 * The contract's field list comes from `FaceCodec.toQuery`, which is flat, so
 * comparing against it without flattening would reject every real face for
 * having an unknown field called "layout".
 */
export function flatten(params: Json): Json {
  const out: Json = {};
  for (const [k, v] of Object.entries(params)) {
    if (k === "layout") continue;
    out[k] = v;
  }
  const layout = params["layout"];
  if (isObject(layout)) {
    for (const [k, v] of Object.entries(layout)) out[k] = v;
  }
  return out;
}

function checkNumber(
  field: string,
  value: unknown,
  min: number,
  max: number,
  integral: boolean,
  problems: Problem[],
): void {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    problems.push({ field, message: `must be a number, got ${describe(value)}` });
    return;
  }
  if (integral && !Number.isInteger(value)) {
    // A fractional `freq` is not a finer setting; the field is an Int and the
    // next read of it would silently be a different number.
    problems.push({ field, message: `must be a whole number, got ${value}` });
    return;
  }
  if (value < min || value > max) {
    problems.push({ field, message: `must be between ${min} and ${max}, got ${value}` });
  }
}

function checkEnum(field: string, value: unknown, enumName: string, problems: Problem[]): void {
  const members = CONTRACT.enums[enumName];
  if (!members) {
    problems.push({ field, message: `internal: the contract has no '${enumName}' enum` });
    return;
  }
  if (typeof value !== "string" || !members.includes(value)) {
    problems.push({ field, message: `must be one of ${members.join(", ")}, got ${describe(value)}` });
  }
}

function checkPattern(field: string, value: unknown, source: string, problems: Problem[]): void {
  if (typeof value !== "string" || !pattern(source).test(value)) {
    problems.push({ field, message: `does not match ${source}, got ${describe(value)}` });
  }
}

function checkBoolean(field: string, value: unknown, problems: Problem[]): void {
  if (typeof value !== "boolean") {
    problems.push({ field, message: `must be true or false, got ${describe(value)}` });
  }
}

function describe(v: unknown): string {
  if (typeof v === "string") return JSON.stringify(v.length > 40 ? `${v.slice(0, 40)}...` : v);
  if (v === null) return "null";
  if (Array.isArray(v)) return `an array of ${v.length}`;
  if (typeof v === "object") return "an object";
  return String(v);
}

/**
 * One complication token: `SOURCE`, `SOURCE+app:pkg/cls`, `SOURCE+open:pkg/cls`,
 * or both suffixes in that order.
 *
 * `+app:` names the provider filling the slot and `+open:` names what pressing
 * it launches — different jobs, which is why they are different markers. Both
 * halves end up in a WFF attribute, so both go through the ComponentName
 * pattern.
 */
function checkComplicationToken(index: number, token: unknown, problems: Problem[]): void {
  const field = `complications[${index}]`;
  if (typeof token !== "string") {
    problems.push({ field, message: `must be a string, got ${describe(token)}` });
    return;
  }
  const open = token.indexOf("+open:");
  const head = open === -1 ? token : token.slice(0, open);
  const launcher = open === -1 ? null : token.slice(open + "+open:".length);

  const app = head.indexOf("+app:");
  const source = app === -1 ? head : head.slice(0, app);
  const provider = app === -1 ? null : head.slice(app + "+app:".length);

  checkEnum(field, source, "complicationSource", problems);
  if (provider !== null) checkPattern(`${field} provider`, provider, CONTRACT.componentPattern, problems);
  if (launcher !== null) checkPattern(`${field} launcher`, launcher, CONTRACT.componentPattern, problems);
}

/**
 * Everything wrong with a submission, in one pass.
 *
 * All of them rather than the first, matching what the JVM validator does and
 * for the same reason: somebody fixing a face should see every problem at once
 * rather than one per attempt.
 */
export function validateFace(submission: unknown): Problem[] {
  const problems: Problem[] = [];

  if (!isObject(submission)) {
    return [{ field: "body", message: "must be a JSON object" }];
  }

  // ---- the envelope -------------------------------------------------------

  const name = submission["name"];
  if (typeof name !== "string" || name.trim().length === 0) {
    problems.push({ field: "name", message: "is required" });
  } else if (name.trim().length > CONTRACT.maxNameChars) {
    problems.push({ field: "name", message: `must be ${CONTRACT.maxNameChars} characters or fewer` });
  } else if (/[\p{Cc}\p{Cf}]/u.test(name)) {
    // Control and format characters -- including the bidirectional overrides
    // that make one string render as another. A carousel label is not a place
    // for those.
    problems.push({ field: "name", message: "contains control characters" });
  }

  const author = submission["author"] ?? "";
  if (typeof author !== "string") {
    problems.push({ field: "author", message: "must be a string" });
  } else if (author.trim().length > CONTRACT.maxAuthorChars) {
    problems.push({ field: "author", message: `must be ${CONTRACT.maxAuthorChars} characters or fewer` });
  } else if (/[\p{Cc}\p{Cf}]/u.test(author)) {
    problems.push({ field: "author", message: "contains control characters" });
  }

  // The base slug is computed by the app, using the one `FaceLibrary.slugify`
  // both apps share. This checks its SHAPE only -- a second slugifier here
  // would be a second answer to "what package does this face install as".
  const slug = submission["slug"];
  if (typeof slug !== "string" || !pattern(CONTRACT.slugPattern).test(slug)) {
    problems.push({ field: "slug", message: `must match ${CONTRACT.slugPattern}` });
  } else if (slug.length > CONTRACT.maxSlugChars) {
    problems.push({ field: "slug", message: `must be ${CONTRACT.maxSlugChars} characters or fewer` });
  }

  const rawParams = submission["params"];
  if (!isObject(rawParams)) {
    problems.push({ field: "params", message: "is required and must be an object" });
    return problems;
  }

  // ---- the parameters -----------------------------------------------------

  const params = flatten(rawParams);

  for (const key of Object.keys(params)) {
    if (!FIELDS.has(key)) {
      // Strict rather than lenient. The keys `FaceCodec.fromQuery` still READS
      // but no longer writes exist so an old saved face opens; a submission is
      // re-serialized by the app on its way out and always carries the current
      // spelling. Accepting extras would widen the public surface for nobody.
      problems.push({ field: key, message: "is not a parameter this catalog knows" });
    }
  }

  for (const field of FIELDS) {
    if (!(field in params)) {
      problems.push({ field, message: "is missing" });
    }
  }

  const version = params["generatorVersion"];
  checkNumber("generatorVersion", version, 1, CONTRACT.currentGeneratorVersion, true, problems);

  const engine = params["engine"];
  checkEnum("engine", engine, "engine", problems);
  if (typeof engine === "string" && CONTRACT.unpublishableEngines.includes(engine)) {
    // The IP shield and the size guarantee, not a style preference: a face
    // built on an imported image cannot be re-derived from parameters and
    // cannot be licensed by us.
    problems.push({
      field: "engine",
      message:
        `${engine} cannot be published. The catalog is parameters only, so a face ` +
        "built on an imported image stays on the machine that made it",
    });
  }

  const texture = params["texture"];
  if (typeof texture !== "string") {
    problems.push({ field: "texture", message: "must be a string" });
  } else if (texture.length > 0) {
    problems.push({ field: "texture", message: "references an imported image, which cannot be published" });
  }

  for (const [id, control] of CONTROLS) {
    if (id in params) {
      checkNumber(id, params[id], control.min, control.max, control.integral, problems);
    }
  }

  for (const [id, bound] of Object.entries(CONTRACT.bounds)) {
    if (id in params) {
      checkNumber(id, params[id], bound.min, bound.max, bound.integral, problems);
    }
  }

  checkPattern("dialColor", params["dialColor"], CONTRACT.colorPattern, problems);
  checkPattern("inkColor", params["inkColor"], CONTRACT.colorPattern, problems);
  checkPattern("fontFamily", params["fontFamily"], CONTRACT.fontFamilyPattern, problems);
  checkEnum("fontWeight", params["fontWeight"], "fontWeight", problems);

  checkBoolean("showSeconds", params["showSeconds"], problems);
  checkBoolean("lens", params["lens"], problems);

  checkEnum("dateStyle", params["dateStyle"], "dateStyle", problems);
  checkEnum("dateScale", params["dateScale"], "dateScale", problems);
  checkEnum("ring", params["ring"], "ring", problems);
  checkEnum("hourFormat", params["hourFormat"], "hourFormat", problems);

  const slots = CONTRACT.enums["slotPosition"] ?? [];

  const iconSlots = params["iconSlots"];
  if (!Array.isArray(iconSlots)) {
    problems.push({ field: "iconSlots", message: `must be an array, got ${describe(iconSlots)}` });
  } else {
    if (iconSlots.length > slots.length) {
      problems.push({ field: "iconSlots", message: `names more slots than exist (${slots.length})` });
    }
    if (new Set(iconSlots).size !== iconSlots.length) {
      problems.push({ field: "iconSlots", message: "names the same slot twice" });
    }
    iconSlots.forEach((s, i) => checkEnum(`iconSlots[${i}]`, s, "slotPosition", problems));
  }

  const providers = params["providers"];
  if (!isObject(providers)) {
    problems.push({ field: "providers", message: `must be an object, got ${describe(providers)}` });
  } else {
    for (const [pos, component] of Object.entries(providers)) {
      checkEnum(`providers.${pos}`, pos, "slotPosition", problems);
      checkPattern(`providers.${pos}`, component, CONTRACT.componentPattern, problems);
    }
  }

  const complications = params["complications"];
  if (!Array.isArray(complications)) {
    problems.push({ field: "complications", message: `must be an array, got ${describe(complications)}` });
  } else if (complications.length > slots.length) {
    problems.push({
      field: "complications",
      message: `names ${complications.length} slots but there are only ${slots.length}`,
    });
  } else {
    complications.forEach((t, i) => checkComplicationToken(i, t, problems));
  }

  return problems;
}

/**
 * The size limit, checked against the BYTES that actually arrived.
 *
 * Separate from [validateFace] because it is about the request rather than the
 * face, and because it has to be checked before the body is parsed at all.
 *
 * Measured by encoding rather than by `String.length`, which counts UTF-16 code
 * units. A name in Japanese is three bytes a character and one unit, so the two
 * disagree by a factor of three on exactly the faces least likely to be tested
 * — and the JVM validator, which measures `text.toByteArray().size`, would
 * disagree with this one about the same file.
 */
export function tooLarge(text: string): boolean {
  return new TextEncoder().encode(text).length > CONTRACT.maxFaceBytes;
}
