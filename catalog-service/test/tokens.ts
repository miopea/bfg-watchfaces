/**
 * Real RS256 tokens, signed by a key this file generates.
 *
 * The alternative was stubbing verification out, which would test nothing: the
 * whole point of `identify` is that it REFUSES tokens, and a fake verifier
 * refuses nothing. These are genuinely signed and genuinely verified — only the
 * key is ours instead of Google's.
 */

import { TEST_KID, TEST_PRIVATE_JWK, TEST_PUBLIC_JWK } from "./testkey";

let signingKey: CryptoKey | null = null;

async function key(): Promise<CryptoKey> {
  if (signingKey) return signingKey;
  signingKey = await crypto.subtle.importKey(
    "jwk",
    TEST_PRIVATE_JWK as JsonWebKey,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  return signingKey;
}

export { TEST_KID };

/** The JWKS `identify` is pointed at, standing in for Google's. */
export async function testJwks(): Promise<Array<{ kid?: string; n?: string; e?: string }>> {
  return [TEST_PUBLIC_JWK];
}

function b64url(bytes: Uint8Array | string): string {
  const raw =
    typeof bytes === "string"
      ? bytes
      : String.fromCharCode(...bytes);
  return btoa(raw).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export interface TokenOptions {
  sub?: string;
  aud?: string;
  iss?: string;
  expiresInSeconds?: number;
  kid?: string;
  alg?: string;
  /** Sign a DIFFERENT payload than the one sent, to prove the signature is checked. */
  tamper?: boolean;
}

export async function makeToken(options: TokenOptions = {}): Promise<string> {
  const header = {
    alg: options.alg ?? "RS256",
    kid: options.kid ?? TEST_KID,
    typ: "JWT",
  };
  const payload = {
    iss: options.iss ?? "https://accounts.google.com",
    aud: options.aud ?? "test-client-id.apps.googleusercontent.com",
    sub: options.sub ?? "1234567890",
    // Present on a real Google token whether the app wants them or not. Here on
    // purpose: a test asserts they never reach the database.
    email: "someone@example.com",
    name: "Someone Real",
    exp: Math.floor(Date.now() / 1000) + (options.expiresInSeconds ?? 3600),
    iat: Math.floor(Date.now() / 1000),
  };

  const headerPart = b64url(JSON.stringify(header));
  const payloadPart = b64url(JSON.stringify(payload));
  const signedPart = options.tamper
    ? b64url(JSON.stringify({ ...payload, sub: "somebody-else" }))
    : payloadPart;

  const signature = new Uint8Array(
    await crypto.subtle.sign(
      "RSASSA-PKCS1-v1_5",
      await key(),
      new TextEncoder().encode(`${headerPart}.${signedPart}`)
    )
  );
  return `${headerPart}.${payloadPart}.${b64url(signature)}`;
}
