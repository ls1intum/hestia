import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";
import path from "path";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
  // In production the app is served under /examlense/ (the shared Traefik proxy routes
  // /examlense/* to this app and strips the prefix). `base` makes Vite emit asset URLs
  // under it and drives the router basename (import.meta.env.BASE_URL). Local dev stays at
  // "/" so `npm run dev` and the http://localhost:8081 backend behave exactly as before.
  base: mode === "production" ? "/examlense/" : "/",
  server: {
    host: "::",
    port: 8080,
    hmr: {
      overlay: false,
    },
  },
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
    dedupe: ["react", "react-dom", "react/jsx-runtime", "react/jsx-dev-runtime", "@tanstack/react-query", "@tanstack/query-core"],
  },
}));
