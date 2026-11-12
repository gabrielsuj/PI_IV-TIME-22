import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { encerrarRascunhos } from '../services/rascunhoPacientes';

afterEach(() => {
  cleanup();
  encerrarRascunhos();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
