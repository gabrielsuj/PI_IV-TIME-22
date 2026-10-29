export default function Alert({ children, tone = 'danger', action }) {
  return (
    <div className={`alert alert--${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <div>{children}</div>
      {action}
    </div>
  );
}
