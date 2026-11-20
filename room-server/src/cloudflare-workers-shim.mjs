// Stand-in for the `cloudflare:workers` module when the room Worker runs on Node.
// The Worker only needs the DurableObject base class, which stores ctx and env.
export class DurableObject {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }
}
