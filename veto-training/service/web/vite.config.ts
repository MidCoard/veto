import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
export default defineConfig({plugins:[react()],test:{environment:'jsdom',testTimeout:30000,setupFiles:['./src/test-setup.ts']},build:{sourcemap:false,chunkSizeWarningLimit:750}});
