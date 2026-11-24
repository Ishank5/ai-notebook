// After `vite build`: put the latest Android APK (from ../dist-release, not in git) on the site at /download/.
import { copyFileSync, existsSync, mkdirSync, readdirSync } from "node:fs";

const from = new URL("../../dist-release/", import.meta.url);
const apks = existsSync(from) ? readdirSync(from).filter((f) => f.endsWith(".apk")).sort() : [];
if (apks.length === 0) {
  console.log("copy-apk: no APK in dist-release, skipped");
} else {
  const to = new URL("../dist/download/", import.meta.url);
  mkdirSync(to, { recursive: true });
  copyFileSync(new URL(apks.at(-1), from), new URL("AI-Notebook.apk", to));
  console.log(`copy-apk: ${apks.at(-1)} -> dist/download/AI-Notebook.apk`);
}
