/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { useId } from 'react';

export default function Card({ title, description, action, children, headingLevel = 2, className = '', ...props }) {
  const titleId = useId();
  // O nível configurável preserva a hierarquia de títulos quando o card aparece dentro
  // de outra seção.
  const Heading = `h${headingLevel}`;

  return (
    <section className={`card ${className}`.trim()} aria-labelledby={title ? titleId : undefined} {...props}>
      {(title || action) && (
        <header className="card__header">
          <div>
            {title && <Heading id={titleId} className="card__title">{title}</Heading>}
            {description && <p className="card__description">{description}</p>}
          </div>
          {action}
        </header>
      )}
      <div className="card__content">{children}</div>
    </section>
  );
}
