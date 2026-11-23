/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { Link } from 'react-router';

export default function Button({ children, variant = 'primary', size = 'md', loading = false, disabled, className = '', to, state, type = 'button', ...props }) {
  const classes = `button button--${variant} button--${size} ${className}`.trim();

  // A navegação vira link quando está disponível; estados bloqueados permanecem como
  // botão para impedir a abertura do destino.
  if (to && !disabled && !loading) {
    return <Link to={to} state={state} className={classes} {...props}>{children}</Link>;
  }

  return (
    <button type={type} className={classes} disabled={disabled || loading} aria-busy={loading || undefined} {...props}>
      {loading && <span className="button__spinner" aria-hidden="true" />}
      {children}
    </button>
  );
}
