import { useState } from "react";
import { Eye, EyeOff, Loader2, Smartphone } from "lucide-react";
import { LogoMark } from "../components/Logo";
import { authErrorMessage, useAuth } from "../lib/auth";

/** A centred card under the logo, for signing in and for the screens before the app. */
function Frame({ children }) {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center px-4 py-10">
      <div className="mb-8 flex flex-col items-center gap-3 text-center">
        <LogoMark size={52} />
        <div>
          <div className="text-xl font-extrabold tracking-tight">AI Notebook</div>
          <div className="text-sm font-medium text-ink-faint">Notes, tasks and answers from everything you record</div>
        </div>
      </div>
      <div className="card w-full max-w-sm p-6 sm:p-8">{children}</div>
      <a
        href="/download/AI-Notebook.apk"
        className="mt-6 inline-flex items-center gap-2 rounded-full px-4 py-2 text-sm font-semibold text-primary hover:bg-primary-tint"
      >
        <Smartphone size={16} aria-hidden="true" />
        Get the Android app
      </a>
    </div>
  );
}

export default function SignIn() {
  const { signIn, resetPassword } = useAuth();
  const [mode, setMode] = useState("signin"); // signin | reset
  const [form, setForm] = useState({ email: "", password: "" });
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const set = (k) => (e) => { setForm({ ...form, [k]: e.target.value }); setError(""); };
  const go = (m) => { setMode(m); setError(""); setNotice(""); };

  async function submit(e) {
    e.preventDefault();
    setBusy(true); setError(""); setNotice("");
    try {
      if (mode === "signin") await signIn(form.email, form.password);
      else { await resetPassword(form.email); setNotice("If this email has an account, a reset link is on its way."); }
    } catch (err) {
      setError(authErrorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Frame>
      <h1 className="text-xl sm:text-xl">{mode === "signin" ? "Sign in" : "Reset password"}</h1>
      <form onSubmit={submit} className="mt-6 flex flex-col gap-4" key={mode}>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="email" className="label">Email</label>
          <input id="email" type="email" className="field h-11" value={form.email} onChange={set("email")}
            autoComplete="email" inputMode="email" placeholder="you@example.com" required autoFocus />
        </div>
        {mode === "signin" && (
          <div className="flex flex-col gap-1.5">
            <div className="flex items-center justify-between">
              <label htmlFor="password" className="label">Password</label>
              <button type="button" onClick={() => go("reset")} className="text-xs font-semibold text-primary hover:text-primary-pressed">
                Forgot password?
              </button>
            </div>
            <div className="relative">
              <input id="password" type={show ? "text" : "password"} className="field h-11 pr-12" value={form.password}
                onChange={set("password")} autoComplete="current-password" required />
              <button type="button" onClick={() => setShow(!show)} aria-label={show ? "Hide password" : "Show password"}
                className="absolute inset-y-0 right-0 flex w-12 items-center justify-center rounded-full text-ink-faint hover:text-ink">
                {show ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
              </button>
            </div>
          </div>
        )}

        {error && <p role="alert" className="rounded-2xl bg-record-tint px-4 py-2.5 text-sm font-medium text-record">{error}</p>}
        {notice && <p className="rounded-2xl bg-primary-tint px-4 py-2.5 text-sm font-medium text-primary">{notice}</p>}

        <button type="submit" disabled={busy} className="btn-primary mt-2 h-11 w-full">
          {busy && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
          {mode === "signin" ? "Sign in" : "Send reset link"}
        </button>
        {mode === "reset" && (
          <button type="button" onClick={() => go("signin")} className="btn-ghost w-full">Back to sign in</button>
        )}
      </form>
    </Frame>
  );
}

const BLOCKED = {
  no_profile: ["No access", "This account has no access to AI Notebook."],
  inactive: ["Access paused", "This account is turned off."],
  unavailable: ["Cannot reach the server", "Try again in a moment."],
};

/** Signed in, but /api/me said no (or could not be reached). */
export function NoAccess({ status }) {
  const { user, signOut } = useAuth();
  const [title, body] = BLOCKED[status] ?? BLOCKED.no_profile;
  return (
    <Frame>
      <h1 className="text-xl sm:text-xl">{title}</h1>
      <p className="mt-2 text-sm text-ink-muted">{body}</p>
      {user?.email && <p className="mt-4 truncate rounded-full bg-surface-alt px-4 py-2 text-sm font-medium">{user.email}</p>}
      <div className="mt-6 flex flex-col gap-2">
        {status === "unavailable" && <button type="button" onClick={() => window.location.reload()} className="btn-primary w-full">Try again</button>}
        <button type="button" onClick={signOut} className={status === "unavailable" ? "btn-ghost w-full" : "btn-primary w-full"}>Sign out</button>
      </div>
    </Frame>
  );
}

/** While Firebase restores a saved session and /api/me answers. */
export function Splash() {
  return (
    <div className="flex min-h-dvh items-center justify-center">
      <LogoMark size={44} className="animate-pulse" />
    </div>
  );
}
