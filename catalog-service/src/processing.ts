import type { Env } from "./env";
import { getPolicy } from "./ai-review";
import type { ReviewWriteResult } from "./review";

const LEASE_MS = 5 * 60_000;
const MAX_ATTEMPTS = 5;
const error = (status: number, message: string): ReviewWriteResult => ({ status, body: { error: message } });

export async function claimProcessing(env: Env, id: string): Promise<ReviewWriteResult> {
  const now = Date.now();
  const lease = crypto.randomUUID();
  const claimed = await env.DB.prepare(`
    INSERT INTO moderation_jobs(face_id, params_hash, generator_version, status, stage, attempts, lease, lease_until, next_attempt, updated_at)
    SELECT id, params_hash, generator_version, 'running', 'preview', 1, ?, ?, 0, ? FROM faces WHERE id = ? AND state = 'pending'
    ON CONFLICT(face_id) DO UPDATE SET params_hash = excluded.params_hash, generator_version = excluded.generator_version,
      status = 'running', stage = 'preview',
      attempts = CASE WHEN moderation_jobs.params_hash = excluded.params_hash AND moderation_jobs.generator_version = excluded.generator_version THEN moderation_jobs.attempts + 1 ELSE 1 END,
      lease = excluded.lease, lease_until = excluded.lease_until, next_attempt = 0, last_error = NULL, updated_at = excluded.updated_at
    WHERE moderation_jobs.params_hash <> excluded.params_hash OR moderation_jobs.generator_version <> excluded.generator_version
       OR (moderation_jobs.status IN ('waiting','retry','running') AND moderation_jobs.lease_until <= ?
           AND moderation_jobs.next_attempt <= ? AND moderation_jobs.attempts < ?)
  `).bind(lease, now + LEASE_MS, now, id, now, now, MAX_ATTEMPTS).run();
  if (!claimed.meta.changes) return { status: 200, body: { claimed: false } };
  const policy = await getPolicy(env);
  return { status: 200, body: { claimed: true, lease, aiEnabled: !!policy.enabled } };
}

export async function validProcessingLease(env: Env, id: string, lease: string): Promise<boolean> {
  return !!await env.DB.prepare(`SELECT 1 FROM moderation_jobs j JOIN faces f ON f.id = j.face_id
    WHERE j.face_id = ? AND j.lease = ? AND j.status = 'running' AND j.lease_until > ?
      AND f.state = 'pending' AND f.params_hash = j.params_hash AND f.generator_version = j.generator_version`)
    .bind(id, lease, Date.now()).first();
}

export async function reportProcessing(env: Env, id: string, body: Record<string, unknown>): Promise<ReviewWriteResult> {
  const lease = body["lease"];
  const status = body["status"];
  const stage = body["stage"];
  if (typeof lease !== "string" || !["running", "retry", "attention", "complete"].includes(String(status)) || !["preview", "ai"].includes(String(stage)))
    return error(422, "a lease, processing status and stage are required");
  if (!await validProcessingLease(env, id, lease)) return error(409, "processing lease expired or submission changed");
  const job = await env.DB.prepare("SELECT attempts FROM moderation_jobs WHERE face_id = ? AND lease = ?").bind(id, lease).first<{ attempts: number }>();
  const now = Date.now();
  const outcome = status === "retry" && Number(job?.attempts) >= MAX_ATTEMPTS ? "failed" : status;
  const detail = typeof body["error"] === "string" ? body["error"].slice(0, 300) : null;
  const next = outcome === "retry" ? now + Math.min(30 * 60_000, 60_000 * 2 ** Number(job?.attempts ?? 1)) : 0;
  const changed = await env.DB.prepare(`UPDATE moderation_jobs SET status = ?, stage = ?, last_error = ?, next_attempt = ?, updated_at = ?,
      lease_until = CASE WHEN ? = 'running' THEN lease_until ELSE 0 END,
      completed_at = CASE WHEN ? = 'complete' THEN ? ELSE completed_at END
    WHERE face_id = ? AND lease = ? AND status = 'running' AND lease_until > ?`)
    .bind(outcome, stage, detail, next, now, outcome, outcome, now, id, lease, now).run();
  return changed.meta.changes ? { status: 200, body: { ok: true, status: outcome, nextAttempt: next || null } } : error(409, "processing lease expired");
}

export async function retryProcessing(env: Env, id: string): Promise<ReviewWriteResult> {
  const now = Date.now();
  const changed = await env.DB.prepare(`INSERT INTO moderation_jobs(face_id, params_hash, generator_version, status, updated_at)
    SELECT id, params_hash, generator_version, 'waiting', ? FROM faces WHERE id = ? AND state = 'pending'
    ON CONFLICT(face_id) DO UPDATE SET status = 'waiting', attempts = 0, lease = NULL, lease_until = 0,
      next_attempt = 0, last_error = NULL, updated_at = excluded.updated_at
    WHERE moderation_jobs.lease_until <= ?`)
    .bind(now, id, now).run();
  return changed.meta.changes ? { status: 200, body: { ok: true, status: "waiting" } } : error(409, "submission is not pending or is already processing");
}

export async function runnerHeartbeat(env: Env, success: boolean): Promise<void> {
  const now = Date.now();
  await env.DB.prepare(`UPDATE moderation_runner SET last_seen = ?, last_success = CASE WHEN ? THEN ? ELSE last_success END,
    last_error = ? WHERE id = 1`).bind(now, success ? 1 : 0, now, success ? null : "The moderation runner failed; check pending review errors and runner logs.").run();
}
