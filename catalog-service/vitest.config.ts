import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";

// Runs the tests inside workerd against a real local D1, rather than against a
// mock. A mocked database is not evidence about SQLite -- the partial unique
// index that makes byte-identical submissions impossible is enforced by the
// engine, and a mock would happily agree it works while it did not.
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: {
        d1Databases: ["DB"],
        bindings: {
          // Turnstile's own documented ALWAYS-PASSES test secret. Using a real
          // one would make the tests depend on the network and on a credential
          // nobody should need to run them.
          TURNSTILE_SECRET: "1x0000000000000000000000000000000AA",
          TURNSTILE_SITE_KEY: "1x00000000000000000000AA",
          MODERATOR_TOKEN: "test-moderator-token",
          IP_SALT: "test-salt",
        },
      },
    }),
  ],
});
