import { env, SELF } from "cloudflare:test";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { get, migrate, MODERATOR, passReview, post, reset, signedIn, submission } from "./helpers";

beforeAll(migrate);
beforeEach(reset);

interface Submitted {
  id: string;
  slug: string;
  state: string;
}

async function submit(overrides: Record<string, unknown> = {}, sub = "test-author-1"): Promise<Submitted> {
  const response = await SELF.fetch(post("/faces", submission(overrides), await signedIn(sub)));
  expect(response.status).toBe(201);
  return (await response.json()) as Submitted;
}

async function publish(id: string): Promise<Response> {
  await passReview(id);
  return SELF.fetch(post(`/admin/faces/${id}/publish`, {}, MODERATOR));
}

describe("approved library", () => {
  it("allows a fresh submission after removal, retains history and still excludes racing active duplicates", async () => {
    const face = await submit();
    await publish(face.id);
    await SELF.fetch(post("/api/ops/library/actions/remove", { id: face.id, reason: "Start over" }, MODERATOR));
    const auth = await signedIn();
    const responses = await Promise.all([SELF.fetch(post('/faces', submission(), auth)), SELF.fetch(post('/faces', submission(), auth))]);
    expect(responses.map((r) => r.status).sort()).toEqual([201, 409]);
    expect(await env.DB.prepare("SELECT state FROM faces WHERE id=?").bind(face.id).first()).toEqual({ state: 'removed' });
    expect(await env.DB.prepare("SELECT count(*) AS count FROM faces WHERE state='pending'").first()).toEqual({ count: 1 });
    expect(await env.DB.prepare("SELECT count(*) AS count FROM face_reviews r JOIN faces f ON f.id=r.face_id WHERE f.state='pending'").first()).toEqual({ count: 0 });
  });

  it("distinguishes a previous rejection from a live library duplicate", async () => {
    const face = await submit();
    await SELF.fetch(post(`/admin/faces/${face.id}/reject`, { reason: "Spam" }, MODERATOR));
    const again = await SELF.fetch(post('/faces', submission(), await signedIn()));
    expect(again.status).toBe(409);
    expect(await again.json()).toEqual({ error: 'this design was previously rejected; change it before submitting again' });
  });
  it("shows trusted published previews and removes without erasing review history", async () => {
    expect(await (await SELF.fetch(get("/api/ops/library", MODERATOR))).json()).toMatchObject({ rows: [], nextCursor: null });
    const face = await submit();
    expect((await SELF.fetch(post("/api/ops/library/actions/remove", { id: face.id, reason: "not published" }, MODERATOR))).status).toBe(404);
    await publish(face.id);
    const publicRead = await SELF.fetch(get(`/faces/${face.slug}`));
    expect(publicRead.status).toBe(200);
    expect(publicRead.headers.get("cache-control")).toBe("no-store");
    expect(await (await SELF.fetch(get("/api/ops/library", MODERATOR))).json()).toMatchObject({
      rows: [{ id: face.id, state: "published", preview: "data:image/png;base64,iVBORw0KGgo=" }],
    });
    expect((await SELF.fetch(post("/api/ops/library/actions/remove", { id: face.id }, MODERATOR))).status).toBe(422);
    expect((await SELF.fetch(post("/api/ops/library/actions/remove", { id: face.id, reason: "Withdrawn by moderator" }, MODERATOR))).status).toBe(200);
    expect(await (await SELF.fetch(get("/api/ops/library", MODERATOR))).json()).toMatchObject({ rows: [] });
    expect((await SELF.fetch(get(`/faces/${face.slug}`))).status).toBe(404);
    expect(await (await SELF.fetch(get("/index.json"))).json()).toMatchObject({ count: 0 });
    expect(await env.DB.prepare("SELECT state, reason FROM faces WHERE id = ?").bind(face.id).first()).toEqual({ state: "removed", reason: "Withdrawn by moderator" });
    expect(await env.DB.prepare("SELECT verdict FROM face_reviews WHERE face_id = ?").bind(face.id).first()).toMatchObject({ verdict: "passed" });
  });

  it("pages equal publication timestamps without gaps and excludes nonpublished states", async () => {
    const face = await submit();
    await publish(face.id);
    for (let i = 0; i < 28; i++) {
      await env.DB.prepare(`INSERT INTO faces (id, slug, name, author, params, params_hash, engine, dial_color, ink_color, generator_version, state, created, reviewed)
        SELECT ?, ?, name, author, params, ?, engine, dial_color, ink_color, generator_version, ?, created, reviewed FROM faces WHERE id = ?`)
        .bind(crypto.randomUUID(), `page_${i}`, i.toString(16).padStart(64, "0"), i < 26 ? "published" : i === 26 ? "removed" : "pending", face.id).run();
    }
    const first = await (await SELF.fetch(get("/api/ops/library", MODERATOR))).json() as { rows: { id: string }[]; nextCursor: string };
    expect(first.rows).toHaveLength(25);
    const second = await (await SELF.fetch(get(`/api/ops/library?cursor=${encodeURIComponent(first.nextCursor)}`, MODERATOR))).json() as { rows: { id: string }[]; nextCursor: string | null };
    expect(second.rows).toHaveLength(2);
    expect(second.nextCursor).toBeNull();
    expect(new Set([...first.rows, ...second.rows].map((row) => row.id)).size).toBe(27);
    expect((await SELF.fetch(get("/api/ops/library?cursor=bad", MODERATOR))).status).toBe(400);
  });
});

describe("submitting", () => {
  it("accepts a real face and puts it in the queue, visible to nobody", async () => {
    const { id, slug, state } = await submit();
    expect(state).toBe("pending");
    expect(id).toMatch(/^[0-9a-f-]{36}$/);

    // The published slug carries a short id because the slug IS the Watch Face
    // Push package suffix: two strangers calling a face "Midnight" would
    // otherwise produce one package, and the second install would silently
    // replace the first on the watch.
    expect(slug).toMatch(/^fixture_face_[0-9a-f]{4}$/);

    const index = (await (await SELF.fetch(get("/index.json"))).json()) as { count: number };
    expect(index.count).toBe(0);

    expect((await SELF.fetch(get(`/faces/${slug}`))).status).toBe(404);
  });

  it("gives two submissions of the same name different packages", async () => {
    const first = await submit();
    // Byte-identical params are refused, so change one number to make this a
    // genuinely different face that happens to share a name.
    const other = submission();
    (other["params"] as Record<string, unknown>)["scale"] = 27;
    const response = await SELF.fetch(post("/faces", other, await signedIn()));
    expect(response.status).toBe(201);
    const second = (await response.json()) as Submitted;

    expect(second.slug).not.toBe(first.slug);
    expect(second.slug.startsWith("fixture_face_")).toBe(true);
  });

  it("keeps a published slug inside the package-name limit for a long name", async () => {
    // The Kotlin side computes the same stem length from the same two contract
    // numbers (PublishedSlugTest pins that half). This pins this half: the two
    // are the construct and verify ends of one rule written in two languages,
    // and a disagreement means the moderation pass refuses every submission.
    const contract = (await (await SELF.fetch(get("/config"))).json()) as { maxFaceBytes: number };
    expect(contract.maxFaceBytes).toBeGreaterThan(0);

    const longName = "Midnight ".repeat(6).trim();
    const { slug } = await submit({ name: longName.slice(0, 40), slug: "midnight_midnight_midnight_midnight_midn" });
    expect(slug.length).toBeLessThanOrEqual(40);
    expect(slug).toMatch(/^[a-z][a-z0-9_]*$/);
  });

  it("refuses a byte-identical resubmission", async () => {
    await submit();
    const response = await SELF.fetch(post("/faces", submission(), await signedIn()));
    expect(response.status).toBe(409);
  });

  it("hashes past key order, so a reordered but identical face is still a duplicate", async () => {
    await submit();
    const reordered = submission();
    const params = reordered["params"] as Record<string, unknown>;
    // Same content, different insertion order.
    reordered["params"] = Object.fromEntries(Object.entries(params).reverse());
    const response = await SELF.fetch(post("/faces", reordered, await signedIn()));
    expect(response.status).toBe(409);
  });

  it("refuses an invalid face with every problem listed", async () => {
    const bad = submission();
    (bad["params"] as Record<string, unknown>)["engine"] = "TEXTURE";
    const response = await SELF.fetch(post("/faces", bad, await signedIn()));
    expect(response.status).toBe(422);
    const body = (await response.json()) as { problems: { field: string }[] };
    expect(body.problems.some((p) => p.field === "engine")).toBe(true);
  });

  it("refuses a submission from nobody", async () => {
    const response = await SELF.fetch(post("/faces", submission()));
    expect(response.status).toBe(401);
  });

  it("refuses a body larger than a face can be", async () => {
    const huge = submission({ author: "x".repeat(9000) });
    const response = await SELF.fetch(post("/faces", huge, await signedIn()));
    expect(response.status).toBe(413);
  });
});

describe("publishing", () => {
  it("refuses to publish until the current parameters have a passed JVM review and preview", async () => {
    const { id } = await submit();
    const early = await SELF.fetch(post(`/admin/faces/${id}/publish`, {}, MODERATOR));
    expect(early.status).toBe(409);

    expect((await passReview(id)).status).toBe(200);
    expect((await SELF.fetch(post(`/admin/faces/${id}/publish`, {}, MODERATOR))).status).toBe(200);
  });

  it("rejects a stale review hash", async () => {
    const { id } = await submit();
    const response = await SELF.fetch(
      post(
        `/admin/faces/${id}/review`,
        {
          paramsHash: "0".repeat(64),
          generatorVersion: 14,
          verdict: "passed",
          problems: [],
          previewBase64: "iVBORw0KGgo=",
        },
        MODERATOR,
      ),
    );
    expect(response.status).toBe(409);
  });

  it("serves the trusted preview and a row-local publish gate through Ops", async () => {
    const { id } = await submit();
    await passReview(id);

    const view = (await (await SELF.fetch(get("/api/ops/inbox", MODERATOR))).json()) as {
      columns: { key: string; type: string }[];
      rows: { id: string; validation: string; preview: string }[];
    };
    expect(view.columns).toContainEqual({ key: "preview", label: "Preview", type: "image" });
    expect(view.rows[0]).toMatchObject({ id, validation: "passed" });
    expect(view.rows[0]?.preview).toBe("data:image/png;base64,iVBORw0KGgo=");

    const actions = (await (
      await SELF.fetch(get("/api/ops/inbox/actions", MODERATOR))
    ).json()) as { actions: { id: string; availableWhen?: unknown }[] };
    expect(actions.actions.find((a) => a.id === "publish")?.availableWhen).toEqual({
      column: "validation",
      equals: "passed",
      reason: "A matching passed JVM review and trusted preview are required.",
    });
    expect(actions.actions.find((a) => a.id === "ai-recommend")?.availableWhen).toEqual({
      column: "validation",
      equals: "passed",
      reason: "A matching passed JVM review and trusted preview are required.",
    });
  });

  it("stores bounded AI advice without deciding the submission", async () => {
    const { id } = await submit();
    const beforeReview = await SELF.fetch(
      post("/api/ops/inbox/actions/ai-recommend", { id }, MODERATOR)
    );
    expect(beforeReview.status).toBe(409);

    await passReview(id);
    const response = await SELF.fetch(
      post("/api/ops/inbox/actions/ai-recommend", { id }, MODERATOR)
    );
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({
      id,
      recommendation: "approve",
      confidence: "high",
    });

    const face = await env.DB.prepare("SELECT state FROM faces WHERE id = ?")
      .bind(id)
      .first<{ state: string }>();
    expect(face?.state).toBe("pending");

    const cached = await SELF.fetch(post(`/admin/faces/${id}/ai-review`, {}, MODERATOR));
    expect(await cached.json()).toMatchObject({ deduplicated: true });
    await env.DB.prepare("UPDATE moderation_policy SET sensitivity = 'cautious' WHERE id = 1").run();
    const changedPolicy = await SELF.fetch(post(`/admin/faces/${id}/ai-review`, {}, MODERATOR));
    expect(changedPolicy.status).toBe(200);
    expect(await changedPolicy.json()).not.toHaveProperty("deduplicated");

    const stored = await env.DB.prepare("SELECT signals FROM face_ai_reviews WHERE face_id = ?")
      .bind(id).first<{ signals: string }>();
    expect(JSON.parse(stored!.signals).deterministic).not.toHaveProperty("exactDuplicatePreventedByDatabase");

    // Advice created with the misleading old safeguard flag must be recomputed.
    await env.DB.prepare("UPDATE face_ai_reviews SET recommendation = 'reject', signals = ? WHERE face_id = ?")
      .bind(JSON.stringify({ deterministic: { exactDuplicatePreventedByDatabase: true } }), id).run();
    const retried = await SELF.fetch(post(`/admin/faces/${id}/ai-review`, {}, MODERATOR));
    expect(await retried.json()).toMatchObject({ recommendation: "approve" });

    const view = (await (await SELF.fetch(get("/api/ops/inbox", MODERATOR))).json()) as {
      rows: Record<string, unknown>[];
    };
    expect(view.rows[0]).toMatchObject({
      id,
      ai_recommendation: "approve",
      ai_confidence: "high",
      ai_rationale: "No concrete abuse or saturation signal is visible.",
    });
  });

  it("invalidates advice after publication and removal, but not install counts", async () => {
    const { id } = await submit(); await passReview(id);
    await SELF.fetch(post(`/admin/faces/${id}/ai-review`, {}, MODERATOR));
    const other = crypto.randomUUID();
    await env.DB.prepare(`INSERT INTO faces(id,slug,name,author,params,params_hash,engine,dial_color,ink_color,generator_version,state,created)
      SELECT ?,?,name,author,params,?,engine,dial_color,ink_color,generator_version,'pending',created FROM faces WHERE id=?`)
      .bind(other, 'comparison', 'f'.repeat(64), id).run();
    expect((await publish(other)).status).toBe(200);
    const afterPublish = await SELF.fetch(post(`/admin/faces/${id}/ai-review`, {}, MODERATOR));
    expect(await afterPublish.json()).not.toHaveProperty('deduplicated');
    await env.DB.prepare("UPDATE faces SET installs=installs+1 WHERE id=?").bind(other).run();
    expect(await (await SELF.fetch(post(`/admin/faces/${id}/ai-review`, {}, MODERATOR))).json()).toHaveProperty('deduplicated', true);
    await SELF.fetch(post('/api/ops/library/actions/remove', { id: other, reason: 'Test removal' }, MODERATOR));
    const stale = await (await SELF.fetch(get('/api/ops/inbox', MODERATOR))).json() as { rows: { ai_recommendation: string }[] };
    expect(stale.rows[0]?.ai_recommendation).toBe('not reviewed');
    expect(await (await SELF.fetch(post(`/admin/faces/${id}/ai-review`, {}, MODERATOR))).json()).not.toHaveProperty('deduplicated');
  });

  it("validates and persists the operator's AI recommendation policy", async () => {
    const invalid = await SELF.fetch(
      post("/api/ops/inbox/actions/configure-ai", {
        enabled: "enabled",
        sensitivity: "balanced",
        comparisonLimit: 0,
        pendingWarningAt: 3,
      }, MODERATOR)
    );
    expect(invalid.status).toBe(422);

    const response = await SELF.fetch(
      post("/api/ops/inbox/actions/configure-ai", {
        enabled: "disabled",
        sensitivity: "permissive",
        comparisonLimit: 4,
        pendingWarningAt: 5,
      }, MODERATOR)
    );
    expect(response.status).toBe(200);
    const row = await env.DB.prepare(
      "SELECT enabled, sensitivity, comparison_limit, pending_warn FROM moderation_policy WHERE id = 1"
    ).first<Record<string, unknown>>();
    expect(row).toMatchObject({
      enabled: 0,
      sensitivity: "permissive",
      comparison_limit: 4,
      pending_warn: 5,
    });
    const catalog = await (await SELF.fetch(get("/api/ops/inbox/actions", MODERATOR))).json() as {
      actions: { id: string; settingsGroup?: string; params: { key: string; currentValue?: unknown }[] }[];
    };
    const settings = catalog.actions.find((action) => action.id === "configure-ai")!;
    expect(settings.settingsGroup).toBe("ai");
    expect(Object.fromEntries(settings.params.map((param) => [param.key, param.currentValue])))
      .toEqual({ mode: "manual", maxPerHour: 5, maxPerAuthorDay: 1, sensitivity: "permissive", comparisonLimit: 4, pendingWarningAt: 5 });
  });

  it("makes a face visible, with its parameters byte-for-byte as submitted", async () => {
    const { id, slug } = await submit();
    expect((await publish(id)).status).toBe(200);

    const index = (await (await SELF.fetch(get("/index.json"))).json()) as {
      count: number;
      faces: { slug: string; name: string; installs: number }[];
    };
    expect(index.count).toBe(1);
    expect(index.faces[0]?.slug).toBe(slug);

    const face = (await (await SELF.fetch(get(`/faces/${slug}`))).json()) as {
      name: string;
      params: Record<string, unknown>;
    };
    // Verbatim. The on-disk format is the interchange format, so what comes out
    // must be what went in -- re-serializing here would make the service a
    // second opinion about the file format.
    expect(face.params).toEqual(submission()["params"]);
  });

  it("refuses moderation without the token, and with a wrong one", async () => {
    const { id } = await submit();
    expect((await SELF.fetch(post(`/admin/faces/${id}/publish`, {}))).status).toBe(401);
    expect(
      (await SELF.fetch(post(`/admin/faces/${id}/publish`, {}, { authorization: "Bearer nope" })))
        .status,
    ).toBe(401);
  });

  it("will not record a rejection without a reason, because appeals are promised", async () => {
    const { id } = await submit();
    expect((await SELF.fetch(post(`/admin/faces/${id}/reject`, {}, MODERATOR))).status).toBe(422);
    const ok = await SELF.fetch(post(`/admin/faces/${id}/reject`, { reason: "not a face" }, MODERATOR));
    expect(ok.status).toBe(200);
  });

  it("takes a removed face out of the index and stops serving it", async () => {
    const { id, slug } = await submit();
    await publish(id);
    expect((await SELF.fetch(get(`/faces/${slug}`))).status).toBe(200);

    await SELF.fetch(post(`/admin/faces/${id}/remove`, { reason: "reported" }, MODERATOR));

    // The cache is purged by the moderation action, so a removal does not wait
    // out the cache window.
    expect((await SELF.fetch(get(`/faces/${slug}`))).status).toBe(404);
    const index = (await (await SELF.fetch(get("/index.json"))).json()) as { count: number };
    expect(index.count).toBe(0);
  });

  it("shows the queue oldest first", async () => {
    const first = await submit();
    const other = submission();
    (other["params"] as Record<string, unknown>)["scale"] = 27;
    await SELF.fetch(post("/faces", other, await signedIn()));

    const queue = (await (await SELF.fetch(get("/admin/queue", MODERATOR))).json()) as {
      count: number;
      faces: { id: string }[];
    };
    expect(queue.count).toBe(2);
    expect(queue.faces[0]?.id).toBe(first.id);
  });
});

describe("what a submitter can find out", () => {
  it("reports a submission's state without being told who is asking", async () => {
    const { id } = await submit();
    const pending = (await (await SELF.fetch(get(`/submissions/${id}`))).json()) as { state: string };
    expect(pending.state).toBe("pending");

    await SELF.fetch(post(`/admin/faces/${id}/reject`, { reason: "too close to a logo" }, MODERATOR));
    const rejected = (await (await SELF.fetch(get(`/submissions/${id}`))).json()) as {
      state: string;
      reason: string;
    };
    // "Waiting" and "quietly rejected" are the states people actually ask
    // about, and a confirmation-and-nothing-else cannot tell them apart.
    expect(rejected.state).toBe("rejected");
    expect(rejected.reason).toBe("too close to a logo");
  });

  it("lets the author withdraw their own face, and nobody else", async () => {
    const { id } = await submit();
    expect(
      (await SELF.fetch(post(`/submissions/${id}/withdraw`, {}, await signedIn("someone-else")))).status
    ).toBe(403);
    expect((await SELF.fetch(post(`/submissions/${id}/withdraw`, {}))).status).toBe(401);
    expect(
      (await SELF.fetch(post(`/submissions/${id}/withdraw`, {}, await signedIn()))).status
    ).toBe(200);
  });

  it("frees the parameters again once a face is withdrawn", async () => {
    // Taking your own face back has to leave you able to submit it again --
    // which is why the byte-identical index excludes withdrawn rows.
    const { id } = await submit();
    await SELF.fetch(post(`/submissions/${id}/withdraw`, {}, await signedIn()));
    const again = await SELF.fetch(post("/faces", submission(), await signedIn()));
    expect(again.status).toBe(201);
  });
});

describe("the install counter", () => {
  it("counts an install and carries nothing about the person", async () => {
    const { id, slug } = await submit();
    await publish(id);

    const response = await SELF.fetch(
      new Request(`https://catalog.test/faces/${slug}/installed`, {
        method: "POST",
        headers: { "cf-connecting-ip": "203.0.113.7" },
      }),
    );
    expect(response.status).toBe(204);
    expect(await response.text()).toBe("");

    const row = await env.DB.prepare("SELECT installs FROM faces WHERE slug = ?")
      .bind(slug)
      .first<{ installs: number }>();
    expect(row?.installs).toBe(1);
  });

  it("says nothing about whether a face exists", async () => {
    // Otherwise the endpoint becomes a way to enumerate what has been taken
    // down.
    const response = await SELF.fetch(
      new Request("https://catalog.test/faces/never_existed/installed", { method: "POST" }),
    );
    expect(response.status).toBe(204);
  });

  it("orders the gallery by installs", async () => {
    const quiet = await submit();
    const other = submission();
    (other["params"] as Record<string, unknown>)["scale"] = 27;
    const popularResponse = await SELF.fetch(post("/faces", other, await signedIn()));
    const popular = (await popularResponse.json()) as Submitted;

    await publish(quiet.id);
    await publish(popular.id);

    for (let i = 0; i < 3; i++) {
      await SELF.fetch(
        new Request(`https://catalog.test/faces/${popular.slug}/installed`, { method: "POST" }),
      );
    }
    await caches.default.delete("https://catalog.test/index.json");

    const index = (await (await SELF.fetch(get("/index.json"))).json()) as {
      faces: { slug: string; installs: number }[];
    };
    expect(index.faces[0]?.slug).toBe(popular.slug);
    expect(index.faces[0]?.installs).toBe(3);
  });
});

describe("reporting", () => {
  it("accepts a report and queues it for a human, changing nothing", async () => {
    const { id, slug } = await submit();
    await publish(id);

    const response = await SELF.fetch(
      post("/reports", { slug, reason: "impersonation", detail: "this is my logo" }),
    );
    expect(response.status).toBe(201);

    // A report is a message, not an action. Nothing here hides a face: with no
    // accounts, "N people reported it" is one person and a loop.
    expect((await SELF.fetch(get(`/faces/${slug}`))).status).toBe(200);

    const open = (await (await SELF.fetch(get("/admin/reports", MODERATOR))).json()) as {
      count: number;
      reports: { reason: string }[];
    };
    expect(open.count).toBe(1);
    expect(open.reports[0]?.reason).toBe("impersonation");
  });

  it("refuses a reason that is not one of the listed ones", async () => {
    const response = await SELF.fetch(
      post("/reports", { slug: "anything", reason: "made up" }),
    );
    expect(response.status).toBe(422);
  });

  it("accepts a report for a face that does not exist", async () => {
    // A reporter should never have to know whether a face is published, and
    // telling them turns this into an enumeration endpoint.
    const response = await SELF.fetch(
      post("/reports", { slug: "never_existed", reason: "spam" }),
    );
    expect(response.status).toBe(201);
  });
});

describe("export", () => {
  it("emits the same files the git catalog held", async () => {
    const { id, slug } = await submit();
    await publish(id);

    const body = (await (await SELF.fetch(get("/export"))).json()) as {
      count: number;
      files: Record<string, unknown>;
    };
    expect(body.count).toBe(1);
    // The keys are the paths the git catalog used, so writing this out as files
    // reproduces that repository -- which is what buys back the portability
    // moving off git gave up.
    expect(Object.keys(body.files).sort()).toEqual([`faces/${slug}.json`, "index.json"]);

    const face = body.files[`faces/${slug}.json`] as { name: string; params: unknown };
    expect(face.name).toBe("Fixture Face");
    expect(face.params).toEqual(submission()["params"]);
  });
});

describe("rate limiting", () => {
  it("stores a salted hash of the address and never the address", async () => {
    await submit();
    const rows = await env.DB.prepare("SELECT bucket FROM rate").all<{ bucket: string }>();
    expect(rows.results.length).toBeGreaterThan(0);
    for (const row of rows.results) {
      expect(row.bucket).toMatch(/^[0-9a-f]{64}$/);
      expect(row.bucket).not.toContain("203.0.113.7");
    }
  });

  it("turns a flood away, and says when to come back", async () => {
    // A speed bump, not the abuse control -- pre-moderation is that. This only
    // stops one machine filling the queue in a second.
    let last: Response | null = null;
    for (let i = 0; i < 12; i++) {
      const face = submission();
      (face["params"] as Record<string, unknown>)["scale"] = 10 + i;
      last = await SELF.fetch(post("/faces", face, await signedIn()));
    }
    expect(last?.status).toBe(429);
    expect(last?.headers.get("retry-after")).toBeTruthy();
  });
});
