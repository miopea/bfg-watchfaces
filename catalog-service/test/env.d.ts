/// <reference types="@cloudflare/vitest-pool-workers/types" />

import type { Env as WorkerEnv } from "../src/env";

/**
 * Types the `env` the tests get from `cloudflare:test` as the Worker's own
 * bindings, so a binding the Worker needs and the test config does not provide
 * is a compile error rather than an undefined at runtime.
 *
 * It augments `Cloudflare.Env`, which is what this version of the pool declares
 * `env` as — not `ProvidedEnv`, which earlier versions used and which is what
 * the first two attempts here wrote. `declare global` is needed because the
 * `import` above makes this file a module.
 */
declare global {
  namespace Cloudflare {
    interface Env extends WorkerEnv {}
  }
}

export {};
