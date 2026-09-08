/**
 * Watch progress for one household, so a film paused on one TV can be
 * continued on another.
 *
 * The whole service is two calls against one key. It holds no credentials and
 * no names: the key is a salted hash the app computes from the provider host
 * and username, so this side cannot tell you who anybody is, only that two
 * boxes belong together.
 *
 *   GET  /v1/progress/<id>   -> the stored blob, or an empty one
 *   PUT  /v1/progress/<id>   -> replace it
 *
 * Both need `Authorization: Bearer <SYNC_KEY>`. That shared secret is not
 * per-viewer security — it is what stops the endpoint being a free key-value
 * store for the internet. The id is what separates households, and it is
 * unguessable without the app's salt.
 *
 * The app merges. This never does: merging needs the two histories in hand and
 * the app has them, so the server stays a dumb box and one less thing can be
 * wrong in a place nobody can debug.
 */

const ID = /^[0-9a-f]{64}$/;          // exactly what accountId() produces
const MAX_BYTES = 256 * 1024;          // 500 entries is ~60KB; this is slack

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const match = url.pathname.match(/^\/v1\/progress\/([^/]+)$/);
    if (!match) return text(404, "not found");

    const id = match[1];
    // Checked before the auth compare so a malformed id cannot be used to
    // probe timing on the key.
    if (!ID.test(id)) return text(400, "bad id");

    // SYNC_KEY_PREVIOUS is optional and exists only for rotation. Without it
    // changing the key means every box is locked out until it updates, which
    // makes rotating something nobody ever does — and a secret nobody rotates
    // is a secret that leaks eventually. Set it to the old key, ship the app
    // with the new one, then unset it.
    const auth = request.headers.get("authorization") || "";
    const accepted = [env.SYNC_KEY, env.SYNC_KEY_PREVIOUS]
      .filter(Boolean)
      .map((k) => `Bearer ${k}`);
    if (!accepted.includes(auth)) return text(401, "unauthorized");

    if (request.method === "GET") {
      const stored = await env.PROGRESS.get(`p:${id}`);
      return json(200, stored ?? '{"version":1,"entries":{}}');
    }

    if (request.method === "PUT") {
      const body = await request.text();
      if (body.length > MAX_BYTES) return text(413, "too large");
      // Parsed only to reject junk. The shape beyond this is the app's
      // business, and validating it here would mean two definitions of it.
      try {
        const parsed = JSON.parse(body);
        if (typeof parsed !== "object" || parsed === null) throw new Error();
      } catch {
        return text(400, "bad json");
      }
      // A year of not switching a TV on should not lose your place; a
      // household that has gone for good should not be stored forever.
      await env.PROGRESS.put(`p:${id}`, body, {
        expirationTtl: 60 * 60 * 24 * 365,
      });
      return text(204, "");
    }

    return text(405, "method not allowed");
  },
};

const json = (status, body) =>
  new Response(body, { status, headers: { "content-type": "application/json" } });

const text = (status, body) =>
  new Response(body, { status, headers: { "content-type": "text/plain" } });
