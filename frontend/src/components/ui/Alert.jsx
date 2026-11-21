/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

// Falhas usam anúncio de alerta; mensagens informativas usam status para comunicar a
// mudança sem a mesma urgência.
export default function Alert({ children, tone = 'danger', action, ...props }) {
  return (
    <div {...props} className={`alert alert--${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <div>{children}</div>
      {action}
    </div>
  );
}
