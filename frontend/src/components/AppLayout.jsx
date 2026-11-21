import { NavLink, Outlet, useLocation } from 'react-router';
import { itensDoMenu, podeAbrir } from '../data/menu';
import { useAuth } from '../hooks/useAuth';
import AccessDeniedPage from '../pages/AccessDeniedPage';
import UserMenu from './UserMenu';
import {
  House,
  Bell,
  CalendarPlus,
  ClipboardList,
  ListOrdered,
  Users,
  Stethoscope,
  CalendarDays,
  Activity,
  CalendarCheck2, 
  ContactRound
} from 'lucide-react';

/**
 * @author Vinicius (criador)
 * @author Gabriel (modificador)
 * @author Murilo Rigoni (modificador)
 */

export default function AppLayout() {
  const { usuario } = useAuth();
  const { pathname } = useLocation();
  
 const icones = {
  '/': House,
  '/nova-consulta': CalendarPlus,
  '/consultas': ClipboardList,
  '/fila-espera': ListOrdered,
  '/avisos-vaga': Bell,
  '/notificacoes': Bell,
  '/pacientes': Users,
  '/medicos': Stethoscope,
  '/agenda': CalendarCheck2,
  '/especialidades': Activity,
  '/calendario': CalendarDays,
  '/perfis':ContactRound,
};

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
            {itensDoMenu(usuario?.perfil).map(({ to, label, end }) => {
              const Icone = icones[to];

              return (
                <li key={to}>
                  <NavLink to={to} end={end} className="app-nav__link">
                    {Icone && <Icone size={18} aria-hidden="true" />}
                    <span>{label}</span>
                  </NavLink>
                </li>
              );
            })}
          </ul>
        </nav>
        <main className="app-main">
          {podeAbrir(pathname, usuario?.perfil) ? <Outlet /> : <AccessDeniedPage />}
        </main>
      </div>
    </div>
  );
}
