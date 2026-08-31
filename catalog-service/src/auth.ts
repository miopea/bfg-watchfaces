import type { Env } from "./env";

/**
 * Who is publishing.
 *
 * Verifying a Google ID token, which replaced the bot check entirely. A CAPTCHA
 * asks "are you a person"; an account answers "which person", and that is the
 * handle this whole design was missing — with no account there is nobody to
 * block, which is what made pre-moderation mandatory rather than chosen.
 *
 * ## What is kept
 *
 * `sha256(salt + sub)`. `sub` is Google's per-application subject id: stable for
 * that person in this app, meaningless anywhere else. Hashing means a copy of
 * the database does not hand out Google subject ids.
 *
 * **The token carries the person's email and name whether the app asks or not.**
 * Nothing here reads them, stores them, or logs them — but "we never see it"
 * would be false and must not be claimed anywhere. What is true is that they go
 * no further than this function's local variable.
 *
 * ## Verification is real, not decorative
 *
 * A token is signed JSON, so an unverified one is a claim by whoever sent it.
 * RS256 against Google's published keys, and every one of `iss`, `aud` and `exp`
 * checked — `aud` especially: a token minted for a DIFFERENT application is
 * perfectly valid and signed by Google, and accepting it would let any app's
 * sign-in publish here.
 */

const GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_ISSUERS = ["accounts.google.com", "https://accounts.google.com"];

export interface Identity {
  /** The stored pseudonym. Never the subject id itself. */
  readonly authorKey: string;
}

export type AuthResult =
  | { readonly ok: true; readonly identity: Identity }
  | { readonly ok: false; readonly reason: string };

function base64UrlToBytes(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

function decodeJson(part: string): Record<string, unknown> {
  return JSON.parse(new TextDecoder().decode(base64UrlToBytes(part))) as Record<string, unknown>;
}

interface Jwk {
  kid?: string;
  kty?: string;
  alg?: string;
  n?: string;
  e?: string;
}

/**
 * Google's signing keys, cached at the edge.
 *
 * They rotate, so this must not be pinned. The Cache API keeps the fetch off
 * the critical path without inventing a cache of our own.
 */
async function signingKeys(): Promise<Jwk[]> {
  const response = await fetch(GOOGLE_JWKS, { cf: { cacheTtl: 3600, cacheEverything: true } });
  if (!response.ok) throw new Error(`could not fetch Google's signing keys: ${response.status}`);
  const body = (await response.json()) as { keys?: Jwk[] };
  return body.keys ?? [];
}

/** SHA-256, hex. Same shape as hash.ts; kept separate so salting stays here. */
async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Verify a Google ID token and return the pseudonym to store.
 *
 * [fetchKeys] is injectable so the tests can verify a REAL signature against a
 * key they generated, rather than stubbing verification itself out. A test that
 * skips the verifying is a test of nothing.
 */
export async function identify(
  env: Env,
  authorization: string | null,
  fetchKeys: () => Promise<Jwk[]> = signingKeys
): Promise<AuthResult> {
  if (!env.GOOGLE_CLIENT_ID) {
    // Fail closed, exactly as the bot check did. A missing client id must never
    // become "accept anybody", which from the outside looks like working.
    return { ok: false, reason: "sign-in is not configured on this service" };
  }
  const token = (authorization ?? "").replace(/^Bearer\s+/i, "").trim();
  if (!token) return { ok: false, reason: "you need to be signed in to do that" };

  const parts = token.split(".");
  if (parts.length !== 3 || !parts[0] || !parts[1] || !parts[2]) {
    return { ok: false, reason: "that sign-in could not be read" };
  }
  const [headerPart, payloadPart, signaturePart] = parts as [string, string, string];

  let header: Record<string, unknown>;
  let payload: Record<string, unknown>;
  try {
    header = decodeJson(headerPart);
    payload = decodeJson(payloadPart);
  } catch {
    return { ok: false, reason: "that sign-in could not be read" };
  }

  if (header["alg"] !== "RS256") {
    // Refusing anything else is the point: `alg: none` and algorithm confusion
    // are the two classic ways a JWT check gets bypassed.
    return { ok: false, reason: "that sign-in is not signed the way Google signs" };
  }

  const keys = await fetchKeys();
  const key = keys.find((k) => k.kid === header["kid"]) ?? null;
  if (!key) return { ok: false, reason: "that sign-in was not signed by a key Google publishes" };

  const publicKey = await crypto.subtle.importKey(
    "jwk",
    { kty: "RSA", n: key.n, e: key.e, alg: "RS256", ext: true },
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"]
  );
  const signed = new TextEncoder().encode(`${headerPart}.${payloadPart}`);
  const valid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    publicKey,
    base64UrlToBytes(signaturePart),
    signed
  );
  if (!valid) return { ok: false, reason: "that sign-in could not be verified" };

  const issuer = String(payload["iss"] ?? "");
  if (!GOOGLE_ISSUERS.includes(issuer)) {
    return { ok: false, reason: "that sign-in did not come from Google" };
  }
  // THE ONE MOST EASILY LEFT OUT. A token minted for another application is
  // genuinely signed by Google and completely valid -- accepting it would let
  // any app's sign-in publish here.
  if (payload["aud"] !== env.GOOGLE_CLIENT_ID) {
    return { ok: false, reason: "that sign-in was issued for a different app" };
  }
  const expiry = Number(payload["exp"] ?? 0);
  if (!Number.isFinite(expiry) || expiry * 1000 <= Date.now()) {
    return { ok: false, reason: "that sign-in has expired; sign in again" };
  }

  const subject = String(payload["sub"] ?? "");
  if (!subject) return { ok: false, reason: "that sign-in names nobody" };

  // From here on the email and name in `payload` are never touched again.
  return { ok: true, identity: { authorKey: await sha256Hex(`${env.IP_SALT ?? ""}:author:${subject}`) } };
}
