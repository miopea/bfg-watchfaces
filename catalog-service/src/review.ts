import type { Env } from "./env";

export interface ReviewWriteResult {
  status: number;
  body: Record<string, unknown>;
}

const MAX_PREVIEW_BASE64 = 1_000_000;

/** Record the JVM moderation verdict and its exact rendered PNG. */
export async function recordReview(
  request: Request,
  env: Env,
  id: string,
): Promise<ReviewWriteResult> {
  let body: Record<string, unknown>;
  try {
    body = JSON.parse(await request.text()) as Record<string, unknown>;
  } catch {
    return result(422, "review body must be JSON");
  }
  const paramsHash = typeof body["paramsHash"] === "string" ? body["paramsHash"] : "";
  const generatorVersion = body["generatorVersion"];
  const verdict = body["verdict"];
  const problems = Array.isArray(body["problems"])
    ? body["problems"].filter((p): p is string => typeof p === "string").slice(0, 20)
    : [];
  const preview = typeof body["previewBase64"] === "string" ? body["previewBase64"] : null;

  if (!/^[0-9a-f]{64}$/.test(paramsHash)) return result(422, "a params hash is required");
  if (!Number.isInteger(generatorVersion)) return result(422, "a generator version is required");
  if (verdict !== "passed" && verdict !== "failed") return result(422, "verdict must be passed or failed");
  if (verdict === "passed" && !validPng(preview)) return result(422, "a valid PNG preview is required when validation passes");
  if (verdict === "failed" && preview !== null) return result(422, "a failed review must not carry a preview");

  const face = await env.DB.prepare(
    `SELECT params_hash, generator_version, state FROM faces WHERE id = ?`,
  )
    .bind(id)
    .first<{ params_hash: string; generator_version: number; state: string }>();
  if (!face) return result(404, "no such submission");
  if (face.state !== "pending") return result(409, "only a pending submission can be reviewed");
  if (face.params_hash !== paramsHash || Number(face.generator_version) !== generatorVersion) {
    return result(409, "the submission changed; render its current stored parameters");
  }

  await env.DB.prepare(
    `INSERT INTO face_reviews
       (face_id, params_hash, generator_version, verdict, problems, preview_base64, created)
     VALUES (?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(face_id) DO UPDATE SET
       params_hash = excluded.params_hash,
       generator_version = excluded.generator_version,
       verdict = excluded.verdict,
       problems = excluded.problems,
       preview_base64 = excluded.preview_base64,
       created = excluded.created`,
  )
    .bind(
      id,
      paramsHash,
      generatorVersion,
      verdict,
      JSON.stringify(problems),
      preview,
      new Date().toISOString(),
    )
    .run();
  return { status: 200, body: { ok: true, id, verdict } };
}

/** Publish only when the current stored face has a matching passed review. */
export async function publishReviewed(
  env: Env,
  id: string,
): Promise<ReviewWriteResult> {
  const face = await env.DB.prepare(`SELECT state FROM faces WHERE id = ?`)
    .bind(id)
    .first<{ state: string }>();
  if (!face) return result(404, "no such submission");
  if (face.state !== "pending") return result(409, "only a pending submission can be published");

  const changed = await env.DB.prepare(
    `UPDATE faces SET state = 'published', reason = NULL, reviewed = ?
       WHERE id = ? AND state = 'pending'
         AND EXISTS (
           SELECT 1 FROM face_reviews r
            WHERE r.face_id = faces.id
              AND r.params_hash = faces.params_hash
              AND r.generator_version = faces.generator_version
              AND r.verdict = 'passed'
              AND r.preview_base64 IS NOT NULL
         )`,
  )
    .bind(new Date().toISOString(), id)
    .run();
  if (!changed.meta.changes) {
    return result(409, "a matching passed JVM review and trusted preview are required");
  }
  return { status: 200, body: { ok: true, id, state: "published" } };
}

function validPng(value: string | null): boolean {
  if (!value || value.length > MAX_PREVIEW_BASE64 || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return false;
  try {
    return atob(value.slice(0, 16)).startsWith("\u0089PNG\r\n\u001a\n");
  } catch {
    return false;
  }
}

function result(status: number, error: string): ReviewWriteResult {
  return { status, body: { error } };
}
