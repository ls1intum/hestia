import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";
import path from "path";

// The landing page is the root catch-all of the HESTIA VMs: Traefik routes the bare
// host (no path prefix) here, so the base stays "/" in every mode.
export default defineConfig({
  base: "/",
  server: {
    host: "::",
    port: 8090,
  },
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
});
