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

async function submitAs(sub: string, scale = 26): Promise<Submitted> {
  const face = submission();
  (face["params"] as Record<string, unknown>)["scale"] = scale;
  const response = await SELF.fetch(post("/faces", face, await signedIn(sub)));
  expect(response.status).toBe(201);
  return (await response.json()) as Submitted;
}

/**
 * What an account buys that anonymity could not.
 *
 * The catalog's whole shape came from one sentence — "no account means no ban".
 * These are the things that sentence was preventing.
 */
describe("blocking an author", () => {
  it("refuses a blocked author's next submission", async () => {
    const first = await submitAs("bad-actor", 26);
    const row = await env.DB.prepare("SELECT author_key FROM faces WHERE id = ?")
      .bind(first.id)
      .first<{ author_key: string }>();
    const key = row?.author_key ?? "";
    expect(key).toMatch(/^[0-9a-f]{64}$/);

    expect(
      (await SELF.fetch(post(`/admin/authors/${key}/block`, { reason: "repeatedly submitting slurs" }, MODERATOR)))
        .status
    ).toBe(200);

    const face = submission();
    (face["params"] as Record<string, unknown>)["scale"] = 30;
    const blocked = await SELF.fetch(post("/faces", face, await signedIn("bad-actor")));
    expect(blocked.status).toBe(403);
  });

  it("does not tell a blocked author that they are blocked", async () => {
    // Telling somebody they are blocked tells them to make another account.
    // The words are the same ones any refusal gets.
    const first = await submitAs("bad-actor-2");
    const row = await env.DB.prepare("SELECT author_key FROM faces WHERE id = ?")
      .bind(first.id)
      .first<{ author_key: string }>();
    await SELF.fetch(post(`/admin/authors/${row?.author_key}/block`, { reason: "spam" }, MODERATOR));

    const face = submission();
    (face["params"] as Record<string, unknown>)["scale"] = 31;
    const blocked = await SELF.fetch(post("/faces", face, await signedIn("bad-actor-2")));
    const body = (await blocked.json()) as { error: string };
    expect(body.error).not.toMatch(/block/i);
    expect(body.error).not.toMatch(/ban/i);
  });

  it("blocks one author without touching anybody else", async () => {
    const first = await submitAs("blocked-one", 26);
    const row = await env.DB.prepare("SELECT author_key FROM faces WHERE id = ?")
      .bind(first.id)
      .first<{ author_key: string }>();
    await SELF.fetch(post(`/admin/authors/${row?.author_key}/block`, { reason: "spam" }, MODERATOR));

    const face = submission();
    (face["params"] as Record<string, unknown>)["scale"] = 40;
    expect((await SELF.fetch(post("/faces", face, await signedIn("innocent")))).status).toBe(201);
  });

  it("will not block without a reason", async () => {
    const key = "a".repeat(64);
    expect((await SELF.fetch(post(`/admin/authors/${key}/block`, {}, MODERATOR))).status).toBe(422);
  });
});

describe("my submissions", () => {
  it("shows an author only their own, and survives a new device", async () => {
    await submitAs("author-a", 26);
    await submitAs("author-b", 30);

    // A DIFFERENT token for the same person -- which is what a reinstall or a
    // new phone produces. The random per-install id this replaced could not do
    // this, and the spec had to apologise for it.
    const mine = (await (await SELF.fetch(get("/mine", await signedIn("author-a")))).json()) as {
      count: number;
      faces: { name: string }[];
    };
    expect(mine.count).toBe(1);
  });

  it("needs a sign-in", async () => {
    expect((await SELF.fetch(get("/mine"))).status).toBe(401);
  });
});

describe("deleting an account", () => {
  /**
   * THE SETTLED BEHAVIOUR, and the operator's reasoning is the whole of it: a
   * watch face is parameters — "knotwork, scale 26, pewter" — and settings are
   * not personal information. The account id was the personal data; it goes.
   * The face stays, because other people may already be using it.
   */
  it("abandons a published face rather than removing it", async () => {
    const published = await submitAs("leaver", 26);
    await passReview(published.id);
    await SELF.fetch(post(`/admin/faces/${published.id}/publish`, {}, MODERATOR));

    const before = (await (await SELF.fetch(get("/index.json"))).json()) as { count: number };
    expect(before.count).toBe(1);

    const gone = await SELF.fetch(
      new Request("https://catalog.test/me", { method: "DELETE", headers: await signedIn("leaver") })
    );
    expect(gone.status).toBe(200);

    await caches.default.delete("https://catalog.test/index.json");
    const after = (await (await SELF.fetch(get("/index.json"))).json()) as { count: number };
    expect(after.count).toBe(1) // still there, still installable

    const row = await env.DB.prepare("SELECT author_key, state FROM faces WHERE id = ?")
      .bind(published.id)
      .first<{ author_key: string | null; state: string }>();
    expect(row?.author_key).toBeNull();
    expect(row?.state).toBe("published");
  });

  /**
   * A pending face is withdrawn instead. Nobody has it yet, and leaving it
   * would ask a moderator to review something with nobody to answer for it.
   */
  it("withdraws a pending face rather than leaving it in the queue", async () => {
    const pending = await submitAs("leaver-2", 30);
    await SELF.fetch(
      new Request("https://catalog.test/me", { method: "DELETE", headers: await signedIn("leaver-2") })
    );
    const row = await env.DB.prepare("SELECT author_key, state FROM faces WHERE id = ?")
      .bind(pending.id)
      .first<{ author_key: string | null; state: string }>();
    expect(row?.state).toBe("withdrawn");
    expect(row?.author_key).toBeNull();
  });

  it("leaves other people's faces alone", async () => {
    const mine = await submitAs("leaver-3", 26);
    const theirs = await submitAs("stayer", 30);
    await SELF.fetch(
      new Request("https://catalog.test/me", { method: "DELETE", headers: await signedIn("leaver-3") })
    );
    const other = await env.DB.prepare("SELECT author_key FROM faces WHERE id = ?")
      .bind(theirs.id)
      .first<{ author_key: string | null }>();
    expect(other?.author_key).not.toBeNull();
    void mine;
  });

  it("needs a sign-in", async () => {
    expect(
      (await SELF.fetch(new Request("https://catalog.test/me", { method: "DELETE" }))).status
    ).toBe(401);
  });
});

describe("what the moderator can now see", () => {
  it("shows how this author's previous faces went", async () => {
    // The main thing an account buys a moderator: whether this is somebody's
    // first face or their eleventh. No amount of looking at ONE face tells you.
    const first = await submitAs("prolific", 26);
    await passReview(first.id);
    await SELF.fetch(post(`/admin/faces/${first.id}/publish`, {}, MODERATOR));
    const second = await submitAs("prolific", 30);
    await SELF.fetch(post(`/admin/faces/${second.id}/reject`, { reason: "not a face" }, MODERATOR));
    await submitAs("prolific", 40);

    const queue = (await (await SELF.fetch(get("/admin/queue", MODERATOR))).json()) as {
      faces: { author_published: number; author_rejected: number }[];
    };
    expect(queue.faces[0]?.author_published).toBe(1);
    expect(queue.faces[0]?.author_rejected).toBe(1);
  });
});

describe("what stays anonymous", () => {
  it("reads need no sign-in at all", async () => {
    for (const path of ["/index.json", "/config", "/export"]) {
      expect((await SELF.fetch(get(path))).status).toBe(200);
    }
  });

  /**
   * R2's original reasoning, which accounts do not change: requiring an account
   * to report became intolerable the moment submitting did not. "Anyone could
   * publish and only developers could complain" is what moved this catalog off
   * GitHub.
   */
  it("reporting needs no sign-in, and must not start needing one", async () => {
    const response = await SELF.fetch(post("/reports", { slug: "anything_7f3a", reason: "spam" }));
    expect(response.status).toBe(201);
  });
});
