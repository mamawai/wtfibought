import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  define: {
    global: 'globalThis',
  },
  server: {
    port: 3000,
    proxy: {
      // quant(8082) 的路径要先于兜底 /api 匹配，其余 /api 走 sim(8080)
      '/api/ai': 'http://localhost:8082',
      '/api/testnet': 'http://localhost:8082',
      '/api': 'http://localhost:8080',
      '/ws': { target: 'http://localhost:8080', ws: true },
    },
  },
})
