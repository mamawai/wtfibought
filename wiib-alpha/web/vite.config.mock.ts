/**
 * 本地预览专用配置：npx vite --config vite.config.mock.ts，打开 http://localhost:3003/alpha/
 * 和主配置的区别——接口不转给 8084 的后端，由 mock/alphaMock.ts 回假数据；端口另开 3003
 */
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { alphaMock } from './mock/alphaMock';

export default defineConfig({
  base: '/alpha/',
  plugins: [react(), tailwindcss(), alphaMock()],
  server: { port: 3003 },
});
