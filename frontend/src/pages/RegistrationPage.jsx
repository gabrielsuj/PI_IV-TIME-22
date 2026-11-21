/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { useRef, useState } from 'react';
import { Link, useLocation } from 'react-router';
import { registrationSchemas } from '../data/registrationSchemas';
import { useRecords } from '../hooks/useRecords';
import { useAuth } from '../hooks/useAuth';
import { recuperarRascunho, removerRascunho } from '../services/rascunhoPacientes';
import RegistrationForm from '../components/RegistrationForm';
import Alert from '../components/ui/Alert';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import DataTable from '../components/ui/DataTable';
import Field from '../components/ui/Field';
import PageHeader from '../components/ui/PageHeader';

export default function RegistrationPage({ resource, searchState }) {
  const { usuario } = useAuth();
  const canEdit = usuario?.perfil === 'SECRETARIA_ADMIN';
  const location = useLocation();
  const rota = `${location.pathname}${location.search}`;
  const createButton = useRef(null);
  // Vincula o rascunho de paciente à conta e à rota, permitindo retomar o formulário
  // no contexto em que foi preenchido.
  const draftContext = resource === 'pacientes' ? { usuarioId: usuario?.id, rota } : null;
  const schema = registrationSchemas[resource];
  const { records, loading, error, refresh } = useRecords(resource);
  const localSearch = useState('');
  const [search, setSearch] = searchState ?? localSearch;
  const [editor, setEditor] = useState(() => canEdit && resource === 'pacientes' ? recuperarRascunho(usuario.id, rota) : null);
  const [notice, setNotice] = useState('');
  const normalize = (value) => String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  const visible = records.filter((record) => schema.searchKeys.some((key) => normalize(record[key]).includes(normalize(search))));
  const editAction = (record) => canEdit && <Button variant="ghost" size="sm" aria-label={`Editar ${record.nome}`} onClick={() => setEditor({ record })}>Editar</Button>;
  const columns = schema.columns?.map(({ badge, ...column }) => {
    if (resource === 'pacientes' && column.key === 'nome') {
      return { ...column, render: (record) => <Link to={encodeURIComponent(record.id || record._id)}>{record.nome}</Link> };
    }
    if (badge) return { ...column, render: (record) => <Badge tone="info">{record[column.key] || '—'}</Badge> };
    return column;
  });

  function saved(message) {
    if (draftContext) removerRascunho(usuario.id, rota);
    setNotice(message);
    setEditor(null);
    refresh();
  }

  function closeEditor() {
    if (draftContext) removerRascunho(usuario.id, rota);
    setEditor(null);
  }

  return (
    <div className="page">
      <PageHeader title={schema.title} description={schema.description} action={canEdit && <Button ref={createButton} onClick={() => { setNotice(''); setEditor({ record: null }); }}><span aria-hidden="true">＋</span>{schema.createLabel}</Button>} />
      {notice && <Alert tone="success">{notice}</Alert>}
      <Card className="search-card">
        <Field type="search" label={schema.searchLabel} placeholder={schema.searchLabel} value={search} onChange={(event) => setSearch(event.target.value)} className="search-field" />
      </Card>
      {schema.cards ? (
        <>
          {error && (
            <Alert action={error.status !== 403 && <Button variant="outline" size="sm" onClick={refresh}>Tentar novamente</Button>}>
              Não foi possível carregar os cadastros. {error.message}
            </Alert>
          )}
          {loading && <Card><p role="status">Carregando registros…</p></Card>}
          {!loading && !error && (visible.length === 0 ? <Card><p className="empty-state" role="status">Nenhum registro encontrado.</p></Card> : (
            <div className="record-grid">
              {visible.map((record) => (
                <Card key={record.id || record._id} className="record-card">
                  <div className="record-card__header">
                    <span className={`record-avatar${resource === 'especialidades' ? ' record-avatar--blue' : ''}`} aria-hidden="true">{record.nome?.slice(0, 2).toUpperCase()}</span>
                    {resource === 'medicos' && <Badge status={record.ativo === false ? 'Inativo' : 'Ativo'} />}
                    {resource === 'especialidades' && editAction(record)}
                  </div>
                  <h2 className="record-card__title">{record.nome}</h2>
                  {resource === 'medicos' ? (
                    <>
                      <p className="record-card__meta">CRM {record.crmNumero} / {record.crmUf}</p>
                      <p>{record.especialidadeIds?.length || 0} especialidade(s)</p>
                      <div className="record-card__actions">{editAction(record)}</div>
                    </>
                  ) : <><p>{record.codigo}</p><Badge status={record.ativa === false ? 'Inativo' : 'Ativo'} /></>}
                </Card>
              ))}
            </div>
          ))}
        </>
      ) : (
        <Card className="table-card">
          <DataTable caption="Pacientes cadastrados" columns={canEdit ? [...columns, { key: 'actions', label: 'Ações', align: 'right', render: editAction }] : columns} rows={visible} loading={loading} error={error} onRetry={refresh} />
        </Card>
      )}
      {canEdit && editor && <RegistrationForm resource={resource} schema={schema} record={editor.record} initialValues={editor.initialValues} restored={editor.restored} draftContext={draftContext} returnFocusRef={createButton} onClose={closeEditor} onSaved={saved} />}
    </div>
  );
}
