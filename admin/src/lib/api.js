import { auth } from "../firebase";

// Same origin in production (Hosting rewrites /api/** to ntApi); the Vite proxy does the same in dev.
export const API_BASE = import.meta.env.VITE_API_BASE || "";

/** A refused or failed API call. `code` says why, for example "inactive" or "recordings.err.notFound". */
export class ApiError extends Error {
  constructor(message, status, code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/** Calls the API as the signed-in user. */
export async function apiFetch(path, { method = "GET", body } = {}) {
  const token = await auth.currentUser?.getIdToken();
  if (!token) throw new ApiError("Sign in again.", 401, "signed_out");
  let res;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method,
      headers: { Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError("Could not reach the server.", 0, "unreachable");
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(data.error || "Something went wrong. Retry.", res.status, data.code);
  return data;
}

/** A failed call as one short sentence for a toast. */
export function friendlyError(err, fallback = "Something went wrong. Try again.") {
  if (!err) return fallback;
  if (err.code === "unreachable") return "Could not reach the server. Check the connection.";
  if (err.code === "signed_out" || err.status === 401) return "You are signed out. Sign in again.";
  if (err.code === "forbidden" || err.status === 403) return "This login cannot do that.";
  if (err.status === 404) return "That is not there any more.";
  if (err.status === 429) return "Too many requests. Wait a moment and try again.";
  return fallback;
}
