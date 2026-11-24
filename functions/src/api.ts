import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { onRequest, type Request } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import type { Response } from "express";

import { recordingAction, recordingMedia, recordingSettings, recordingUploadProgress, testSummaryModel } from "./recording/admin.js";
import {
  ANTHROPIC_API_KEY,
  ELEVENLABS_API_KEY,
  ELEVENLABS_WEBHOOK_SECRET,
  HttpsError,
  OPENAI_API_KEY,
  REGION,
  USERS,
  describe,
  type Caller,
} from "./recording/common.js";
import { ask } from "./recording/ask.js";
import { dashboard } from "./recording/dashboard.js";
import { followUpEmail } from "./recording/email.js";
import { generateInsights, getInsights } from "./recording/insights.js";
import { listRecordings, recordingDetail } from "./recording/read.js";
import { createTask, deleteTask, listTasks, updateTask } from "./recording/tasks.js";
import { finalizeRecording, recordingStatuses, recordingUploadUrls } from "./recordings.js";

/**
 * `ntApi`: the whole HTTP API, for the phones (function URL) and the admin panel (Hosting rewrite `/api/**`).
 *
 * AUTH. `Authorization: Bearer <Firebase ID token>`, then `notetakerUsers/{uid}`: no profile or an inactive one is
 * refused. Members record and read their own recordings; admins read everyone's and manage.
 *
 * ERRORS, as the Render API sent them: a refusal is `status` + `{ error: key, code: key }`; anything else is
 * `500 { error: "Something went wrong. Retry." }`.
 */

const MEMBER = ["recordings.record", "recordings.read"];
const ADMIN = [...MEMBER, "recordings.read_all", "recordings.manage"];

type Body = Record<string, unknown>;
type Route = {
  method: "GET" | "POST" | "PATCH" | "DELETE";
  /** `:id` matches one path segment. */
  path: string;
  admin?: boolean;
  handler: (caller: Caller, body: Body, req: Request, id: string) => Promise<unknown> | unknown;
};

/* Fixed paths come before `/:id`, so `/api/recordings/settings` is never read as an id. */
const ROUTES: Route[] = [
  {
    method: "GET",
    path: "/api/me",
    handler: (c) => ({ uid: c.uid, name: c.name, email: c.email, role: c.role, active: true, permissions: c.admin ? ADMIN : MEMBER }),
  },
  { method: "POST", path: "/api/recordings/upload-urls", handler: (c, b) => recordingUploadUrls(c, b) },
  { method: "POST", path: "/api/recordings/finalize", handler: (c, b) => finalizeRecording(c, b) },
  { method: "POST", path: "/api/recordings/statuses", handler: (c, b) => recordingStatuses(c, b) },
  { method: "GET", path: "/api/recordings/settings", admin: true, handler: (c) => recordingSettings(c, {}) },
  { method: "POST", path: "/api/recordings/settings", admin: true, handler: (c, b) => recordingSettings(c, b) },
  { method: "POST", path: "/api/recordings/test-model", admin: true, handler: (c, b) => testSummaryModel(c, b) },
  { method: "POST", path: "/api/recordings/upload-progress", admin: true, handler: (c, b) => recordingUploadProgress(c, b) },
  { method: "GET", path: "/api/recordings", handler: (c, _b, req) => listRecordings(c, req.query as Body) },
  { method: "GET", path: "/api/recordings/:id", handler: (c, _b, _r, id) => recordingDetail(c, id) },
  { method: "POST", path: "/api/recordings/:id/media", handler: (c, b, _r, id) => recordingMedia(c, id, b) },
  { method: "POST", path: "/api/recordings/:id/action", admin: true, handler: (c, b, _r, id) => recordingAction(c, id, b) },
  { method: "POST", path: "/api/recordings/:id/email", handler: (c, b, _r, id) => followUpEmail(c, id, b) },

  // Built on the notes; members see only their own, admins everyone's.
  { method: "GET", path: "/api/dashboard", handler: (c, _b, req) => dashboard(c, req.query as Body) },
  { method: "GET", path: "/api/insights", handler: (c, _b, req) => getInsights(c, req.query as Body) },
  { method: "POST", path: "/api/insights", handler: (c, b) => generateInsights(c, b) },
  { method: "POST", path: "/api/ask", handler: (c, b) => ask(c, b) },
  { method: "GET", path: "/api/tasks", handler: (c, _b, req) => listTasks(c, req.query as Body) },
  { method: "POST", path: "/api/tasks", handler: (c, b) => createTask(c, b) },
  { method: "PATCH", path: "/api/tasks/:id", handler: (c, b, _r, id) => updateTask(c, id, b) },
  { method: "DELETE", path: "/api/tasks/:id", handler: (c, _b, _r, id) => deleteTask(c, id) },
];

/** The route for this method and path, and the `:id` it matched. */
function match(method: string, path: string): { route: Route; id: string } | null {
  const parts = path.split("/");
  for (const route of ROUTES) {
    if (route.method !== method) continue;
    const want = route.path.split("/");
    if (want.length !== parts.length) continue;
    let id = "";
    const ok = want.every((seg, i) => {
      if (seg === ":id") {
        id = parts[i];
        return id.length > 0;
      }
      return seg === parts[i];
    });
    if (ok) return { route, id };
  }
  return null;
}

/** Who is calling, or a reply already sent (401 / 403) and null. */
async function callerOf(req: Request, res: Response): Promise<Caller | null> {
  const token = /^Bearer (.+)$/.exec(req.get("authorization") ?? "")?.[1];
  if (!token) {
    res.status(401).json({ error: "Sign in again." });
    return null;
  }
  let uid: string;
  let tokenEmail = "";
  try {
    const decoded = await getAuth().verifyIdToken(token);
    uid = decoded.uid;
    tokenEmail = decoded.email ?? "";
  } catch {
    res.status(401).json({ error: "Sign in again." });
    return null;
  }

  // Default deny: a Firebase account alone gets nothing without a profile.
  const profile = (await getFirestore().collection(USERS).doc(uid).get()).data();
  if (!profile) {
    res.status(403).json({ error: "no_profile", code: "no_profile" });
    return null;
  }
  if (profile.active === false) {
    res.status(403).json({ error: "inactive", code: "inactive" });
    return null;
  }
  const role = profile.role === "admin" ? "admin" : "member";
  const email = typeof profile.email === "string" && profile.email ? profile.email : tokenEmail;
  return {
    uid,
    name: typeof profile.name === "string" && profile.name.trim() ? profile.name.trim() : email,
    email,
    role,
    admin: role === "admin",
  };
}

/** The JSON body, also when a client sent it without a JSON content type. */
function bodyOf(req: Request): Body {
  let body: unknown = req.body;
  if (Buffer.isBuffer(body)) body = body.toString("utf8");
  if (typeof body === "string") {
    try {
      body = body.trim() ? JSON.parse(body) : {};
    } catch {
      throw new HttpsError("invalid-argument", "recordings.err.badRequest");
    }
  }
  return body && typeof body === "object" && !Array.isArray(body) ? (body as Body) : {};
}

export const ntApi = onRequest(
  {
    region: REGION,
    invoker: "public",
    // The settings page reports which keys are set; the model test, insights, Ask AI and the email call the model.
    secrets: [ANTHROPIC_API_KEY, OPENAI_API_KEY, ELEVENLABS_API_KEY, ELEVENLABS_WEBHOOK_SECRET],
    timeoutSeconds: 540,
    memory: "512MiB",
    maxInstances: 10,
    concurrency: 40,
  },
  async (req, res) => {
    // Any origin: bearer tokens, not cookies, so this lets nothing in on its own.
    res.set("Access-Control-Allow-Origin", "*");
    res.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
    res.set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
    res.set("Access-Control-Max-Age", "3600");
    if (req.method === "OPTIONS") {
      res.status(204).send("");
      return;
    }

    // Called as .../ntApi/api/me on the function URL, or /api/me through Hosting.
    const path = (req.path || "/").replace(/^\/ntApi(?=\/)/, "").replace(/\/+$/, "") || "/";
    if (req.method === "GET" && path === "/api/health") {
      res.json({ ok: true });
      return;
    }
    const found = match(req.method, path);
    if (!found) {
      res.status(404).json({ error: "Not found." });
      return;
    }

    try {
      const caller = await callerOf(req, res);
      if (!caller) return;
      if (found.route.admin && !caller.admin) {
        res.status(403).json({ error: "You do not have permission for this.", code: "forbidden" });
        return;
      }
      const withBody = req.method === "POST" || req.method === "PATCH";
      const result = await found.route.handler(caller, withBody ? bodyOf(req) : {}, req, found.id);
      res.json(result ?? {});
    } catch (err) {
      if (err instanceof HttpsError) {
        res.status(err.status).json({ error: err.key, code: err.key });
        return;
      }
      logger.error("Request failed", { method: req.method, path, error: describe(err) });
      res.status(500).json({ error: "Something went wrong. Retry." });
    }
  },
);
