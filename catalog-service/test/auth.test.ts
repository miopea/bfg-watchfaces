import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { identify } from "../src/auth";
import { makeToken, testJwks } from "./tokens";

/**
 * The identity check, against tokens that are really signed and really
 * verified — only the key is ours instead of Google's.
 *
 * A CAPTCHA asked "are you a person". This answers "which person", which is the
 * handle the whole catalog design was missing: with nobody to block,
 * pre-moderation was the only control there was.
 *
 * Every test here is a way of getting in that must not work. A verifier that
 * accepts everything passes no test in this file.
 */
describe("identify", () => {
  const keys = testJwks;

  it("accepts a properly signed token and returns a pseudonym", async () => {
    const result = await identify(env, `Bearer ${await makeToken()}`, keys);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.identity.authorKey).toMatch(/^[0-9a-f]{64}$/);
  });

  it("gives the same person the same key, and different people different keys", async () => {
    // This is what makes blocking, withdrawal and "my faces" work at all, and
    // it has to survive a reinstall -- which is the whole reason the random
    // per-install id it replaces was not good enough.
    const first = await identify(env, `Bearer ${await makeToken({ sub: "aaa" })}`, keys);
    const again = await identify(env, `Bearer ${await makeToken({ sub: "aaa" })}`, keys);
    const other = await identify(env, `Bearer ${await makeToken({ sub: "bbb" })}`, keys);
    if (!first.ok || !again.ok || !other.ok) throw new Error("a valid token was refused");
    expect(first.identity.authorKey).toBe(again.identity.authorKey);
    expect(first.identity.authorKey).not.toBe(other.identity.authorKey);
  });

  it("never returns the subject id itself", async () => {
    const result = await identify(env, `Bearer ${await makeToken({ sub: "1234567890" })}`, keys);
    if (!result.ok) throw new Error("refused");
    expect(result.identity.authorKey).not.toContain("1234567890");
  });

  /**
   * THE ONE MOST EASILY LEFT OUT.
   *
   * A token minted for another application is genuinely signed by Google and
   * entirely valid. Skipping this check would let any app's sign-in publish
   * here, and nothing would look wrong.
   */
  it("refuses a token issued for a different app", async () => {
    const result = await identify(env, `Bearer ${await makeToken({ aud: "some-other-app" })}`, keys);
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toContain("different app");
  });

  it("refuses a token whose signature does not match its payload", async () => {
    const result = await identify(env, `Bearer ${await makeToken({ tamper: true })}`, keys);
    expect(result.ok).toBe(false);
  });

  it("refuses an unsigned token", async () => {
    // `alg: none` is the oldest way a JWT check gets bypassed.
    const result = await identify(env, `Bearer ${await makeToken({ alg: "none" })}`, keys);
    expect(result.ok).toBe(false);
  });

  it("refuses a token signed by a key Google does not publish", async () => {
    const result = await identify(env, `Bearer ${await makeToken({ kid: "not-a-google-key" })}`, keys);
    expect(result.ok).toBe(false);
  });

  it("refuses an expired token", async () => {
    const result = await identify(env, `Bearer ${await makeToken({ expiresInSeconds: -60 })}`, keys);
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toContain("expired");
  });

  it("refuses a token from somewhere that is not Google", async () => {
    const result = await identify(env, `Bearer ${await makeToken({ iss: "https://evil.example" })}`, keys);
    expect(result.ok).toBe(false);
  });

  it("refuses nonsense and nothing at all", async () => {
    expect((await identify(env, null, keys)).ok).toBe(false);
    expect((await identify(env, "Bearer not.a.jwt", keys)).ok).toBe(false);
    expect((await identify(env, "Bearer ", keys)).ok).toBe(false);
  });

  /**
   * Fail closed, exactly as the bot check did. A missing client id must never
   * become "accept anybody" — from the outside that looks like working.
   */
  it("refuses everybody when sign-in is not configured", async () => {
    const unconfigured = { ...env, GOOGLE_CLIENT_ID: undefined };
    const result = await identify(unconfigured, `Bearer ${await makeToken()}`, keys);
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toContain("not configured");
  });
});
