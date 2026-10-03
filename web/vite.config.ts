import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// 工程结构见 web/README.md（既有规划，本配置照图施工）。
//
// base './'：构建产物用相对路径——可直接由后端的静态目录或任意 nginx 托管，
// 不绑定部署域名。dev 代理 /api 与 /admin 到本地应用（server.port 的默认 8080；
// 本地起服务时按实际端口调整）。
export default defineConfig({
  plugins: [vue()],
  base: './',
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/admin': 'http://127.0.0.1:8080',
    },
  },
  build: {
    outDir: 'dist',
  },
});
