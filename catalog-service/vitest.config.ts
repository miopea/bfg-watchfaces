import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { TEST_PUBLIC_JWK } from "./test/testkey.js";

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
        // Stand in for Google's key server, so the Worker's OWN verification
        // succeeds against tokens the tests signed. The alternative was making
        // the JWKS URL configurable, which would put "who may sign tokens" on a
        // production knob -- a far nastier thing to get wrong than an audience.
        //
        // Anything else the Worker tries to fetch fails loudly here rather than
        // quietly reaching the internet.
        outboundService: (request: Request) => {
          if (new URL(request.url).hostname === "api.anthropic.com") {
            return new Response(
              JSON.stringify({
                content: [{
                  type: "text",
                  text: JSON.stringify({
                    recommendation: "approve",
                    confidence: "high",
                    rationale: "No concrete abuse or saturation signal is visible.",
                    signals: ["distinct from recent faces"],
                  }),
                }],
              }),
              { headers: { "content-type": "application/json" } }
            );
          }
          if (new URL(request.url).pathname === "/oauth2/v3/certs") {
            return new Response(JSON.stringify({ keys: [TEST_PUBLIC_JWK] }), {
              headers: { "content-type": "application/json" },
            });
          }
          return new Response(`refused an unexpected outbound call to ${request.url}`, { status: 502 });
        },
        bindings: {
          // The client id every test token is minted for. The tests sign their
          // own tokens with their own key rather than reaching Google, so
          // nothing here touches the network.
          GOOGLE_CLIENT_ID: "test-client-id.apps.googleusercontent.com",
          MODERATOR_TOKEN: "test-moderator-token",
          IP_SALT: "test-salt",
          ANTHROPIC_API_KEY: "test-anthropic-key",
          ANTHROPIC_MODEL: "claude-haiku-4-5-20251001",
        },
      },
    }),
  ],
});
