import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// The app is served under this URL path prefix in production (the shared Traefik proxy
// routes /learninggoalhub/* to this app and strips the prefix). `base` makes Vite emit
// asset URLs under it; the client's router basename and API base use the same prefix.
// Keep dev and prod identical so paths behave the same in both.
const BASE_PATH = "/learninggoalhub";

// The Spring Boot server runs on :8080. The dev server proxies the prefixed API path to
// it (stripping the prefix, exactly like Traefik does in prod) so the browser talks to a
// single origin and we avoid CORS configuration during development.
const API_TARGET = process.env.VITE_API_TARGET ?? "http://localhost:8080";

export default defineConfig({
  base: `${BASE_PATH}/`,
  plugins: [react(), tailwindcss()],
  server: {
    // Bind all interfaces so both IPv4 (127.0.0.1) and IPv6 (::1) localhost resolve.
    host: true,
    port: 5173,
    proxy: {
      [`${BASE_PATH}/api`]: {
        target: API_TARGET,
        changeOrigin: true,
        rewrite: (path) => path.replace(new RegExp(`^${BASE_PATH}`), ""),
      },
    },
  },
});
