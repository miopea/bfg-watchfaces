import raw from "../params-contract.json";

/**
 * The generated params contract, typed.
 *
 * `params-contract.json` is written by `./gradlew :workbench:contract` from
 * `CatalogContract` in `:generator`, and `ContractFileTest` fails if the
 * committed copy goes stale. Nothing in this directory decides what a legal
 * face looks like — it reads the answer.
 *
 * That is not tidiness. A range written out again here would be a second
 * definition of the file format, and `ControlInventory`'s own header explains
 * what this repo has already paid for that: "A test that two copies match
 * cannot tell you they are both correct."
 */
export interface Control {
  readonly id: string;
  readonly min: number;
  readonly max: number;
  readonly step: number;
  readonly integral: boolean;
  readonly target: string;
}

export interface Bound {
  readonly min: number;
  readonly max: number;
  readonly integral: boolean;
}

export interface Contract {
  readonly contractVersion: number;
  readonly currentGeneratorVersion: number;
  readonly maxFaceBytes: number;
  readonly maxNameChars: number;
  readonly maxAuthorChars: number;
  readonly colorPattern: string;
  readonly fontFamilyPattern: string;
  readonly componentPattern: string;
  readonly slugPattern: string;
  readonly maxSlugChars: number;
  readonly publishedIdChars: number;
  readonly controls: readonly Control[];
  readonly bounds: Readonly<Record<string, Bound>>;
  readonly enums: Readonly<Record<string, readonly string[]>>;
  readonly unpublishableEngines: readonly string[];
  /**
   * Dial images that ship inside the app.
   *
   * The only textures a face may name. Every other value points at a picture
   * only the sender has, which is why `texture` is otherwise refused outright.
   */
  readonly builtInDials: readonly string[];
  readonly fields: readonly string[];
}

export const CONTRACT: Contract = raw;

/**
 * The shape this code was written against.
 *
 * `contractVersion` moves when the FILE is laid out differently — a renamed
 * key, a new section — as opposed to `currentGeneratorVersion`, which moves
 * when a face's geometry changes. A mismatch means this Worker would read half
 * the file and silently skip the rest, so it refuses to start instead.
 */
export const SUPPORTED_CONTRACT_VERSION = 1;

if (CONTRACT.contractVersion !== SUPPORTED_CONTRACT_VERSION) {
  throw new Error(
    `params-contract.json is version ${CONTRACT.contractVersion}, this Worker reads ` +
      `${SUPPORTED_CONTRACT_VERSION}. Validation would silently skip whatever changed.`,
  );
}

/** Controls, by id, so validation is a lookup rather than a scan per field. */
export const CONTROLS: ReadonlyMap<string, Control> = new Map(
  CONTRACT.controls.map((c) => [c.id, c]),
);

export const FIELDS: ReadonlySet<string> = new Set(CONTRACT.fields);

const compiled = new Map<string, RegExp>();

/** Compile once. A Worker isolate serves many requests. */
export function pattern(source: string): RegExp {
  const existing = compiled.get(source);
  if (existing) return existing;
  const re = new RegExp(source);
  compiled.set(source, re);
  return re;
}
