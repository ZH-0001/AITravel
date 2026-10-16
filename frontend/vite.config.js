import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: { rollupOptions: { output: { manualChunks: { 'vue-vendor': ['vue'], 'element-plus': ['element-plus'], icons: ['lucide-vue-next'] } } } },
  server: { port: 5173, strictPort: true, proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false } } },
  preview: { proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false } } },
})
