import { ops } from "./ops";
import { CONTRACT } from "./contract";
import type { Env } from "./env";
import { paramsHash } from "./hash";
import { publishReviewed, recordReview } from "./review";
import { checkRate, LIMITS, sweepRate } from "./ratelimit";
import { identify } from "./auth";
import { flatten, tooLarge, validateFace } from "./validate";

/**
 * The community catalog service.
 *
 * Anyone can share a face and anyone can report one, neither needing an account
 * anywhere — which is the whole reason this exists rather than a GitHub
 * repository, since GitHub has no anonymous write path of any kind.
 *
 * `docs/specs/catalog-service.md` is the contract. The parts most easily got
 * wrong, restated here because this file is where they are enforced:
 *
 * - **Nothing is public until it is approved.** Anonymous submission removes
 *   the only handle moderation normally has. Pre-moderation is what makes it
 *   safe; rate limiting only slows a flood that still lands.
 * - **A report is a message, not an action.** Nothing here hides a face. With
 *   no accounts, "N people reported it" is one person and a loop.
 * - **The install counter carries nothing about the person.** No install id, no
 *   device details. One number per face. It is inflatable, which is acceptable
 *   for ordering a gallery and would not be for anything else.
 * - **Everything can be exported as files.** `/export` emits the same
 *   `faces/<slug>.json` and `index.json` the git catalog used, so if this
 *   service dies the catalog survives.
 */

/**
 * How long the edge may serve a published read without asking again.
 *
 * The published catalog changes only when a maintainer approves something, so
 * this is the difference between a gallery that costs nothing to browse and one
 * that queries a database per view. Moderation actions purge the cache
 * explicitly, so the window is not how long a removal takes to take effect.
 */
const CACHE_SECONDS = 300;

interface FaceRow {
  id: string;
  slug: string;
  name: string;
  author: string;
  params: string;
  engine: string;
  dial_color: string;
  ink_color: string;
  generator_version: number;
  installs: number;
  created: string;
  state: string;
  reason: string | null;
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    try {
      return await route(request, env, ctx);
    } catch (error) {
      // Never leak an internal message to a public endpoint. The Worker's own
      // logs carry the detail; the caller gets a number.
      console.error("unhandled", error);
      return json({ error: "the service failed to handle that" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;

async function route(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const method = request.method.toUpperCase();

  if (method === "OPTIONS") return preflight();

  // ---- reads --------------------------------------------------------------

  if (method === "GET" && path === "/index.json") return getIndex(env, ctx, request);
  if (method === "GET" && path === "/export") return getExport(env);
  if (method === "GET" && path === "/config") return getConfig(env);

  const faceRead = /^\/faces\/([a-z][a-z0-9_]*)$/.exec(path);
  if (method === "GET" && faceRead?.[1]) return getFace(env, ctx, request, faceRead[1]);

  const submissionRead = /^\/submissions\/([0-9a-f-]{36})$/.exec(path);
  if (method === "GET" && submissionRead?.[1]) return getSubmission(env, submissionRead[1]);

  // ---- writes -------------------------------------------------------------

  if (method === "GET" && path === "/mine") return getMine(request, env);
  if (method === "DELETE" && path === "/me") return deleteMe(request, env);
  if (method === "POST" && path === "/faces") return postFace(request, env, ctx);
  if (method === "POST" && path === "/reports") return postReport(request, env, ctx);

  const installed = /^\/faces\/([a-z][a-z0-9_]*)\/installed$/.exec(path);
  if (method === "POST" && installed?.[1]) return postInstalled(env, request, installed[1]);

  const withdraw = /^\/submissions\/([0-9a-f-]{36})\/withdraw$/.exec(path);
  if (method === "POST" && withdraw?.[1]) return postWithdraw(request, env, withdraw[1]);

  // ---- the ops contract ---------------------------------------------------
  //
  // Before /admin, because it is a different caller with different credentials:
  // the console presents an ops token, never MODERATOR_TOKEN directly.
  if (path.startsWith("/api/ops/")) {
    const handled = await ops(request, env, path, method);
    if (handled) return handled;
  }

  // ---- moderation ---------------------------------------------------------

  if (path.startsWith("/admin/")) return admin(request, env, ctx, path, method);

  return json({ error: "not found" }, 404);
}

// ---------------------------------------------------------------------------
// Reads
// ---------------------------------------------------------------------------

/**
 * The gallery's one request.
 *
 * Carries enough to render a browsable list and no more; full parameters live
 * behind `/faces/<slug>` and are fetched only when someone opens one. A
 * thousand faces is one response, not a thousand.
 *
 * Ordered by installs, which is the cost the popularity ordering was accepted
 * with: the app has to report installs for this column to mean anything.
 */
async function getIndex(env: Env, ctx: ExecutionContext, request: Request): Promise<Response> {
  const cached = await caches.default.match(request);
  if (cached) return cached;

  const { results } = await env.DB.prepare(
    `SELECT slug, name, author, engine, dial_color, ink_color, generator_version, created, installs
       FROM faces WHERE state = 'published'
      ORDER BY installs DESC, created DESC`,
  ).all<FaceRow>();

  const faces = results.map((r) => ({
    slug: r.slug,
    name: r.name,
    author: r.author,
    engine: r.engine,
    dialColor: r.dial_color,
    inkColor: r.ink_color,
    generatorVersion: r.generator_version,
    created: r.created,
    installs: r.installs,
  }));

  const body = {
    generated: new Date().toISOString(),
    // The HIGHEST version among the faces, not the version of whatever built
    // the index -- what a client needs to know is whether it is new enough to
    // render everything in here.
    maxGeneratorVersion: faces.reduce((m, f) => Math.max(m, f.generatorVersion), 0),
    count: faces.length,
    faces,
  };

  const response = json(body, 200, { "cache-control": `public, max-age=${CACHE_SECONDS}` });
  ctx.waitUntil(caches.default.put(request, response.clone()));
  return response;
}

/** One published face, in the catalog's own on-disk shape. */
async function getFace(
  env: Env,
  ctx: ExecutionContext,
  request: Request,
  slug: string,
): Promise<Response> {
  const cached = await caches.default.match(request);
  if (cached) return cached;

  const row = await env.DB.prepare(
    `SELECT slug, name, author, params, created FROM faces WHERE slug = ? AND state = 'published'`,
  )
    .bind(slug)
    .first<FaceRow>();

  // A face that was removed and one that never existed are the same 404 on
  // purpose. "This face was taken down" on a public endpoint is a fact about a
  // moderation action, and nobody outside needs it.
  if (!row) return json({ error: "no such face" }, 404);

  const response = json(faceDocument(row), 200, {
    "cache-control": `public, max-age=${CACHE_SECONDS}`,
  });
  ctx.waitUntil(caches.default.put(request, response.clone()));
  return response;
}

/**
 * R6: everything, as files.
 *
 * This is the mitigation for what moving off git gave up — nobody can clone the
 * catalog any more. The keys are the paths the git catalog used, so writing
 * this response out as files reproduces that repository exactly, and the
 * on-disk format stays the interchange format rather than becoming legacy.
 *
 * Deliberately uncached: it is a maintainer's tool run rarely, and a stale copy
 * of the thing whose whole job is to be a faithful copy would be worse than a
 * slow one.
 */
async function getExport(env: Env): Promise<Response> {
  const { results } = await env.DB.prepare(
    `SELECT slug, name, author, params, engine, dial_color, ink_color, generator_version, created, installs
       FROM faces WHERE state = 'published' ORDER BY slug`,
  ).all<FaceRow>();

  const files: Record<string, unknown> = {};
  for (const row of results) files[`faces/${row.slug}.json`] = faceDocument(row);

  files["index.json"] = {
    generated: new Date().toISOString(),
    maxGeneratorVersion: results.reduce((m, r) => Math.max(m, r.generator_version), 0),
    count: results.length,
    faces: results.map((r) => ({
      slug: r.slug,
      name: r.name,
      author: r.author,
      engine: r.engine,
      dialColor: r.dial_color,
      inkColor: r.ink_color,
      generatorVersion: r.generator_version,
      created: r.created,
    })),
  };

  return json({ count: results.length, files });
}

/** What a client needs to sign somebody in. Public values only. */
function getConfig(env: Env): Response {
  return json({
    // Public by necessity: the app needs the same client id to ask Google for a
    // token in the first place. Empty means publishing is switched off.
    googleClientId: env.GOOGLE_CLIENT_ID ?? "",
    contractVersion: CONTRACT.contractVersion,
    currentGeneratorVersion: CONTRACT.currentGeneratorVersion,
    maxFaceBytes: CONTRACT.maxFaceBytes,
  });
}

/**
 * What happened to one submission.
 *
 * Keyed by the opaque id returned at submit, which the app stores, so asking
 * costs nothing and sends nothing: the install id is NOT used here. Browsing
 * and checking stay anonymous; the id only travels on submit and report.
 */
async function getSubmission(env: Env, id: string): Promise<Response> {
  const row = await env.DB.prepare(
    `SELECT id, slug, name, state, reason, created, reviewed FROM faces WHERE id = ?`,
  )
    .bind(id)
    .first<{
      id: string;
      slug: string;
      name: string;
      state: string;
      reason: string | null;
      created: string;
      reviewed: string | null;
    }>();

  if (!row) return json({ error: "no such submission" }, 404);
  return json({
    id: row.id,
    slug: row.slug,
    name: row.name,
    state: row.state,
    reason: row.reason,
    created: row.created,
    reviewed: row.reviewed,
  });
}

/**
 * Whether these exact parameters are already here under some name.
 *
 * Excludes withdrawn rows, matching the partial unique index: taking your own
 * face back has to leave you able to submit it again.
 */
async function duplicateExists(env: Env, hash: string): Promise<boolean> {
  const row = await env.DB.prepare(
    `SELECT 1 AS hit FROM faces WHERE params_hash = ? AND state <> 'withdrawn' LIMIT 1`,
  )
    .bind(hash)
    .first<{ hit: number }>();
  return row !== null;
}

function faceDocument(row: FaceRow): unknown {
  return {
    name: row.name,
    slug: row.slug,
    author: row.author,
    created: row.created,
    // Verbatim, as submitted. Re-serializing here would make this service a
    // second opinion about the file format.
    params: JSON.parse(row.params) as unknown,
  };
}

// ---------------------------------------------------------------------------
// Writes
// ---------------------------------------------------------------------------

async function postFace(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const ip = clientIp(request);
  const rate = await checkRate(env, LIMITS.submit, ip);
  if (!rate.allowed) return tooMany(rate.retryAfterSeconds);

  const text = await request.text();
  if (tooLarge(text)) {
    const size = new TextEncoder().encode(text).length;
    return json(
      { error: `a face must be under ${CONTRACT.maxFaceBytes} bytes; that one is ${size}` },
      413,
    );
  }

  let body: unknown;
  try {
    body = JSON.parse(text) as unknown;
  } catch {
    return json({ error: "that is not JSON" }, 400);
  }
  if (typeof body !== "object" || body === null) return json({ error: "that is not JSON" }, 400);
  const envelope = body as Record<string, unknown>;

  const who = await identify(env, request.headers.get("authorization"));
  if (!who.ok) return json({ error: who.reason }, 401);
  if (await isBlocked(env, who.identity.authorKey)) {
    // Deliberately the same words a stranger would get for any refusal. Telling
    // somebody they are blocked tells them to make another account.
    return json({ error: "that face cannot be published" }, 403);
  }

  const problems = validateFace(envelope);
  if (problems.length > 0) {
    return json({ error: "that face cannot be published", problems }, 422);
  }

  // Validated above, so these are the shapes validateFace guaranteed.
  const name = (envelope["name"] as string).trim();
  const author = typeof envelope["author"] === "string" ? envelope["author"].trim() : "";
  const baseSlug = envelope["slug"] as string;
  const rawParams = envelope["params"] as Record<string, unknown>;
  const flat = flatten(rawParams);
  const authorKey = who.identity.authorKey;

  const hash = await paramsHash(flat);
  const created = new Date().toISOString();

  // The published slug carries a short id because the slug IS the Watch Face
  // Push package suffix: two strangers both calling a face "Midnight" would
  // produce one package, and installing the second would silently replace the
  // first on the watch.
  const stem = baseSlug.slice(0, CONTRACT.maxSlugChars - CONTRACT.publishedIdChars - 1);

  // Ask before inserting. The ordinary duplicate is a plain lookup, and it
  // gives a clean answer without depending on what a constraint violation
  // happens to say.
  if (await duplicateExists(env, hash)) {
    return json({ error: "that face has already been submitted" }, 409);
  }

  for (let attempt = 0; attempt < 5; attempt++) {
    const slug = `${stem}_${shortId(CONTRACT.publishedIdChars)}`;
    const id = crypto.randomUUID();
    try {
      await env.DB.prepare(
        `INSERT INTO faces (id, slug, name, author, params, params_hash, engine,
                            dial_color, ink_color, generator_version, author_key, state, created)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)`,
      )
        .bind(
          id,
          slug,
          name,
          author,
          JSON.stringify(rawParams),
          hash,
          String(flat["engine"]),
          String(flat["dialColor"]),
          String(flat["inkColor"]),
          Number(flat["generatorVersion"]),
          authorKey,
          created,
        )
        .run();

      ctx.waitUntil(sweepRate(env));
      return json({ id, slug, state: "pending" }, 201);
    } catch {
      // Something unique was violated. WHICH one is decided by asking the
      // database, not by matching text in the error: a driver's message is not
      // an interface, it differs between SQLite builds, and getting it wrong
      // turns a duplicate into five pointless retries and a 503 -- which is
      // exactly what it did.
      if (await duplicateExists(env, hash)) {
        return json({ error: "that face has already been submitted" }, 409);
      }
      // Otherwise the four random characters collided. A different four will do.
    }
  }
  return json({ error: "could not allocate a name for that face; try again" }, 503);
}

/**
 * A report queues for a human and does nothing else.
 *
 * Auto-hiding on a count was rejected: with no accounts, a count is one person
 * and a loop, and it would hand anyone a takedown button. The cost is named in
 * MODERATION.md — a harmful face stays up until a person sees it, which is what
 * the 72-hour promise means.
 */
const REPORT_REASONS = [
  "intellectual_property",
  "impersonation",
  "hate_or_harassment",
  "sexual_content",
  "spam",
  "other",
] as const;

async function postReport(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const ip = clientIp(request);
  const rate = await checkRate(env, LIMITS.report, ip);
  if (!rate.allowed) return tooMany(rate.retryAfterSeconds);

  const text = await request.text();
  if (text.length > 4096) return json({ error: "that report is too long" }, 413);

  let body: Record<string, unknown>;
  try {
    const parsed = JSON.parse(text) as unknown;
    if (typeof parsed !== "object" || parsed === null) throw new Error("not an object");
    body = parsed as Record<string, unknown>;
  } catch {
    return json({ error: "that is not JSON" }, 400);
  }

  // NO SIGN-IN HERE, and that is the design rather than an omission. R2 exists
  // because requiring an account to report became intolerable the moment
  // submitting did not -- "anyone could publish and only developers could
  // complain" is what moved this catalog off GitHub. Publishing is a privilege;
  // complaining is not.
  //
  // Safe to leave open because a report is a MESSAGE, not an action: nothing
  // auto-hides, so flooding buys a longer queue for a human and nothing else.
  const slug = body["slug"];
  if (typeof slug !== "string" || !/^[a-z][a-z0-9_]*$/.test(slug)) {
    return json({ error: "which face?" }, 422);
  }
  const reason = body["reason"];
  if (typeof reason !== "string" || !REPORT_REASONS.includes(reason as (typeof REPORT_REASONS)[number])) {
    return json({ error: `reason must be one of ${REPORT_REASONS.join(", ")}` }, 422);
  }
  const detail = typeof body["detail"] === "string" ? body["detail"].slice(0, 2000) : "";

  // Reports are accepted for a face that is not published, and for one that
  // does not exist. A reporter should never have to know which -- and telling
  // them would turn this endpoint into a way to enumerate what has been taken
  // down.
  const id = crypto.randomUUID();
  await env.DB.prepare(
    `INSERT INTO reports (id, face_slug, reason, detail, state, created)
     VALUES (?, ?, ?, ?, 'open', ?)`,
  )
    .bind(id, slug, reason, detail, new Date().toISOString())
    .run();

  ctx.waitUntil(sweepRate(env));
  return json({ id, state: "open" }, 201);
}

/**
 * One install, counted.
 *
 * Empty body, deliberately. No install id, no device details, nothing
 * correlatable — one number per face. A per-person history is exactly what this
 * must not become, which is why the install id is not attached even though it
 * exists elsewhere in this file.
 */
async function postInstalled(env: Env, request: Request, slug: string): Promise<Response> {
  const rate = await checkRate(env, LIMITS.installed, clientIp(request));
  if (!rate.allowed) return tooMany(rate.retryAfterSeconds);

  const result = await env.DB.prepare(
    `UPDATE faces SET installs = installs + 1 WHERE slug = ? AND state = 'published'`,
  )
    .bind(slug)
    .run();

  // 204 either way. Whether a face is still published is not something this
  // endpoint should disclose, and the app has nothing to do with the answer.
  void result;
  return new Response(null, { status: 204, headers: cors() });
}

/**
 * An author taking their own face back.
 *
 * Proven by the random per-install id, which is deliberately weak: reinstalling
 * makes a new one and the old face can then only be withdrawn by reporting it.
 * That is stated at submit rather than discovered.
 */
async function postWithdraw(request: Request, env: Env, id: string): Promise<Response> {
  const who = await identify(env, request.headers.get("authorization"));
  if (!who.ok) return json({ error: who.reason }, 401);

  const result = await env.DB.prepare(
    `UPDATE faces SET state = 'withdrawn', reviewed = ?
      WHERE id = ? AND author_key = ? AND state IN ('pending','published')`,
  )
    .bind(new Date().toISOString(), id, who.identity.authorKey)
    .run();

  // Withdrawing somebody else's face and withdrawing one that does not exist
  // are the same answer, so this cannot be used to find out who wrote what.
  if (result.meta.changes === 0) {
    return json({ error: "that is not one of your submissions" }, 403);
  }
  return json({ id, state: "withdrawn" });
}

/**
 * An author's own submissions, for "my faces".
 *
 * Needs the account, so it is the one READ that is not anonymous — and it is a
 * read of your own things, which is the only reason it can exist at all.
 */
async function getMine(request: Request, env: Env): Promise<Response> {
  const who = await identify(env, request.headers.get("authorization"));
  if (!who.ok) return json({ error: who.reason }, 401);

  const { results } = await env.DB.prepare(
    `SELECT id, slug, name, state, reason, created, reviewed
       FROM faces WHERE author_key = ? ORDER BY created DESC LIMIT 200`,
  )
    .bind(who.identity.authorKey)
    .all();
  return json({ count: results.length, faces: results });
}

/**
 * Delete the account's data.
 *
 * Play requires this once an app has accounts. What it does is settled and is
 * narrower than it sounds: it drops the LINK and leaves the faces.
 *
 * The operator's reasoning, and it ends the question rather than balancing it:
 * a watch face is parameters -- "knotwork, scale 26, pewter" -- and settings are
 * not personal information. The account id was the personal data here, and it
 * is what goes. Faces other people are already using stay where they are;
 * nothing should pull one off a wrist because its author left.
 *
 * A PENDING face is withdrawn rather than abandoned, because nobody has it yet
 * and leaving it would ask a moderator to review something with no one to
 * answer for it.
 */
async function deleteMe(request: Request, env: Env): Promise<Response> {
  const who = await identify(env, request.headers.get("authorization"));
  if (!who.ok) return json({ error: who.reason }, 401);
  const key = who.identity.authorKey;

  const withdrawn = await env.DB.prepare(
    `UPDATE faces SET state = 'withdrawn', author_key = NULL, reviewed = ?
      WHERE author_key = ? AND state = 'pending'`,
  )
    .bind(new Date().toISOString(), key)
    .run();

  const abandoned = await env.DB.prepare(
    `UPDATE faces SET author_key = NULL WHERE author_key = ?`,
  )
    .bind(key)
    .run();

  return json({
    deleted: true,
    pendingWithdrawn: withdrawn.meta.changes,
    facesKept: abandoned.meta.changes,
  });
}

/** Whether this author has been blocked. */
async function isBlocked(env: Env, authorKey: string): Promise<boolean> {
  const row = await env.DB.prepare(`SELECT 1 AS hit FROM blocked_authors WHERE author_key = ?`)
    .bind(authorKey)
    .first<{ hit: number }>();
  return row !== null;
}

// ---------------------------------------------------------------------------
// Moderation
// ---------------------------------------------------------------------------

/**
 * The maintainer's side. One bearer token, and no other credential anywhere.
 *
 * These endpoints are what makes MODERATION.md's promises keepable by one
 * person: a queue that can be worked through, oldest first, rather than an
 * inbox.
 */
async function admin(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  path: string,
  method: string,
): Promise<Response> {
  if (!env.MODERATOR_TOKEN) {
    // No default, no fallback. A default moderator token is a published one.
    return json({ error: "moderation is not configured on this service" }, 503);
  }
  const offered = request.headers.get("authorization") ?? "";
  if (!timingSafeEqual(offered, `Bearer ${env.MODERATOR_TOKEN}`)) {
    return json({ error: "not authorised" }, 401);
  }

  if (method === "GET" && path === "/admin/queue") {
    const url = new URL(request.url);
    const state = url.searchParams.get("state") ?? "pending";
    // The per-author counts are the main thing an account buys a moderator:
    // whether this is somebody's first face or their eleventh, and how the
    // previous ten went. No amount of looking at one face tells you that.
    const { results } = await env.DB.prepare(
      `SELECT f.id, f.slug, f.name, f.author, f.params, f.params_hash,
              f.generator_version, f.state, f.reason, f.created,
              f.author_key,
              (SELECT COUNT(*) FROM faces p WHERE p.author_key = f.author_key AND p.state = 'published') AS author_published,
              (SELECT COUNT(*) FROM faces r WHERE r.author_key = f.author_key AND r.state IN ('rejected','removed')) AS author_rejected
         FROM faces f WHERE f.state = ? ORDER BY f.created ASC LIMIT 100`,
    )
      .bind(state)
      .all();
    return json({ state, count: results.length, faces: results });
  }

  const review = /^\/admin\/faces\/([0-9a-f-]{36})\/review$/.exec(path);
  if (method === "POST" && review?.[1]) {
    const recorded = await recordReview(request, env, review[1]);
    return json(recorded.body, recorded.status);
  }

  if (method === "GET" && path === "/admin/reports") {
    const { results } = await env.DB.prepare(
      `SELECT r.id, r.face_slug, r.reason, r.detail, r.created, f.name, f.state AS face_state
         FROM reports r LEFT JOIN faces f ON f.slug = r.face_slug
        WHERE r.state = 'open' ORDER BY r.created ASC LIMIT 100`,
    ).all();
    return json({ count: results.length, reports: results });
  }

  const decide = /^\/admin\/faces\/([0-9a-f-]{36})\/(publish|reject|remove)$/.exec(path);
  if (method === "POST" && decide?.[1] && decide[2]) {
    const [, id, action] = decide;
    const state = action === "publish" ? "published" : action === "reject" ? "rejected" : "removed";
    let reason: string | null = null;
    try {
      const body = JSON.parse(await request.text()) as Record<string, unknown>;
      if (typeof body["reason"] === "string") reason = body["reason"];
    } catch {
      // A decision with no body is fine; publish needs no reason.
    }
    if (state !== "published" && !reason) {
      // MODERATION.md promises appeals are answered, and an appeal against a
      // decision with no recorded reason cannot be.
      return json({ error: "a rejection or removal needs a reason" }, 422);
    }

    const row = await env.DB.prepare(`SELECT slug FROM faces WHERE id = ?`)
      .bind(id)
      .first<{ slug: string }>();
    if (!row) return json({ error: "no such submission" }, 404);

    if (state === "published") {
      const published = await publishReviewed(env, id);
      if (published.status !== 200) return json(published.body, published.status);
      ctx.waitUntil(purge(request, row.slug));
      return json({ id, slug: row.slug, state });
    }

    await env.DB.prepare(`UPDATE faces SET state = ?, reason = ?, reviewed = ? WHERE id = ?`)
      .bind(state, reason, new Date().toISOString(), id)
      .run();

    // Purge, so a removal is not served from the edge for another five minutes.
    ctx.waitUntil(purge(request, row.slug));
    return json({ id, slug: row.slug, state });
  }

  // Blocking an author. The capability the anonymous design could not have.
  const block = /^\/admin\/authors\/([0-9a-f]{64})\/block$/.exec(path);
  if (method === "POST" && block?.[1]) {
    let reason = "";
    try {
      const body = JSON.parse(await request.text()) as Record<string, unknown>;
      if (typeof body["reason"] === "string") reason = body["reason"];
    } catch {
      // handled below
    }
    if (!reason) return json({ error: "blocking an author needs a reason" }, 422);
    await env.DB.prepare(
      `INSERT INTO blocked_authors (author_key, reason, created) VALUES (?, ?, ?)
       ON CONFLICT(author_key) DO UPDATE SET reason = excluded.reason`,
    )
      .bind(block[1], reason, new Date().toISOString())
      .run();
    return json({ authorKey: block[1], blocked: true });
  }

  const resolve = /^\/admin\/reports\/([0-9a-f-]{36})\/resolve$/.exec(path);
  if (method === "POST" && resolve?.[1]) {
    let outcome = "";
    try {
      const body = JSON.parse(await request.text()) as Record<string, unknown>;
      if (typeof body["outcome"] === "string") outcome = body["outcome"];
    } catch {
      // handled below
    }
    if (!outcome) return json({ error: "a resolution needs an outcome" }, 422);
    const result = await env.DB.prepare(
      `UPDATE reports SET state = 'resolved', outcome = ?, resolved = ? WHERE id = ? AND state = 'open'`,
    )
      .bind(outcome, new Date().toISOString(), resolve[1])
      .run();
    if (result.meta.changes === 0) return json({ error: "no such open report" }, 404);
    return json({ id: resolve[1], state: "resolved" });
  }

  return json({ error: "not found" }, 404);
}

async function purge(request: Request, slug: string): Promise<void> {
  const origin = new URL(request.url).origin;
  await caches.default.delete(`${origin}/index.json`);
  await caches.default.delete(`${origin}/faces/${slug}`);
}

// ---------------------------------------------------------------------------
// Plumbing
// ---------------------------------------------------------------------------

function json(body: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...cors(), ...extra },
  });
}

function tooMany(retryAfterSeconds: number): Response {
  return json({ error: "too many requests from here; try again later" }, 429, {
    "retry-after": String(retryAfterSeconds),
  });
}

/**
 * Open to any origin, and that is correct here rather than lax.
 *
 * Everything readable is already public, and every write is authorised by a
 * signed token or a bearer token rather than by a cookie — so there is no
 * ambient authority for another origin to borrow, which is the thing the
 * same-origin policy protects.
 */
function cors(): Record<string, string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET, POST, DELETE, OPTIONS",
    "access-control-allow-headers": "content-type, authorization",
  };
}

function preflight(): Response {
  return new Response(null, { status: 204, headers: cors() });
}

function clientIp(request: Request): string | null {
  return request.headers.get("cf-connecting-ip");
}

/** Lowercase hex, from the platform's own randomness. */
function shortId(chars: number): string {
  const bytes = new Uint8Array(Math.ceil(chars / 2));
  crypto.getRandomValues(bytes);
  return [...bytes]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("")
    .slice(0, chars);
}

/**
 * Constant-time string comparison for the moderator token.
 *
 * `===` on a secret leaks its length and its prefix through timing. That is a
 * thin attack over the internet and a free defence here.
 */
function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
