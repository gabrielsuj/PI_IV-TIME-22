import { Link } from 'react-router';

export default function Button({ children, variant = 'primary', size = 'md', loading = false, disabled, className = '', to, type = 'button', ...props }) {
  const classes = `button button--${variant} button--${size} ${className}`.trim();

  if (to && !disabled && !loading) {
    return <Link to={to} className={classes} {...props}>{children}</Link>;
  }

  return (
    <button type={type} className={classes} disabled={disabled || loading} aria-busy={loading || undefined} {...props}>
      {loading && <span className="button__spinner" aria-hidden="true" />}
      {children}
    </button>
  );
}
