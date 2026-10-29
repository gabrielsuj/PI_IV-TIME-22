import { useId } from 'react';

export default function Card({ title, description, action, children, className = '', ...props }) {
  const titleId = useId();

  return (
    <section className={`card ${className}`.trim()} aria-labelledby={title ? titleId : undefined} {...props}>
      {(title || action) && (
        <header className="card__header">
          <div>
            {title && <h2 id={titleId} className="card__title">{title}</h2>}
            {description && <p className="card__description">{description}</p>}
          </div>
          {action}
        </header>
      )}
      <div className="card__content">{children}</div>
    </section>
  );
}
