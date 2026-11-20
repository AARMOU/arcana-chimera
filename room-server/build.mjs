// Bundles the vendored Cloudflare room Worker into plain Node.js ESM.
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const here = (relative) => fileURLToPath(new URL(relative, import.meta.url));
await build({
  entryPoints: [here("./vendor/room-worker.ts")],
  outfile: here("./dist/room-worker.mjs"),
  bundle: true,
  format: "esm",
  platform: "node",
  target: "node22",
  alias: { "cloudflare:workers": here("./src/cloudflare-workers-shim.mjs") },
  logLevel: "warning",
});
console.log("Built dist/room-worker.mjs");
