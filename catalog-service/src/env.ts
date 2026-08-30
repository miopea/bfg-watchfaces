/**
 * What the Worker is given at runtime.
 *
 * The three secrets are set with `wrangler secret put` and are never in
 * `wrangler.toml`, which is committed.
 */
export interface Env {
  readonly DB: D1Database;

  /** Public half of the Turnstile pair. Only ever echoed to a client. */
  readonly TURNSTILE_SITE_KEY: string;

  /**
   * Private half. Absent means proof-of-humanity cannot be checked, and the
   * write endpoints FAIL CLOSED rather than accepting everything — a missing
   * secret must not silently become an open submission endpoint.
   */
  readonly TURNSTILE_SECRET?: string;

  /**
   * The only credential in the system, guarding `/admin/*`.
   *
   * Absent means every moderation endpoint returns 503. There is no fallback
   * and no default value: a default moderator token is a published one.
   */
  readonly MODERATOR_TOKEN?: string;

  /**
   * Salts the hashed IP used for rate limiting, so what is stored cannot be
   * reversed into an address by trying all four billion of them.
   */
  readonly IP_SALT?: string;
}
