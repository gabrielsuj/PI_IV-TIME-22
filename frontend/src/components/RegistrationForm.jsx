import { useState } from 'react';
import { useRecords } from '../hooks/useRecords';
import { api } from '../services/api';
import Alert from './ui/Alert';
import Button from './ui/Button';
import Field from './ui/Field';
import Modal from './ui/Modal';
import CheckboxGroup from './ui/CheckboxGroup';

function SpecialtyField({ field, value, error, onChange, disabled }) {
  const { records, loading, error: loadError, refresh } = useRecords(field.source);
  return (
    <div className={field.wide ? 'form-grid__wide' : ''}>
      <CheckboxGroup label={field.label} name={field.name} value={value} required={field.required} disabled={disabled || loading || Boolean(loadError)} onChange={onChange} error={error} options={records.filter((record) => record.ativa !== false || value.includes(record.id || record._id)).map((record) => ({ value: record.id || record._id, label: record.nome }))} />
      {loading && <p className="field__hint" role="status">Carregando especialidades…</p>}
      {loadError && <Alert action={<Button variant="outline" size="sm" onClick={refresh}>Tentar novamente</Button>}>Não foi possível carregar as especialidades.</Alert>}
      {!loading && !loadError && records.length === 0 && <p className="field__hint">Cadastre uma especialidade antes de cadastrar o médico.</p>}
    </div>
  );
}

export default function RegistrationForm({ resource, schema, record, onClose, onSaved }) {
  const [values, setValues] = useState(() => Object.fromEntries(schema.fields.map((field) => {
    const value = record?.[field.name] ?? field.defaultValue ?? '';
    return [field.name, Array.isArray(value) ? value : String(value)];
  })));
  const [errors, setErrors] = useState({});
  const [failure, setFailure] = useState('');
  const [saving, setSaving] = useState(false);

  function change(event) {
    const { name, value } = event.target;
    setValues((current) => ({ ...current, [name]: value }));
    setErrors((current) => ({ ...current, [name]: undefined }));
  }

  async function submit(event) {
    event.preventDefault();
    const nextErrors = {};
    for (const field of schema.fields) {
      const value = values[field.name];
      if (field.required && (Array.isArray(value) ? value.length === 0 : !value.trim())) nextErrors[field.name] = 'Preencha este campo.';
      if (field.type === 'email' && values[field.name] && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(values[field.name])) nextErrors[field.name] = 'Informe um e-mail válido.';
      if (field.pattern && value && !new RegExp(`^(?:${field.pattern})$`).test(value.trim())) nextErrors[field.name] = field.patternMessage;
    }
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) {
      event.currentTarget.querySelector(`[name="${Object.keys(nextErrors)[0]}"]`)?.focus();
      return;
    }
    setSaving(true);
    setFailure('');
    const payload = Object.fromEntries(schema.fields.map((field) => {
      const value = values[field.name];
      return [field.name, field.dataType === 'boolean' ? value === 'true' : Array.isArray(value) ? value : value.trim()];
    }));
    try {
      if (record) await api.put(`/${resource}/${encodeURIComponent(record.id || record._id)}`, payload);
      else await api.post(`/${resource}`, payload);
      onSaved(record ? 'Cadastro atualizado.' : 'Cadastro realizado.');
    } catch (error) {
      setFailure(error.message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title={record ? `Editar ${schema.singular}` : schema.createLabel} description={schema.formDescription} busy={saving} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <p className="form-note">Campos com * são obrigatórios.</p>
        <div className="form-grid">
          {schema.fields.map(({ name, label, wide, options, source, defaultValue, ...props }) => {
            delete props.dataType;
            delete props.patternMessage;
            return source ? (
              <SpecialtyField key={name} field={{ name, label, wide, source, ...props }} value={values[name]} onChange={change} error={errors[name]} disabled={saving} />
            ) : (
              <Field key={name} {...props} name={name} label={label} className={wide ? 'form-grid__wide' : ''} as={options ? 'select' : props.as} value={values[name] ?? defaultValue ?? ''} onChange={change} disabled={saving} error={errors[name]}>
                {options?.map((option) => <option key={option.value ?? option} value={option.value ?? option}>{option.label ?? option}</option>)}
              </Field>
            );
          })}
        </div>
        {failure && <Alert>{failure}</Alert>}
        <footer className="form-actions">
          <Button variant="outline" onClick={onClose} disabled={saving}>Cancelar</Button>
          <Button type="submit" loading={saving}>{saving ? 'Salvando…' : 'Salvar'}</Button>
        </footer>
      </form>
    </Modal>
  );
}
