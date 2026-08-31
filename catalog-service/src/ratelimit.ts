import type { Env } from "./env";
import { sha256 } from "./hash";

/**
 * A speed bump, and named as one.
 *
 * With no accounts there is nothing to ban, so this cannot be the abuse
 * control — pre-moderation is. All this does is stop one machine filling the
 * queue in a second, and it is defeated by anyone with more than one address.
 *
 * What is stored is a SALTED HASH of the address and a count. Never the
 * address: the About screen promises nothing about the person is sent, and an
 * IP is something about a person.
 */
export interface Limit {
  readonly route: string;
  readonly max: number;
  readonly windowSeconds: number;
}

export const LIMITS = {
  submit: { route: "submit", max: 10, windowSeconds: 3600 },
  report: { route: "report", max: 20, windowSeconds: 3600 },
  // Deliberately loose. This one is on the app's critical path and a person
  // reinstalling a few faces in a row is not abuse; the count is a hint for
  // ordering a gallery, not a truth, so an inflated one costs little.
  installed: { route: "installed", max: 120, windowSeconds: 3600 },
} as const satisfies Record<string, Limit>;

export interface Verdict {
  readonly allowed: boolean;
  readonly retryAfterSeconds: number;
}

export async function checkRate(
  env: Env,
  limit: Limit,
  ip: string | null,
): Promise<Verdict> {
  // No address means no bucket to count in. Allowed rather than refused: this
  // is a speed bump, and refusing everyone behind a proxy that strips the
  // header would break the app for them while stopping nobody.
  if (!ip) return { allowed: true, retryAfterSeconds: 0 };

  const now = Math.floor(Date.now() / 1000);
  const window = Math.floor(now / limit.windowSeconds);
  const bucket = await sha256(`${env.IP_SALT ?? ""}:${limit.route}:${window}:${ip}`);
  const expires = (window + 1) * limit.windowSeconds;

  // One statement, so two requests racing cannot both read 9 and both write 10.
  const row = await env.DB.prepare(
    `INSERT INTO rate (bucket, hits, expires) VALUES (?, 1, ?)
     ON CONFLICT(bucket) DO UPDATE SET hits = hits + 1
     RETURNING hits`,
  )
    .bind(bucket, expires)
    .first<{ hits: number }>();

  const hits = row?.hits ?? 1;
  return hits <= limit.max
    ? { allowed: true, retryAfterSeconds: 0 }
    : { allowed: false, retryAfterSeconds: Math.max(1, expires - now) };
}

/**
 * Drop expired buckets.
 *
 * Called opportunistically after a write rather than on a schedule: a cron
 * trigger for a table that only ever holds an hour of counters is a moving part
 * with nothing to do. Cheap because `rate_by_expiry` covers it.
 */
export async function sweepRate(env: Env): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  await env.DB.prepare(`DELETE FROM rate WHERE expires < ?`).bind(now).run();
}
