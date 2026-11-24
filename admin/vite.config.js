import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In production Hosting serves this app and rewrites /api/** to the ntApi function.
// In dev the same /api paths are proxied to the deployed function.
export default defineConfig({
  plugins: [react()],
  appType: "spa",
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "https://us-central1-team-akshay-sharma.cloudfunctions.net",
        changeOrigin: true,
        rewrite: (path) => `/ntApi${path}`,
      },
    },
  },
});
