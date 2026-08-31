/**
 * What the Worker is given at runtime.
 *
 * The three secrets are set with `wrangler secret put` and are never in
 * `wrangler.toml`, which is committed.
 */
export interface Env {
  readonly DB: D1Database;

  /**
   * The OAuth client id every ID token must be issued for.
   *
   * Public, and it has to be: the app needs it too. Absent means publishing
   * fails closed rather than accepting anybody — a missing client id must not
   * silently become an open submission endpoint.
   */
  readonly GOOGLE_CLIENT_ID?: string;

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
