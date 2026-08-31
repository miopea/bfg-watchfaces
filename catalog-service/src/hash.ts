/**
 * SHA-256, hex.
 *
 * Two jobs, both of which need a stable digest and neither of which is a
 * password: identifying byte-identical parameters, and salting an IP so rate
 * limiting can count without storing an address.
 */
export async function sha256(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * A stable hash of a face's parameters.
 *
 * Keys are sorted and the nested `layout` is flattened first, so two faces that
 * differ only in the order their JSON was written hash the same. Without that,
 * "byte-identical submissions are rejected" would mean "submissions whose JSON
 * happened to be serialized in the same order are rejected", which is a
 * different and much weaker rule.
 */
export async function paramsHash(flatParams: Record<string, unknown>): Promise<string> {
  const canonical = JSON.stringify(
    Object.keys(flatParams)
      .sort()
      .map((k) => [k, canonicalize(flatParams[k])]),
  );
  return sha256(canonical);
}

function canonicalize(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(canonicalize);
  if (typeof v === "object" && v !== null) {
    return Object.keys(v as Record<string, unknown>)
      .sort()
      .map((k) => [k, canonicalize((v as Record<string, unknown>)[k])]);
  }
  return v;
}
