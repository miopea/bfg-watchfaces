import type { Env } from "./env";
import { getPolicy, reviewModel } from "./ai-review";

/** The operator's deterministic publishing policy, never a model tool call.
 * The audit insert and publication share one atomic D1 batch. */
export async function applyAutomaticApproval(env: Env, faceId: string, lease: string): Promise<{ published: boolean; reason?: string }> {
  const policy = await getPolicy(env);
  if (!policy.enabled || policy.mode !== "automatic") return { published: false };
  const now = Date.now();
  const created = new Date(now).toISOString();
  const hourAgo = new Date(now - 3_600_000).toISOString();
  const dayAgo = new Date(now - 86_400_000).toISOString();
  const eventId = crypto.randomUUID();
  const model = reviewModel(env, policy);
  const result = await env.DB.batch([
    env.DB.prepare(`INSERT INTO automatic_publications(id,face_id,params_hash,generator_version,model,policy,created)
      SELECT ?, f.id, f.params_hash, f.generator_version, ai.model, ?, ?
      FROM faces f
      JOIN face_reviews r ON r.face_id=f.id AND r.params_hash=f.params_hash AND r.generator_version=f.generator_version
      JOIN face_ai_reviews ai ON ai.face_id=f.id AND ai.params_hash=f.params_hash AND ai.generator_version=f.generator_version
      JOIN moderation_jobs j ON j.face_id=f.id AND j.params_hash=f.params_hash AND j.generator_version=f.generator_version
      JOIN moderation_policy p ON p.id=1 JOIN automatic_approval_policy a ON a.id=1
      WHERE f.id=? AND f.state='pending' AND f.author_key IS NOT NULL AND f.author_key<>''
      AND j.lease=? AND j.status='complete' AND j.stage='ai' AND j.updated_at>=?
      AND r.verdict='passed' AND r.preview_base64 IS NOT NULL
      AND ai.recommendation='approve' AND ai.confidence='high' AND ai.created>=? AND ai.model=?
      AND json_extract(ai.signals,'$.policy')=?
      AND json_extract(ai.signals,'$.libraryRevision')=(SELECT revision FROM catalog_revision WHERE id=1)
      AND p.enabled=1 AND a.mode='automatic' AND a.max_per_hour=? AND a.max_per_author_day=?
      AND p.sensitivity=? AND p.comparison_limit=? AND p.pending_warn=? AND p.model IS ?
      AND NOT EXISTS (SELECT 1 FROM blocked_authors b WHERE b.author_key=f.author_key)
      AND NOT EXISTS (SELECT 1 FROM faces duplicate LEFT JOIN face_reviews dr ON dr.face_id=duplicate.id
        WHERE duplicate.state='published' AND duplicate.id<>f.id AND
        (duplicate.params_hash=f.params_hash OR (dr.verdict='passed' AND dr.params_hash=duplicate.params_hash
         AND dr.generator_version=duplicate.generator_version AND dr.preview_base64=r.preview_base64)))
      AND (SELECT COUNT(*) FROM faces pending WHERE pending.author_key=f.author_key AND pending.state='pending') < p.pending_warn
      AND (SELECT COUNT(*) FROM faces published WHERE published.author_key=f.author_key AND published.state='published' AND published.reviewed>=?) < a.max_per_author_day
      AND (SELECT COUNT(*) FROM automatic_publications WHERE created>=?) < a.max_per_hour`)
      .bind(eventId, JSON.stringify(policy), created, faceId, lease, now - 300_000, hourAgo, model, JSON.stringify(policy),
        policy.max_per_hour, policy.max_per_author_day, policy.sensitivity, policy.comparison_limit, policy.pending_warn, policy.model ?? null, dayAgo, hourAgo),
    env.DB.prepare(`UPDATE faces SET state='published',reason=NULL,reviewed=? WHERE id=? AND state='pending'
      AND EXISTS(SELECT 1 FROM automatic_publications e WHERE e.id=? AND e.face_id=faces.id
        AND e.params_hash=faces.params_hash AND e.generator_version=faces.generator_version)`)
      .bind(created, faceId, eventId),
  ]);
  if (result[1]?.meta.changes) return { published: true };
  const reason = "Automatic approval withheld: review freshness, confidence, author limits or library similarity needs a human check.";
  await env.DB.prepare(`UPDATE moderation_jobs SET status='attention',last_error=? WHERE face_id=? AND lease=?
    AND status='complete' AND EXISTS(SELECT 1 FROM faces WHERE id=? AND state='pending')`).bind(reason, faceId, lease, faceId).run();
  return { published: false, reason };
}
