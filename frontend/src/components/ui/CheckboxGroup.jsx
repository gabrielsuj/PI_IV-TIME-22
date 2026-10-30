import { useId } from 'react';

export default function CheckboxGroup({ label, name, options, value = [], onChange, disabled, required, error }) {
  const id = useId();
  return (
    <fieldset className="field checkbox-group" disabled={disabled} aria-describedby={error ? `${id}-error` : undefined} aria-invalid={error ? true : undefined}>
      <legend className="field__label">{label}{required && <span aria-hidden="true"> *</span>}</legend>
      <div className="checkbox-group__options">
        {options.map((option) => (
          <label key={option.value} className="checkbox-option">
            <input type="checkbox" name={name} value={option.value} checked={value.includes(option.value)} onChange={(event) => onChange({ target: { name, value: event.target.checked ? [...value, option.value] : value.filter((item) => item !== option.value) } })} />
            {option.label}
          </label>
        ))}
      </div>
      {error && <p className="field__error" id={`${id}-error`}>{error}</p>}
    </fieldset>
  );
}
