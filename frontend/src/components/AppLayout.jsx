import { NavLink, Outlet, useLocation } from 'react-router';
import { itensDoMenu, podeAbrir } from '../data/menu';
import { useAuth } from '../hooks/useAuth';
import AccessDeniedPage from '../pages/AccessDeniedPage';
import UserMenu from './UserMenu';

export default function AppLayout() {
  const { usuario } = useAuth();
  const { pathname } = useLocation();

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-header__inner">
          <NavLink to="/" className="app-brand">
            <span className="app-brand__mark" aria-hidden="true">+</span>
            <span>ConsultaFácil<small>Agendamento de consultas médicas</small></span>
          </NavLink>
          <UserMenu />
        </div>
      </header>
      <div className="app-body">
        <nav className="app-sidebar" aria-label="Navegação principal">
          <ul className="app-nav">
            {itensDoMenu(usuario?.perfil).map(({ to, label, end }) => (
              <li key={to}>
                <NavLink to={to} end={end} className="app-nav__link">
                  {label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <main className="app-main">
          {podeAbrir(pathname, usuario?.perfil) ? <Outlet /> : <AccessDeniedPage />}
        </main>
      </div>
    </div>
  );
}
