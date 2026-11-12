import { useEffect, useId, useRef } from 'react';
import Button from './Button';

export default function Modal({ title, description, onClose, busy = false, returnFocusRef, children }) {
  const ref = useRef(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    const dialog = ref.current;
    const previousFocus = document.activeElement;
    const fallbackFocus = returnFocusRef?.current;
    const previousOverflow = document.body.style.overflow;
    dialog.showModal();
    dialog.querySelector('input:not([disabled]), select:not([disabled]), textarea:not([disabled])')?.focus();
    document.body.style.overflow = 'hidden';
    return () => {
      dialog.close();
      document.body.style.overflow = previousOverflow;
      if (previousFocus?.isConnected && previousFocus !== document.body) previousFocus.focus();
      else if (fallbackFocus?.isConnected) fallbackFocus.focus();
    };
  }, [returnFocusRef]);

  return (
    <dialog
      ref={ref}
      className="modal"
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      onCancel={(event) => { event.preventDefault(); if (!busy) onClose(); }}
    >
      <header className="modal__header">
        <h2 id={titleId}>{title}</h2>
        {description && <p id={descriptionId}>{description}</p>}
        <Button variant="ghost" size="icon" className="modal__close" aria-label="Fechar" disabled={busy} onClick={onClose}>×</Button>
      </header>
      {children}
    </dialog>
  );
}
