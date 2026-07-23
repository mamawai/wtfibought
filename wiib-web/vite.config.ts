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
        // 不锁竖屏：K 线横屏看是真实需求，且横屏宽度(如 852px)已过 md 断点，自动切桌面版布局
        orientation: 'any',
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
        // 不含 html：index.html 一旦进 precache，连同 navigateFallback 会把所有导航
        // 从缓存直接喂旧壳，压根不发请求——发版后服务器都新了，用户还看旧页面，
        // 而且旧壳引用的旧 hash chunk 也在缓存里，不报错不白屏，问题完全看不出来
        globPatterns: ['**/*.{js,css,woff2,png,ico}'],
        // 主包 2.2MB 已超 Workbox 默认 2MiB 门槛，不放宽会被静默剔出 precache
        maximumFileSizeToCacheInBytes: 4 * 1024 * 1024,
        // 必须显式关：插件默认 navigateFallback='index.html'，不写照样注册 NavigationRoute，
        // 而且排在下面 runtimeCaching 前面先匹配先赢，等于白改；它还绑着已不在 precache 的
        // index.html，SW 一启动就抛错
        navigateFallback: undefined,
        runtimeCaching: [
          {
            // 页面导航走网络优先：每次开都拿最新壳，发版刷一次就生效。
            // 断网时 fetch 立刻失败直接回退缓存，3s 只用来兜"网慢没断"。
            // 代价：离线深链到从没在线打开过的路由会白屏——本站离线本就只是个壳，认了。
            // /api /ws 不是 navigate 请求天然不匹配，这里挡的是地址栏直接敲接口地址的情况
            urlPattern: ({ request, url }) =>
              request.mode === 'navigate' && !url.pathname.startsWith('/api') && !url.pathname.startsWith('/ws'),
            handler: 'NetworkFirst',
            options: {
              cacheName: 'html-shell',
              networkTimeoutSeconds: 3,
              expiration: { maxEntries: 20, maxAgeSeconds: 7 * 24 * 60 * 60 },
            },
          },
        ],
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
