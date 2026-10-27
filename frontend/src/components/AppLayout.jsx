import { NavLink, Outlet } from 'react-router';

const NAV_ITEMS = [
  { to: '/', label: 'Início', end: true },
  { to: '/status', label: 'Status do sistema' },
];

export default function AppLayout() {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-header__inner">
          <NavLink to="/" className="app-brand">
            ConsultaFácil
          </NavLink>
          <nav aria-label="Navegação principal">
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
        </div>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}
