import { createBrowserRouter, Outlet } from 'react-router';
import AppLayout from '../components/AppLayout';
import RequireAuth from '../components/RequireAuth';
import AuthProvider from '../contexts/AuthProvider';
import AgendaPage from '../pages/AgendaPage';
import CalendarioPage from '../pages/CalendarioPage';
import ErrorPage from '../pages/ErrorPage';
import HomePage from '../pages/HomePage';
import NotFoundPage from '../pages/NotFoundPage';
import NovaConsultaPage from '../pages/NovaConsultaPage';
import StatusPage from '../pages/StatusPage';
import LoginPage from '../pages/LoginPage';
import ProfilesPage from '../pages/ProfilesPage';
import RegistrationPage from '../pages/RegistrationPage';
import { buscarVagas, confirmarAgendamento } from '../services/agendamentoService';

/**
 * Tabela de rotas da aplicação.
 *
 * /login fica fora do layout (sem menu lateral). Todas as outras telas exigem
 * login (RequireAuth) e entram como filhas de AppLayout para herdar o
 * cabeçalho e a navegação.
 */
export const routes = [
  {
    element: (
      <AuthProvider>
        <Outlet />
      </AuthProvider>
    ),
    errorElement: <ErrorPage />,
    children: [
      { path: '/login', element: <LoginPage /> },
      {
        element: <RequireAuth />,
        children: [
          {
            path: '/',
            element: <AppLayout />,
            children: [
              { index: true, element: <HomePage /> },
              { path: 'status', element: <StatusPage /> },
              { path: 'perfis', element: <ProfilesPage /> },
              { path: 'pacientes', element: <RegistrationPage key="pacientes" resource="pacientes" /> },
              { path: 'medicos', element: <RegistrationPage key="medicos" resource="medicos" /> },
              { path: 'especialidades', element: <RegistrationPage key="especialidades" resource="especialidades" /> },
              { path: 'agenda', element: <AgendaPage /> },
              { path: 'calendario', element: <CalendarioPage /> },
              {
                path: 'nova-consulta',
                element: <NovaConsultaPage buscarVagas={buscarVagas} confirmarAgendamento={confirmarAgendamento} />,
              },
              { path: '*', element: <NotFoundPage /> },
            ],
          },
        ],
      },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(routes);
}
