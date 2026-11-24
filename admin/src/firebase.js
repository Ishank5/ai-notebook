import { getApps, initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";

// Firebase web app config, from admin/.env.local (copy admin/.env.example). Never committed.
const env = import.meta.env;
const config = {
  apiKey: env.VITE_FIREBASE_API_KEY,
  authDomain: env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: env.VITE_FIREBASE_PROJECT_ID,
  storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: env.VITE_FIREBASE_MESSAGING_SENDER_ID,
  appId: env.VITE_FIREBASE_APP_ID,
};
if (!config.apiKey || !config.projectId) {
  throw new Error("Firebase config missing: copy admin/.env.example to admin/.env.local and fill it in.");
}

const app = getApps()[0] ?? initializeApp(config);
export const auth = getAuth(app);
