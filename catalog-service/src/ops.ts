import type { Env } from "./env";
import { configureAiPolicy, getPolicy, recommendFace } from "./ai-review";
import { publishReviewed } from "./review";

/**
 * The BFG ops contract, so this catalog appears in the ops console as a
 * managed app rather than as a Gradle command on somebody's laptop.
 *
 * WHY THE CONSOLE CANNOT JUST CALL /admin. The console only ever talks to an
 * app through the contract: a manifest that declares capabilities, and a
 * generic view/action pair per capability. That is what keeps the console free
 * of per-app code — nothing in `web/src` knows what a watch face is, and a test
 * there fails the build if anything learns.
 *
 * SO THIS IS A TRANSLATION LAYER, NOT A SECOND API. Every handler below reads
 * or writes through the same queries `/admin/*` already uses. There is one
 * moderation implementation; this shapes it for a different caller.
 */

const CAPABILITY = "inbox";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

/**
 * Scope-split auth, accepted before the console sends it.
 *
 * 403 WITH THE SCOPE NAMED, NEVER 401. A 401 says "who are you"; the caller
 * knows exactly who it is and has a valid credential — it brought the wrong
 * ONE. Saying so is the difference between a token that needs rotating and a
 * grant that needs widening, and this fleet has already lost an evening to a
 * 401 that meant an empty string.
 *
 * Falls back to MODERATOR_TOKEN so this works before the split secrets exist.
 * When OPS_TOKEN_READ/WRITE are set they take precedence and the fallback stops
 * applying, so adding them is the whole migration.
 */
function authorise(
  request: Request,
  env: Env,
  required: "read" | "write",
): Response | null {
  const offered = request.headers.get("authorization") ?? "";
  const readToken = env.OPS_TOKEN_READ ?? env.MODERATOR_TOKEN;
  const writeToken = env.OPS_TOKEN_WRITE ?? env.MODERATOR_TOKEN;
  const wanted = required === "write" ? writeToken : readToken;
  if (!wanted) {
    return json({ error: "no ops token configured", required }, 403);
  }
  if (offered === `Bearer ${wanted}`) return null;
  // A read token presented for a write is the interesting case: say which
  // scope was offered and which was needed, so the fix is obvious.
  const offeredScope =
    readToken && offered === `Bearer ${readToken}` ? "read" : "unknown";
  return json({ error: "wrong scope", scope: offeredScope, required }, 403);
}

export async function ops(
  request: Request,
  env: Env,
  path: string,
  method: string,
): Promise<Response | null> {
  if (method === "GET" && path === "/api/ops/manifest") {
    const denied = authorise(request, env, "read");
    if (denied) return denied;
    return json({
      appId: "bfg-watchfaces-catalog",
      displayName: "Watch Face Catalog",
      contractVersion: 1,
      buildSha: env.BUILD_SHA ?? null,
      environment: "production",
      capabilities: [CAPABILITY],
      pushIntervalSec: null,
    });
  }

  if (method === "GET" && path === "/api/ops/health") {
    const denied = authorise(request, env, "read");
    if (denied) return denied;
    return json(await health(env));
  }

  if (method === "GET" && path === "/api/ops/metrics") {
    const denied = authorise(request, env, "read");
    if (denied) return denied;
    return json(await metrics(env));
  }

  if (method === "GET" && path === "/api/ops/incidents") {
    const denied = authorise(request, env, "read");
    if (denied) return denied;
    return json(await incidents(env));
  }

  if (method === "GET" && path === `/api/ops/${CAPABILITY}`) {
    const denied = authorise(request, env, "read");
    if (denied) return denied;
    return json(await queueView(env));
  }

  if (method === "GET" && path === `/api/ops/${CAPABILITY}/actions`) {
    const denied = authorise(request, env, "read");
    if (denied) return denied;
    return json(await actionCatalog(env));
  }

  const act = new RegExp(
    `^/api/ops/${CAPABILITY}/actions/(publish|reject|remove|ai-recommend|configure-ai)$`,
  ).exec(path);
  if (method === "POST" && act?.[1]) {
    const denied = authorise(request, env, "write");
    if (denied) return denied;
    return execute(request, env, act[1]);
  }

  return null;
}

/**
 * A REAL READ against the table moderation depends on, not `SELECT 1`.
 *
 * `SELECT 1` passes on a database whose tables have gone; the console's own
 * house rule is that a check whose red path cannot execute is not a check.
 * Counting the faces table fails if D1 is unreachable or the schema is gone,
 * which are the failures that would actually stop moderation.
 *
 * NO `disk` CHECK, DELIBERATELY. A Worker has no filesystem to report on, and
 * the console's conformance harness will warn about its absence. That warning
 * is correct and should stay: inventing a green disk check to silence it would
 * be exactly the always-healthy reporting the contract exists to replace.
 */
async function health(env: Env): Promise<unknown> {
  const checkedAt = Date.now();
  try {
    const row = await env.DB.prepare(`SELECT COUNT(*) AS n FROM faces`).first<{
      n: number;
    }>();
    return {
      status: "healthy",
      checkedAt,
      checks: [
        {
          name: "catalog-d1",
          kind: "database",
          status: "healthy",
          detail: `read probe against faces returned ${row?.n ?? 0} row(s)`,
          observed: row?.n ?? 0,
        },
      ],
    };
  } catch (error) {
    return {
      status: "unhealthy",
      checkedAt,
      checks: [
        {
          name: "catalog-d1",
          kind: "database",
          status: "unhealthy",
          detail: `read probe failed: ${(error as Error).message}`.slice(
            0,
            500,
          ),
        },
      ],
    };
  }
}

/**
 * The numbers that answer "is anything waiting for me" without opening a tab.
 *
 * `moderation.pending` is the one that matters: the console can alert on it,
 * so a queue backing up is noticed rather than discovered.
 */
async function metrics(env: Env): Promise<unknown> {
  const states = await env.DB.prepare(
    `SELECT state, COUNT(*) AS n FROM faces GROUP BY state`,
  ).all<{ state: string; n: number }>();
  const byState = new Map(
    (states.results ?? []).map((r) => [r.state, Number(r.n)]),
  );
  const reports = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM reports WHERE state = 'open'`,
  ).first<{ n: number }>();
  return {
    collectedAt: Date.now(),
    metrics: [
      {
        name: "moderation.pending",
        kind: "gauge",
        value: byState.get("pending") ?? 0,
        unit: "count",
      },
      {
        name: "moderation.published",
        kind: "gauge",
        value: byState.get("published") ?? 0,
        unit: "count",
      },
      {
        name: "moderation.rejected",
        kind: "gauge",
        value: byState.get("rejected") ?? 0,
        unit: "count",
      },
      {
        name: "moderation.reports_open",
        kind: "gauge",
        value: Number(reports?.n ?? 0),
        unit: "count",
      },
    ],
  };
}

/**
 * An open report IS an incident for this app.
 *
 * Somebody has said a published face is harmful and nobody has answered yet.
 * Mapping them here means they reach the console's alerting rather than
 * sitting in a table only a moderator thinks to open.
 */
async function incidents(env: Env): Promise<unknown> {
  const { results } = await env.DB.prepare(
    `SELECT r.id, r.face_slug, r.reason, r.created
       FROM reports r WHERE r.state = 'open'
      ORDER BY r.created ASC LIMIT 100`,
  ).all<{ id: string; face_slug: string; reason: string; created: number }>();
  return {
    incidents: (results ?? []).map((r) => ({
      id: `report:${r.id}`,
      title: `open report on ${r.face_slug}: ${r.reason}`.slice(0, 300),
      severity: "warning",
      status: "open",
      firstSeenAt: Number(r.created),
      lastSeenAt: Number(r.created),
      occurrences: 1,
    })),
  };
}

/**
 * The pending queue as a generic review surface.
 *
 * The preview is produced by the repository's JVM moderation pass from the
 * exact stored parameters. The Worker never redraws it and a submitter never
 * supplies it. A matching params hash and generator version keep a stale
 * preview from authorising publication.
 *
 * The per-author counts carry most of the signal an account buys a moderator —
 * a first submission and an eleventh from someone with nine rejections are
 * different decisions — but deciding on a design still needs eyes on the
 * design. Publishing from here alone would be approving a picture nobody saw.
 */
async function queueView(env: Env): Promise<unknown> {
  const policy = await getPolicy(env);
  const { results } = await env.DB.prepare(
    `SELECT f.id, f.slug, f.name, f.author, f.state, f.created,
            (SELECT COUNT(*) FROM faces p WHERE p.author_key = f.author_key AND p.state = 'published') AS author_published,
            (SELECT COUNT(*) FROM faces x WHERE x.author_key = f.author_key AND x.state IN ('rejected','removed')) AS author_rejected,
            CASE
              WHEN r.params_hash = f.params_hash AND r.generator_version = f.generator_version THEN r.verdict
              ELSE 'pending'
            END AS validation,
            CASE
              WHEN r.params_hash = f.params_hash AND r.generator_version = f.generator_version AND r.verdict = 'passed'
                THEN r.preview_base64
              ELSE NULL
            END AS preview_base64,
            CASE
              WHEN a.params_hash = f.params_hash AND a.generator_version = f.generator_version
                THEN a.recommendation
              ELSE 'not reviewed'
            END AS ai_recommendation,
            CASE
              WHEN a.params_hash = f.params_hash AND a.generator_version = f.generator_version
                THEN a.confidence
              ELSE NULL
            END AS ai_confidence,
            CASE
              WHEN a.params_hash = f.params_hash AND a.generator_version = f.generator_version
                THEN a.rationale
              ELSE NULL
            END AS ai_rationale
       FROM faces f
       LEFT JOIN face_reviews r ON r.face_id = f.id
       LEFT JOIN face_ai_reviews a ON a.face_id = f.id
      WHERE f.state = 'pending'
      ORDER BY f.created ASC LIMIT 100`,
  ).all();
  const rows = ((results ?? []) as Record<string, unknown>[]).map((row) => {
    const preview =
      typeof row["preview_base64"] === "string" ? row["preview_base64"] : null;
    const rest = { ...row };
    delete rest["preview_base64"];
    return {
      ...rest,
      preview: preview ? `data:image/png;base64,${preview}` : null,
    };
  });
  return {
    capability: CAPABILITY,
    collectedAt: Date.now(),
    columns: [
      { key: "preview", label: "Preview", type: "image" },
      { key: "name", label: "Name", type: "string" },
      { key: "validation", label: "Technical review", type: "status" },
      { key: "ai_recommendation", label: "AI suggestion", type: "status" },
      { key: "ai_confidence", label: "Confidence", type: "string" },
      { key: "ai_rationale", label: "Why", type: "string" },
      { key: "slug", label: "Slug", type: "string" },
      { key: "author", label: "Author", type: "string" },
      { key: "created", label: "Submitted", type: "timestamp" },
      {
        key: "author_published",
        label: "Author published",
        type: "number",
        unit: "count",
      },
      {
        key: "author_rejected",
        label: "Author rejected",
        type: "number",
        unit: "count",
      },
      { key: "state", label: "State", type: "status" },
      { key: "id", label: "ID", type: "id" },
    ],
    rows,
    note:
      "Previews come from the JVM renderer and exact stored parameters. " +
      `AI advice is ${policy.enabled ? "enabled" : "disabled"} with ${policy.sensitivity} sensitivity; ` +
      "it never publishes or rejects. Publish stays a human decision and remains disabled until technical validation and preview generation pass.",
  };
}

/**
 * REASON IS REQUIRED ON REJECT AND REMOVE, and that is not politeness.
 *
 * The service returns 422 without one, because MODERATION.md promises every
 * refusal carries a written reason and an appeal against a bare "rejected"
 * cannot be answered. Declaring it `required` here means the console refuses
 * an empty submit instead of the moderator meeting a 422 they cannot act on.
 *
 * `destructive` drives a server-enforced confirmation in the console. It is a
 * behaviour, not a label.
 */
async function actionCatalog(env: Env): Promise<unknown> {
  const policy = await getPolicy(env);
  const face = {
    key: "id",
    label: "Face",
    type: "string" as const,
    required: true,
    fromColumn: "id",
  };
  const reason = {
    key: "reason",
    label: "Reason",
    type: "string" as const,
    required: true,
    help: "Recorded and answerable on appeal. A bare refusal cannot be appealed.",
  };
  return {
    capability: CAPABILITY,
    actions: [
      {
        id: "ai-recommend",
        label: "Ask AI",
        description:
          "Check the trusted preview for abuse, spam and library saturation. Advice only; it never decides.",
        destructive: false,
        availableWhen: {
          column: "validation",
          equals: "passed",
          reason:
            "A matching passed JVM review and trusted preview are required.",
        },
        params: [face],
      },
      {
        id: "publish",
        label: "Publish",
        description: "Make this face visible in the catalog.",
        destructive: false,
        availableWhen: {
          column: "validation",
          equals: "passed",
          reason:
            "A matching passed JVM review and trusted preview are required.",
        },
        params: [face],
      },
      {
        id: "reject",
        label: "Reject",
        description: "Refuse this submission. The author is told why.",
        destructive: true,
        params: [face, reason],
      },
      {
        id: "remove",
        label: "Remove",
        description: "Take a published face down.",
        destructive: true,
        params: [face, reason],
      },
      {
        id: "configure-ai",
        settingsGroup: "ai",
        settingsSummary: `Provider: Anthropic. Model: ${env.ANTHROPIC_MODEL ?? "claude-haiku-4-5-20251001"}. ${env.ANTHROPIC_API_KEY ? "Provider credential configured." : "Provider credential missing; reviews cannot run."} Review mode: recommendations only. Uses the trusted preview, public name and author, author-history counts and up to ${policy.comparison_limit} published previews.`,
        label: "Configure AI review",
        description:
          "Set the recommendation policy. This cannot enable automatic publishing.",
        destructive: false,
        params: [
          {
            key: "enabled",
            currentValue: policy.enabled ? "enabled" : "disabled",
            label: "AI review",
            type: "enum",
            required: true,
            options: [
              { value: "enabled", label: "Enabled" },
              { value: "disabled", label: "Disabled" },
            ],
          },
          {
            key: "sensitivity",
            currentValue: policy.sensitivity,
            label: "Sensitivity",
            type: "enum",
            required: true,
            options: [
              { value: "permissive", label: "Permissive" },
              { value: "balanced", label: "Balanced" },
              { value: "cautious", label: "Cautious" },
            ],
          },
          {
            key: "comparisonLimit",
            currentValue: policy.comparison_limit,
            label: "Recent published faces to compare",
            type: "number",
            required: true,
            help: "1 to 12 trusted previews.",
          },
          {
            key: "pendingWarningAt",
            currentValue: policy.pending_warn,
            label: "Warn when one author has this many pending",
            type: "number",
            required: true,
            help: "1 to 50 submissions.",
          },
        ],
      },
    ],
  };
}

/**
 * Executes through the same state transition `/admin/faces/:id/:action` uses.
 *
 * RETURNS A SUMMARY, NOT THE RECORD. An action's return value is persisted in
 * the console's audit trail and rendered back to whoever ran it, so echoing the
 * whole submission would put author fields somewhere nobody ruled on. A sibling
 * app shipped that bug one verb away on this same boundary.
 */
async function execute(
  request: Request,
  env: Env,
  action: string,
): Promise<Response> {
  let body: Record<string, unknown> = {};
  try {
    body = JSON.parse(await request.text()) as Record<string, unknown>;
  } catch {
    /* an empty body is a missing id, handled below */
  }
  const id = typeof body["id"] === "string" ? body["id"] : "";
  const reason =
    typeof body["reason"] === "string" ? body["reason"].trim() : "";
  if (action === "configure-ai") {
    const configured = await configureAiPolicy(env, body);
    return json(configured.body, configured.status);
  }
  if (!/^[0-9a-f-]{36}$/.test(id)) {
    return json({ error: "a face id is required" }, 422);
  }
  if (action === "ai-recommend") {
    const recommendation = await recommendFace(env, id, true);
    return json(recommendation.body, recommendation.status);
  }
  const state =
    action === "publish"
      ? "published"
      : action === "reject"
        ? "rejected"
        : "removed";
  if (state !== "published" && !reason) {
    return json({ error: "a reason is required", action }, 422);
  }
  if (state === "published") {
    const published = await publishReviewed(env, id);
    return json(published.body, published.status);
  }
  const result = await env.DB.prepare(
    `UPDATE faces SET state = ?, reason = ? WHERE id = ?`,
  )
    .bind(state, reason, id)
    .run();
  if (!result.meta.changes) {
    return json({ error: "no such face", id }, 404);
  }
  return json({ ok: true, id, state });
}
