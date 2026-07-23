import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    // ===== PWA：可安装外壳 =====
    // 只离线静态壳，行情/接口一律不碾存——看盘看到旧价格比看不到更糟
    VitePWA({
      registerType: 'autoUpdate',   // 新版静默下载，下次导航生效，不弹提示打扰
      includeAssets: ['favicon.ico', 'apple-touch-icon-180.png'],
      manifest: {
        name: 'WhatIfIBought 虚拟交易',
        short_name: 'WIIB',
        description: '虚拟资金交易代币化美股、加密现货 / 永续合约与大宗商品，观察 AI 量化研判',
        lang: 'zh-CN',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'portrait',
        background_color: '#f6f6f4',  // 启动闪屏底色，对齐亮色主题 --color-background
        theme_color: '#F97316',
        icons: [
          { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
          // maskable：安卓自适应图标，内容已收进安全圆，随系统裁圆/方不会切字
          { src: '/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,woff2,png,ico}'],
        // 主包 2.2MB 已超 Workbox 默认 2MiB 门槛，不放宽会被静默剔出 precache
        maximumFileSizeToCacheInBytes: 4 * 1024 * 1024,
        navigateFallback: '/index.html',
        // 接口与 WebSocket 握手绝不能被当成页面导航喂 index.html
        navigateFallbackDenylist: [/^\/api/, /^\/ws/],
        runtimeCaching: [],           // 空：/api 全部 network-only，SW 不插手
        cleanupOutdatedCaches: true,
        clientsClaim: true,
        skipWaiting: true,
      },
      devOptions: { enabled: false }, // 开发期不注册 SW，免得缓存挡住热更新
    }),
  ],
  define: {
    global: 'globalThis',
  },
  server: {
    port: 3000,
    proxy: {
      // quant(8082) 的路径要先于兜底 /api 匹配，其余 /api 走 sim(8080)
      '/api/ai': 'http://localhost:8082',
      '/api/testnet': 'http://localhost:8082',
      '/api/admin/ai-agent': 'http://localhost:8082',
      '/api': 'http://localhost:8080',
      '/ws': { target: 'http://localhost:8080', ws: true },
    },
  },
})
