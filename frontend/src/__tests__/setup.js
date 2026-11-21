/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { encerrarRascunhos } from '../services/rascunhoPacientes';
import { encerrarRascunhoAgendamento } from '../services/rascunhoAgendamento';

// Limpa a interface, os rascunhos e os mocks após cada cenário para que o estado de
// uma conta não alcance o teste seguinte.
afterEach(() => {
  cleanup();
  encerrarRascunhos();
  encerrarRascunhoAgendamento();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
