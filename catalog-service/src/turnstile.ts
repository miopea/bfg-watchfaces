import type { Env } from "./env";

/**
 * Proof of humanity, without an account and without tracking anybody.
 *
 * This is the smaller half of R7. Pre-moderation is the substantive abuse
 * control — nothing is public until a person approves it, so flooding the queue
 * costs effort and gains nothing. Turnstile only makes the flooding cost
 * something.
 */
export interface TurnstileResult {
  readonly ok: boolean;
  readonly reason: string;
}

const VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

export async function verifyTurnstile(
  env: Env,
  token: unknown,
  ip: string | null,
): Promise<TurnstileResult> {
  if (!env.TURNSTILE_SECRET) {
    // FAIL CLOSED. A missing secret is a deployment mistake, and the failure
    // mode of guessing the other way is an open submission endpoint that looks
    // like it is working.
    return { ok: false, reason: "the bot check is not configured on this service" };
  }
  if (typeof token !== "string" || token.length === 0) {
    return { ok: false, reason: "the bot check was not completed" };
  }

  const form = new FormData();
  form.append("secret", env.TURNSTILE_SECRET);
  form.append("response", token);
  // Turnstile accepts the address as an optional extra signal. It is sent, not
  // stored: nothing in this service writes an IP anywhere.
  if (ip) form.append("remoteip", ip);

  const response = await fetch(VERIFY_URL, { method: "POST", body: form });
  if (!response.ok) {
    return { ok: false, reason: "the bot check could not be reached" };
  }
  const body = (await response.json()) as { success?: boolean };
  return body.success === true
    ? { ok: true, reason: "" }
    : { ok: false, reason: "the bot check did not pass" };
}
