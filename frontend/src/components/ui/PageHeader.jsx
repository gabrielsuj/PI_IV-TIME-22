export default function PageHeader({ title, description, action }) {
  return (
    <header className="page__header">
      <div>
        <h1>{title}</h1>
        {description && <p className="page__lead">{description}</p>}
      </div>
      {action}
    </header>
  );
}
