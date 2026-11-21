/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

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

// O tom pode ser informado pela tela ou derivado do status, mantendo a apresentação
// reutilizável entre os fluxos.
export default function Badge({ status, tone, children, className = '', ...props }) {
  return <span className={`badge badge--${tone || TONES[status] || 'neutral'} ${className}`.trim()} {...props}>{children || status}</span>;
}
