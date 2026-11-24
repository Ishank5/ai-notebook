import { createContext, useContext, useEffect, useState } from "react";
import { onAuthStateChanged, sendPasswordResetEmail, signInWithEmailAndPassword, signOut as fbSignOut } from "firebase/auth";
import { auth } from "../firebase";
import { poll } from "./poll";

// eslint-disable-next-line react-refresh/only-export-components
export const ROLES = { admin: "Admin", member: "Member" };

const AuthContext = createContext(null);
// eslint-disable-next-line react-refresh/only-export-components
export const useAuth = () => useContext(AuthContext);

/**
 * Firebase says who is signed in; GET /api/me says what they may do.
 * status: "loading" | "signed_out" | "no_profile" | "inactive" | "unavailable" | "ready"
 */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(undefined);
  const [me, setMe] = useState({ uid: null, profile: null, blocked: null });

  useEffect(() => onAuthStateChanged(auth, setUser), []);

  useEffect(() => {
    if (!user) return undefined;
    const uid = user.uid;
    return poll(
      "/api/me",
      (profile) => setMe({ uid, profile, blocked: null }),
      (err) => setMe({ uid, profile: null, blocked: err.code === "no_profile" || err.code === "inactive" ? err.code : "unavailable" }),
      60000,
    );
  }, [user]);

  const current = user && me.uid === user.uid ? me : { profile: null, blocked: null };
  let status = "loading";
  if (user === null) status = "signed_out";
  else if (user && current.blocked) status = current.blocked;
  else if (user && current.profile) status = "ready";

  const profile = status === "ready" ? current.profile : null;
  const value = {
    status,
    user,
    profile,
    // For showing buttons and pages; the API decides.
    can: (permission) => Boolean(profile?.permissions?.includes(permission)),
    signIn: (email, password) => signInWithEmailAndPassword(auth, email.trim(), password),
    signOut: () => fbSignOut(auth),
    resetPassword: (email) => sendPasswordResetEmail(auth, email.trim()),
  };
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function authErrorMessage(err) {
  const code = err?.code || "";
  if (/invalid-credential|wrong-password|user-not-found|invalid-login/.test(code)) return "Email or password is incorrect.";
  if (code.includes("user-disabled")) return "This account is turned off.";
  if (code.includes("too-many-requests")) return "Too many attempts. Wait a minute and try again.";
  if (code.includes("invalid-email")) return "That email address is not valid.";
  if (code.includes("network-request-failed")) return "No connection. Check the network and try again.";
  return "Something went wrong. Try again.";
}
