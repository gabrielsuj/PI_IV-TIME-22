import { createBrowserRouter } from 'react-router';
import AppLayout from '../components/AppLayout';
import ErrorPage from '../pages/ErrorPage';
import HomePage from '../pages/HomePage';
import NotFoundPage from '../pages/NotFoundPage';
import StatusPage from '../pages/StatusPage';
import LoginPage from '../pages/LoginPage';
import ProfilesPage from '../pages/ProfilesPage';
import RegistrationPage from '../pages/RegistrationPage';

/**
 * Tabela de rotas da aplicação. Novas telas entram como filhas de AppLayout
 * para herdar o cabeçalho e a navegação.
 */
export const routes = [
  {
    path: '/',
    element: <AppLayout />,
    errorElement: <ErrorPage />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'status', element: <StatusPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'perfis', element: <ProfilesPage /> },
      { path: 'pacientes', element: <RegistrationPage key="pacientes" resource="pacientes" /> },
      { path: 'medicos', element: <RegistrationPage key="medicos" resource="medicos" /> },
      { path: 'especialidades', element: <RegistrationPage key="especialidades" resource="especialidades" /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(routes);
}
