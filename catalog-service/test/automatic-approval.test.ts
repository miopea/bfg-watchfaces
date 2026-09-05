import { env, SELF } from "cloudflare:test";
import { beforeAll, beforeEach, expect, it } from "vitest";
import { migrate, reset, get, post, MODERATOR } from "./helpers";
import { getPolicy } from "../src/ai-review";
import { applyAutomaticApproval } from "../src/automatic-approval";
beforeAll(migrate);
beforeEach(async () => {
  await reset();
  await env.DB.prepare("DELETE FROM blocked_authors").run();
  await env.DB.prepare("UPDATE automatic_approval_policy SET mode='automatic' WHERE id=1").run();
});
async function seed(author?: string, preview?: string): Promise<string> {
  const id = crypto.randomUUID(); const hash = id.replaceAll("-", "").padEnd(64, "0");
  const at = new Date().toISOString();
  await env.DB.prepare("INSERT INTO faces(id,slug,name,author,params,params_hash,engine,dial_color,ink_color,generator_version,author_key,state,created) VALUES(?,?,?,'Author','{}',?,'knotwork','#000000','#ffffff',1,?,'pending',?)").bind(id, id, "Candidate", hash, author ?? id, at).run();
  await env.DB.prepare("INSERT INTO face_reviews VALUES(?,?,1,'passed','[]',?,?)").bind(id, hash, preview ?? btoa("\x89PNG\r\n\x1a\n" + id), at).run();
  const libraryRevision = (await env.DB.prepare("SELECT revision FROM catalog_revision WHERE id=1").first<{ revision: number }>())!.revision;
  await env.DB.prepare("INSERT INTO face_ai_reviews VALUES(?,?,1,?,'approve','high','No concerns',?,?)").bind(id, hash, env.ANTHROPIC_MODEL ?? "claude-haiku-4-5-20251001", JSON.stringify({ policy: await getPolicy(env), libraryRevision }), at).run();
  await env.DB.prepare("INSERT INTO moderation_jobs(face_id,params_hash,generator_version,status,stage,lease,updated_at) VALUES(?,?,1,'complete','ai',?,?)").bind(id, hash, "lease-" + id, Date.now()).run();
  return id;
}
const approve = (id: string) => applyAutomaticApproval(env, id, "lease-" + id);
it("connects processing completion to publication and exposes its approval history", async () => {
  const id = await seed();
  await env.DB.prepare("UPDATE moderation_jobs SET status='running',lease_until=? WHERE face_id=?").bind(Date.now() + 300_000, id).run();
  const response = await SELF.fetch(post(`/admin/faces/${id}/processing/report`, { lease: "lease-" + id, status: "complete", stage: "ai" }, MODERATOR));
  expect(response.status).toBe(200);
  expect(await response.json()).toMatchObject({ published: true });
  const history = await (await SELF.fetch(get("/api/ops/audit", MODERATOR))).json() as { rows: { face_id: string }[] };
  expect(history.rows[0]?.face_id).toBe(id);
  const library = await (await SELF.fetch(get("/api/ops/library", MODERATOR))).json() as { rows: { approved_by: string }[] };
  expect(library.rows[0]?.approved_by).toBe("Automatic policy");
});
it("publishes an eligible exact revision once and keeps an atomic policy audit", async () => {
  const id = await seed();
  expect(await approve(id)).toEqual({ published: true });
  expect(await env.DB.prepare("SELECT state FROM faces WHERE id=?").bind(id).first()).toEqual({ state: "published" });
  expect((await approve(id)).published).toBe(false);
  expect(await env.DB.prepare("SELECT COUNT(*) AS count FROM automatic_publications").first()).toEqual({ count: 1 });
});
it.each([
  "UPDATE face_ai_reviews SET confidence='medium'",
  "UPDATE face_ai_reviews SET recommendation='review'",
  "UPDATE face_ai_reviews SET created='2000-01-01T00:00:00.000Z'",
  "UPDATE face_ai_reviews SET params_hash='stale'",
  "UPDATE face_reviews SET verdict='failed'",
  "UPDATE face_reviews SET params_hash='stale'",
  "UPDATE faces SET author_key=NULL",
  "UPDATE moderation_policy SET sensitivity='cautious'",
  "UPDATE automatic_approval_policy SET mode='recommendations'",
  "UPDATE catalog_revision SET revision=revision+1 WHERE id=1",
])("withholds approval when a safeguard changes: %s", async sql => {
  const id = await seed(); await env.DB.prepare(sql).run();
  expect((await approve(id)).published).toBe(false);
  expect(await env.DB.prepare("SELECT state FROM faces WHERE id=?").bind(id).first()).toEqual({ state: "pending" });
});
it("checks current published previews, while removed previews are only history", async () => {
  const old = await seed("one", "same-preview");
  await env.DB.prepare("UPDATE faces SET state='removed' WHERE id=?").bind(old).run();
  const id = await seed("two", "same-preview");
  expect((await approve(id)).published).toBe(true);
  const next = await seed("three", "same-preview");
  expect((await approve(next)).published).toBe(false);
});
it("enforces the global limit when two candidates finish concurrently", async () => {
  await env.DB.prepare("UPDATE automatic_approval_policy SET max_per_hour=1 WHERE id=1").run();
  const ids = await Promise.all([seed(), seed()]);
  const outcomes = await Promise.all(ids.map(approve));
  expect(outcomes.filter(outcome => outcome.published)).toHaveLength(1);
  const fresh = await seed();
  expect((await approve(fresh)).published).toBe(false);
});
it("keeps blocked authors and saturated author queues for a human", async () => {
  const blocked = await seed("blocked");
  await env.DB.prepare("INSERT INTO blocked_authors VALUES('blocked','abuse',?)").bind(new Date().toISOString()).run();
  expect((await approve(blocked)).published).toBe(false);
  const ids = await Promise.all([seed("busy"), seed("busy"), seed("busy")]);
  expect((await approve(ids[0]!)).published).toBe(false);
  const first = await seed("limited"); expect((await approve(first)).published).toBe(true);
  const second = await seed("limited"); expect((await approve(second)).published).toBe(false);
});
