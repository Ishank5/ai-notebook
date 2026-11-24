import { apiFetch } from "../../lib/api";

/** One tracker for every action item. Members change their own; admins any. */
export const TASKS_PATH = (status = "all") => `/api/tasks?status=${status}`;

const task = (id) => `/api/tasks/${encodeURIComponent(id)}`;

export const createTask = (fields) => apiFetch("/api/tasks", { method: "POST", body: fields }).then((d) => d.task);
export const updateTask = (id, fields) => apiFetch(task(id), { method: "PATCH", body: fields }).then((d) => d.task);
export const deleteTask = (id) => apiFetch(task(id), { method: "DELETE" });
