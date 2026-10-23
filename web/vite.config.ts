/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Em desenvolvimento (npm run dev), /api vai para o Caddy do Docker: o mesmo caminho da
// produção, com o cookie da sessão e os limites de verdade. O certificado é o da CA interna
// do Caddy, por isso secure: false (só aqui, na máquina de quem desenvolve).
const httpsPorta = process.env.HTTPS_PORTA ?? '443';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: `https://localhost:${httpsPorta}`, changeOrigin: true, secure: false },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/testes.setup.ts'],
  },
});
