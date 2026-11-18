import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { encerrarRascunhos } from '../services/rascunhoPacientes';
import { encerrarRascunhoAgendamento } from '../services/rascunhoAgendamento';

afterEach(() => {
  cleanup();
  encerrarRascunhos();
  encerrarRascunhoAgendamento();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
