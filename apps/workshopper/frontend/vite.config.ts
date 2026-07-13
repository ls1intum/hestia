import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

const BASE_PATH = "/workshopper";
const API_TARGET = process.env.VITE_API_TARGET ?? "http://localhost:8081";

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
        rewrite: (path) => path.replace(new RegExp(`^${BASE_PATH}`), ""),
      },
      "/learninggoalhub/api": {
        target: process.env.VITE_LGH_TARGET ?? "http://localhost:8080",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/learninggoalhub/, ""),
      },
    },
  },
  optimizeDeps: {
    include: ["pdfjs-dist"],
  },
});
