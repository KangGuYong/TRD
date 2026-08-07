import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // 콘솔 API 프록시 (세션 쿠키 전달)
    proxy: { "/admin": { target: "http://localhost:8080", changeOrigin: true } },
  },
});
