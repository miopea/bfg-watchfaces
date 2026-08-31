/**
 * Vite's `?raw` import, used by the tests to load `schema.sql` as text.
 *
 * Declared rather than reached for with `fs`: the tests run inside workerd,
 * which has no filesystem, and the schema has to be the same file `wrangler d1
 * execute` applies — a copy pasted into a test would let the two diverge in
 * exactly the place that decides whether the dedup rule is enforced.
 */
declare module "*?raw" {
  const content: string;
  export default content;
}
