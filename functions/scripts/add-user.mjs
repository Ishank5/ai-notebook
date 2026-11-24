#!/usr/bin/env node
/**
 * Creates or updates an AI Notebook login (self sign-up is off on the project).
 *
 *   node scripts/add-user.mjs --key /path/to/service-account.json --email a@b.c --name "Name" --role admin|member
 *   node scripts/add-user.mjs --key ... --email a@b.c --reset-password      (asks for a new password)
 *   node scripts/add-user.mjs --key ... --email a@b.c --deactivate          (turns the login off)
 *   node scripts/add-user.mjs --key ... --email a@b.c --activate            (turns it back on)
 *
 * Creates the Firebase Auth user if missing, with a password typed at a hidden prompt (never an argument, never
 * printed), and writes `notetakerUsers/{uid}`: { name, email, role, active, createdAt }.
 */
import { readFileSync } from "node:fs";
import process from "node:process";

import { cert, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, getFirestore } from "firebase-admin/firestore";

const USERS = "notetakerUsers";

function fail(message) {
  console.error(`Error: ${message}`);
  process.exit(1);
}

function argsOf(argv) {
  const flags = new Set(["deactivate", "activate", "reset-password", "help"]);
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith("--")) fail(`Unexpected argument: ${arg}`);
    const key = arg.slice(2);
    if (key === "password") fail("Passwords are never passed as arguments; you will be asked for one.");
    if (flags.has(key)) {
      out[key] = true;
    } else {
      const value = argv[i + 1];
      if (value === undefined || value.startsWith("--")) fail(`--${key} needs a value`);
      out[key] = value;
      i++;
    }
  }
  return out;
}

/** Reads a line without echoing it. */
function askHidden(question) {
  const stdin = process.stdin;
  if (!stdin.isTTY) fail("Run this in a terminal: the password is typed at a hidden prompt.");
  return new Promise((resolve) => {
    process.stdout.write(question);
    stdin.setRawMode(true);
    stdin.resume();
    stdin.setEncoding("utf8");
    let value = "";
    const done = () => {
      stdin.setRawMode(false);
      stdin.pause();
      stdin.removeListener("data", onData);
      process.stdout.write("\n");
      resolve(value);
    };
    const onData = (chunk) => {
      for (const ch of chunk) {
        if (ch === "\r" || ch === "\n" || ch === "\u0004") return done();
        if (ch === "\u0003") {
          stdin.setRawMode(false);
          process.stdout.write("\n");
          process.exit(130);
        }
        if (ch === "\u007f" || ch === "\b") value = value.slice(0, -1);
        else value += ch;
      }
    };
    stdin.on("data", onData);
  });
}

async function newPassword() {
  const first = await askHidden("Password (at least 6 characters): ");
  if (first.length < 6) fail("The password must be at least 6 characters.");
  const second = await askHidden("Password again: ");
  if (first !== second) fail("The passwords do not match.");
  return first;
}

const args = argsOf(process.argv.slice(2));
if (args.help || !args.key || !args.email) {
  console.log(
    "Usage: node scripts/add-user.mjs --key <service-account.json> --email <email> [--name <name>] [--role admin|member]\n" +
      "       [--reset-password] [--deactivate | --activate]",
  );
  process.exit(args.help ? 0 : 1);
}
if (args.deactivate && args.activate) fail("Pick one of --deactivate and --activate.");
if (args.role !== undefined && !["admin", "member"].includes(args.role)) fail("--role must be admin or member.");

let serviceAccount;
try {
  serviceAccount = JSON.parse(readFileSync(args.key, "utf8"));
} catch (err) {
  fail(`Could not read the service account key: ${err.message}`);
}
initializeApp({ credential: cert(serviceAccount), projectId: serviceAccount.project_id });
const auth = getAuth();
const db = getFirestore();
const email = String(args.email).trim().toLowerCase();

let user = await auth.getUserByEmail(email).catch((err) => {
  if (err.code === "auth/user-not-found") return null;
  throw err;
});

if (args.deactivate) {
  if (!user) fail(`No login for ${email}.`);
  await db.collection(USERS).doc(user.uid).set({ active: false, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  // Signed-in phones and browsers lose access at their next call (the API checks `active`).
  await auth.revokeRefreshTokens(user.uid);
  console.log(`Deactivated ${email} (${user.uid}).`);
  process.exit(0);
}

let created = false;
if (!user) {
  if (!args.name || !args.role) fail("A new login needs --name and --role.");
  const password = await newPassword();
  user = await auth.createUser({ email, password, displayName: args.name, emailVerified: true });
  created = true;
} else {
  if (args["reset-password"]) await auth.updateUser(user.uid, { password: await newPassword() });
  if (args.name && args.name !== user.displayName) await auth.updateUser(user.uid, { displayName: args.name });
}

const ref = db.collection(USERS).doc(user.uid);
const existing = (await ref.get()).data();
if (!existing && !args.role) fail("This login has no profile yet: pass --role (and --name).");
const name = args.name ?? existing?.name ?? user.displayName ?? email;
const role = args.role ?? existing?.role ?? "member";
// A deactivated login stays off unless --activate says otherwise.
const active = args.activate ? true : existing ? existing.active !== false : true;
await ref.set(
  {
    name,
    email,
    role,
    active,
    ...(existing ? { updatedAt: FieldValue.serverTimestamp() } : { createdAt: FieldValue.serverTimestamp() }),
  },
  { merge: true },
);

const what = created ? "Created" : args.activate ? "Reactivated" : "Updated";
console.log(`${what} ${email} (${user.uid}): ${name}, ${role}${active ? "" : ", inactive"}.${args["reset-password"] ? " Password changed." : ""}`);
process.exit(0);
