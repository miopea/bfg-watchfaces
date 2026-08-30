import { env } from "cloudflare:test";
import schema from "../schema.sql?raw";
import fixture from "./fixtures/face.json";

/**
 * Apply the real `schema.sql` to the test database.
 *
 * The real file, not a copy: the partial unique index that enforces "byte-identical
 * submissions are rejected" is the kind of thing a hand-maintained test copy
 * loses silently, and the test would then pass while the rule was gone.
 */
export async function migrate(): Promise<void> {
  const statements = schema
    .split("\n")
    .filter((line) => !line.trimStart().startsWith("--"))
    .join("\n")
    .split(";")
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
  for (const statement of statements) {
    await env.DB.prepare(statement).run();
  }
}

/**
 * Empty every table, so one test's rows cannot decide another's result.
 *
 * The edge cache is cleared too. It outlives the database between tests, and a
 * cached `/index.json` from a previous test is indistinguishable from the
 * service returning the wrong thing.
 */
export async function reset(): Promise<void> {
  await env.DB.prepare("DELETE FROM faces").run();
  await env.DB.prepare("DELETE FROM reports").run();
  await env.DB.prepare("DELETE FROM rate").run();
  await caches.default.delete("https://catalog.test/index.json");
}

/**
 * A real face, produced by `FaceCodec` and `CatalogStore` via
 * `./gradlew :workbench:contract`.
 *
 * Regenerated with the contract, so it cannot drift from what the app actually
 * writes. A hand-typed fixture would prove the service accepts a face nobody
 * sends.
 */
export const FIXTURE = fixture as {
  name: string;
  slug: string;
  author: string;
  created: string;
  params: Record<string, unknown>;
};

/**
 * Turnstile's documented always-passes test token, matched to the
 * always-passes secret in `vitest.config.ts`.
 */
export const HUMAN = "XXXX.DUMMY.TOKEN.XXXX";

export function submission(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    name: FIXTURE.name,
    author: FIXTURE.author,
    slug: FIXTURE.slug,
    params: structuredClone(FIXTURE.params),
    turnstile: HUMAN,
    installId: "install-aaaa",
    ...overrides,
  };
}

export function post(path: string, body: unknown, headers: Record<string, string> = {}): Request {
  return new Request(`https://catalog.test${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.7", ...headers },
    body: JSON.stringify(body),
  });
}

export function get(path: string, headers: Record<string, string> = {}): Request {
  return new Request(`https://catalog.test${path}`, { headers });
}

export const MODERATOR = { authorization: "Bearer test-moderator-token" };
