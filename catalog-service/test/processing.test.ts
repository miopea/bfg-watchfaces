import { env, SELF } from "cloudflare:test";
import { beforeAll, beforeEach, expect, it } from "vitest";
import { get, migrate, MODERATOR, post, reset, signedIn, submission } from "./helpers";

beforeAll(migrate);
beforeEach(reset);
async function face(): Promise<string> {
  const response = await SELF.fetch(post("/faces", submission(), await signedIn("processing-test")));
  expect(response.status).toBe(201);
  return ((await response.json()) as { id: string }).id;
}
async function claim(id: string): Promise<{ claimed: boolean; lease: string }> {
  return await (await SELF.fetch(post(`/admin/faces/${id}/processing/claim`, {}, MODERATOR))).json() as { claimed: boolean; lease: string };
}
const report = (id: string, lease: string, status: string) => SELF.fetch(post(`/admin/faces/${id}/processing/report`, { lease, status, stage: "ai", error: status === "retry" ? "Provider unavailable" : null }, MODERATOR));

it("leases a submission once and makes a retry failure visible without losing it", async () => {
  const id = await face();
  const claims = await Promise.all([claim(id), claim(id)]);
  expect(claims.filter((item) => item.claimed)).toHaveLength(1);
  const lease = claims.find((item) => item.claimed)!.lease;
  expect((await report(id, lease, "retry")).status).toBe(200);
  expect(await (await SELF.fetch(get("/admin/queue?state=pending&due=1", MODERATOR))).json()).toMatchObject({ count: 0 });
  const view = await (await SELF.fetch(get("/api/ops/inbox", MODERATOR))).json() as { rows: Record<string, unknown>[] };
  expect(view.rows[0]).toMatchObject({ processing_status: "retry", processing_stage: "ai", processing_error: "Provider unavailable" });
  await env.DB.prepare("UPDATE moderation_jobs SET next_attempt = 0 WHERE face_id = ?").bind(id).run();
  const next = await claim(id);
  expect(next.claimed).toBe(true);
  expect(next.lease).not.toBe(lease);
  expect((await report(id, lease, "complete")).status).toBe(409);
  expect((await report(id, next.lease, "complete")).status).toBe(200);
  expect((await claim(id)).claimed).toBe(false);
});

it("caps automatic attempts, protects active work and permits an explicit retry", async () => {
  const id = await face();
  const active = await claim(id);
  expect((await SELF.fetch(post("/api/ops/inbox/actions/retry-review", { id }, MODERATOR))).status).toBe(409);
  await env.DB.prepare("UPDATE moderation_jobs SET attempts = 5 WHERE face_id = ?").bind(id).run();
  expect(await (await report(id, active.lease, "retry")).json()).toMatchObject({ status: "failed" });
  expect((await claim(id)).claimed).toBe(false);
  expect((await SELF.fetch(post("/api/ops/inbox/actions/retry-review", { id }, MODERATOR))).status).toBe(200);
  expect((await claim(id)).claimed).toBe(true);
});

it("recovers expired leases and refuses stale worker writes", async () => {
  const id = await face();
  const old = await claim(id);
  await env.DB.prepare("UPDATE moderation_jobs SET lease_until = 0 WHERE face_id = ?").bind(id).run();
  const next = await claim(id);
  expect(next.claimed).toBe(true);
  const staleRequest = post(`/admin/faces/${id}/ai-review`, {}, MODERATOR);
  staleRequest.headers.set("X-Moderation-Lease", old.lease);
  expect((await SELF.fetch(staleRequest)).status).toBe(409);
});

it("reports absent, successful, failed and stale runner heartbeats honestly", async () => {
  const health = async () => await (await SELF.fetch(get("/api/ops/health", MODERATOR))).json() as { status: string };
  expect((await health()).status).toBe("degraded");
  await SELF.fetch(post("/admin/processing/heartbeat", { success: true }, MODERATOR));
  expect((await health()).status).toBe("healthy");
  await SELF.fetch(post("/admin/processing/heartbeat", { success: false }, MODERATOR));
  expect((await health()).status).toBe("degraded");
  await SELF.fetch(post("/admin/processing/heartbeat", { success: true }, MODERATOR));
  await env.DB.prepare("UPDATE moderation_runner SET last_seen = 1 WHERE id = 1").run();
  expect((await health()).status).toBe("degraded");
});
