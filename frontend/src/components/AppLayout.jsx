/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { useRef } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { itensDoMenu, podeAbrir } from '../data/menu';
import { useAuth } from '../hooks/useAuth';
import AccessDeniedPage from '../pages/AccessDeniedPage';
import UserMenu from './UserMenu';
import { retornoPainel } from '../utils/painel';
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


export default function AppLayout() {
  const { usuario } = useAuth();
  const location = useLocation();
  const { pathname } = location;
  // O endereço inicial recupera os filtros do painel da mesma conta, preservando o
  // contexto ao voltar de outra tela.
  const inicio = retornoPainel(location, usuario) || '/';
  const conteudo = useRef(null);
  
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
    <div className={`app-shell${pathname === '/' ? ' app-shell--inicio' : ''}`}>
      <a className="skip-link" href="#conteudo-principal" onClick={(event) => { event.preventDefault(); conteudo.current?.focus(); }}>
        Ir para o conteúdo principal
      </a>
      <header className="app-header">
        <div className="app-header__inner">
          <NavLink to={inicio} className="app-brand">
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
                  <NavLink to={to === '/' ? inicio : to} end={end} className="app-nav__link">
                    {Icone && <Icone size={18} aria-hidden="true" />}
                    <span>{label}</span>
                  </NavLink>
                </li>
              );
            })}
          </ul>
        </nav>
        <main className="app-main" id="conteudo-principal" tabIndex={-1} ref={conteudo}>
          {podeAbrir(pathname, usuario?.perfil) ? <Outlet /> : <AccessDeniedPage />}
        </main>
      </div>
    </div>
  );
}
