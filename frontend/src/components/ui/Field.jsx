import { useId } from 'react';

export default function Field({ label, error, hint, as: Control = 'input', id, className = '', required, children, ...props }) {
  const generatedId = useId();
  const controlId = id || generatedId;
  const descriptionId = `${controlId}-description`;

  return (
    <div className={`field ${className}`.trim()}>
      <label className="field__label" htmlFor={controlId}>
        {label}{required && <span aria-hidden="true"> *</span>}
      </label>
      <Control
        {...props}
        id={controlId}
        required={required}
        className={`field__control${Control === 'textarea' ? ' field__control--textarea' : ''}`}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || hint ? descriptionId : undefined}
      >
        {children}
      </Control>
      {(error || hint) && <p id={descriptionId} className={error ? 'field__error' : 'field__hint'}>{error || hint}</p>}
    </div>
  );
}
