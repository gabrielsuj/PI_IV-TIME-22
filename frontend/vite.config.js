import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  // Lê o .env da raiz do repositório, o mesmo usado pelo backend.
  // Apenas variáveis com prefixo VITE_ são expostas ao navegador.
  envDir: '..',
  server: {
    port: 5173,
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/__tests__/setup.js',
    // Os testes não dependem do .env local.
    env: {
      VITE_API_BASE_URL: 'http://api.teste',
    },
  },
});
