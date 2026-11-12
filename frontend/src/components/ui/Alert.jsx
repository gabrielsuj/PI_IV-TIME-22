export default function Alert({ children, tone = 'danger', action, ...props }) {
  return (
    <div {...props} className={`alert alert--${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <div>{children}</div>
      {action}
    </div>
  );
}
