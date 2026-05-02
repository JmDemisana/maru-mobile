import { resolve } from "node:path";
import { defineConfig } from "vite";

export default defineConfig({
  root: resolve(__dirname, "helper-web"),
  base: "./",
  build: {
    outDir: resolve(__dirname, "helper-dist"),
    emptyOutDir: true,
    sourcemap: false,
    target: "es2020",
    assetsDir: ".",
  },
  publicDir: false,
});
