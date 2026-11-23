/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router/dom';
import { createAppRouter } from './routes';
import './styles/global.css';

// Cria o roteador uma vez antes da montagem para compartilhar a mesma navegação entre
// as telas.
const router = createAppRouter();

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
