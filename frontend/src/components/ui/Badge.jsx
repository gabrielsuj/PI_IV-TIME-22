const TONES = {
  Pendente: 'warning',
  Confirmada: 'teal',
  'Reagendamento solicitado': 'info',
  Cancelada: 'danger',
  Concluída: 'success',
  'Não compareceu': 'neutral',
  Ativo: 'success',
  Inativo: 'neutral',
  loading: 'neutral',
  online: 'success',
  offline: 'danger',
};

export default function Badge({ status, tone, children, className = '', ...props }) {
  return <span className={`badge badge--${tone || TONES[status] || 'neutral'} ${className}`.trim()} {...props}>{children || status}</span>;
}
