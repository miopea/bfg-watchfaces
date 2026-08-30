import { env, SELF } from "cloudflare:test";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { get, migrate, MODERATOR, post, reset, submission } from "./helpers";

beforeAll(migrate);
beforeEach(reset);

interface Submitted {
  id: string;
  slug: string;
  state: string;
}

async function submit(overrides: Record<string, unknown> = {}): Promise<Submitted> {
  const response = await SELF.fetch(post("/faces", submission(overrides)));
  expect(response.status).toBe(201);
  return (await response.json()) as Submitted;
}

async function publish(id: string): Promise<Response> {
  return SELF.fetch(post(`/admin/faces/${id}/publish`, {}, MODERATOR));
}

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
    const response = await SELF.fetch(post("/faces", other));
    expect(response.status).toBe(201);
    const second = (await response.json()) as Submitted;

    expect(second.slug).not.toBe(first.slug);
    expect(second.slug.startsWith("fixture_face_")).toBe(true);
  });

  it("refuses a byte-identical resubmission", async () => {
    await submit();
    const response = await SELF.fetch(post("/faces", submission()));
    expect(response.status).toBe(409);
  });

  it("hashes past key order, so a reordered but identical face is still a duplicate", async () => {
    await submit();
    const reordered = submission();
    const params = reordered["params"] as Record<string, unknown>;
    // Same content, different insertion order.
    reordered["params"] = Object.fromEntries(Object.entries(params).reverse());
    const response = await SELF.fetch(post("/faces", reordered));
    expect(response.status).toBe(409);
  });

  it("refuses an invalid face with every problem listed", async () => {
    const bad = submission();
    (bad["params"] as Record<string, unknown>)["engine"] = "TEXTURE";
    const response = await SELF.fetch(post("/faces", bad));
    expect(response.status).toBe(422);
    const body = (await response.json()) as { problems: { field: string }[] };
    expect(body.problems.some((p) => p.field === "engine")).toBe(true);
  });

  it("refuses a submission with no proof of humanity", async () => {
    const response = await SELF.fetch(post("/faces", submission({ turnstile: undefined })));
    expect(response.status).toBe(403);
  });

  it("refuses a body larger than a face can be", async () => {
    const huge = submission({ author: "x".repeat(9000) });
    const response = await SELF.fetch(post("/faces", huge));
    expect(response.status).toBe(413);
  });
});

describe("publishing", () => {
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
    await SELF.fetch(post("/faces", other));

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

  it("lets the device that submitted a face withdraw it, and nobody else", async () => {
    const { id } = await submit();
    expect((await SELF.fetch(post(`/submissions/${id}/withdraw`, { installId: "someone-else" }))).status).toBe(403);
    expect((await SELF.fetch(post(`/submissions/${id}/withdraw`, { installId: "install-aaaa" }))).status).toBe(200);
  });

  it("frees the parameters again once a face is withdrawn", async () => {
    // Taking your own face back has to leave you able to submit it again --
    // which is why the byte-identical index excludes withdrawn rows.
    const { id } = await submit();
    await SELF.fetch(post(`/submissions/${id}/withdraw`, { installId: "install-aaaa" }));
    const again = await SELF.fetch(post("/faces", submission()));
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

    const row = await env.DB.prepare("SELECT installs, install_id FROM faces WHERE slug = ?")
      .bind(slug)
      .first<{ installs: number; install_id: string | null }>();
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
    const popularResponse = await SELF.fetch(post("/faces", other));
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
      post("/reports", { slug, reason: "impersonation", detail: "this is my logo", turnstile: "XXXX.DUMMY.TOKEN.XXXX" }),
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
      post("/reports", { slug: "anything", reason: "made up", turnstile: "XXXX.DUMMY.TOKEN.XXXX" }),
    );
    expect(response.status).toBe(422);
  });

  it("accepts a report for a face that does not exist", async () => {
    // A reporter should never have to know whether a face is published, and
    // telling them turns this into an enumeration endpoint.
    const response = await SELF.fetch(
      post("/reports", { slug: "never_existed", reason: "spam", turnstile: "XXXX.DUMMY.TOKEN.XXXX" }),
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
      last = await SELF.fetch(post("/faces", face));
    }
    expect(last?.status).toBe(429);
    expect(last?.headers.get("retry-after")).toBeTruthy();
  });
});
