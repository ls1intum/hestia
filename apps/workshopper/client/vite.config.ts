import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

const BASE_PATH = "/workshopper";
const API_TARGET = process.env.VITE_API_TARGET ?? "http://localhost:8080";

export default defineConfig({
  base: `${BASE_PATH}/`,
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 5173,
    proxy: {
      [`${BASE_PATH}/api`]: {
        target: API_TARGET,
        changeOrigin: true,
        timeout: 300000,
        proxyTimeout: 300000,
      },
      // Core's API (review list), reached like the Workshopper API under the base path
      [`${BASE_PATH}/core/api`]: {
        target: API_TARGET,
        changeOrigin: true,
        rewrite: (p: string) => p.slice(BASE_PATH.length),
      },
    },
  },
});
