import { NavLink, Outlet } from 'react-router';
import Button from './ui/Button';

const NAV_ITEMS = [
  { to: '/', label: 'Início', end: true },
  { to: '/pacientes', label: 'Pacientes' },
  { to: '/medicos', label: 'Médicos' },
  { to: '/especialidades', label: 'Especialidades' },
  { to: '/perfis', label: 'Perfis de acesso' },
  { to: '/status', label: 'Status do sistema' },
];

export default function AppLayout() {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-header__inner">
          <NavLink to="/" className="app-brand">
            <span className="app-brand__mark" aria-hidden="true">+</span>
            <span>ConsultaFácil<small>Agendamento de consultas médicas</small></span>
          </NavLink>
          <Button to="/login" variant="outline">Acessar conta</Button>
        </div>
      </header>
      <div className="app-body">
        <nav className="app-sidebar" aria-label="Navegação principal">
          <ul className="app-nav">
            {NAV_ITEMS.map(({ to, label, end }) => (
              <li key={to}>
                <NavLink to={to} end={end} className="app-nav__link">
                  {label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
