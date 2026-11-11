import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from '../hooks/useAuth';

/** Envolve as rotas que exigem login: sem sessão, manda para /login lembrando a página pedida. */
export default function RequireAuth() {
  const { usuario } = useAuth();
  const location = useLocation();
  if (!usuario) return <Navigate to="/login" replace state={{ from: location }} />;
  return <Outlet />;
}
