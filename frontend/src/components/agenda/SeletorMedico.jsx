/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

import Alert from '../ui/Alert';
import Button from '../ui/Button';
import Card from '../ui/Card';
import Field from '../ui/Field';

const idDe = (medico) => medico.id || medico._id;

function rotuloMedico(medico) {
  const { nome, crmNumero, crmUf } = medico;
  return crmNumero && crmUf ? `${nome} — CRM ${crmNumero}/${crmUf}` : nome;
}

export default function SeletorMedico({ medicoId, medicos, onChange }) {
  const { records, loading, error, refresh } = medicos;
  const ativos = records.filter((medico) => idDe(medico) && medico.ativo !== false)
    .sort((a, b) => a.nome.localeCompare(b.nome, 'pt-BR'));
  const escolhido = records.find((medico) => idDe(medico) === medicoId);
  const disponivel = ativos.some((medico) => idDe(medico) === medicoId);

  return (
    <Card className="search-card agenda-medico">
      <Field as="select" label="Médico" className="search-field" value={medicoId}
        disabled={loading || Boolean(error) || ativos.length === 0}
        onChange={(event) => onChange(event.target.value)}>
        <option value="">Selecione um médico</option>
        {ativos.map((medico) => <option key={idDe(medico)} value={idDe(medico)}>{rotuloMedico(medico)}</option>)}
        {medicoId && !disponivel && (
          <option value={medicoId} disabled>{escolhido ? `${rotuloMedico(escolhido)} (inativo)` : 'Médico indisponível'}</option>
        )}
      </Field>
      {loading && <p className="field__hint" role="status">Carregando lista de médicos…</p>}
      {error && (
        <Alert action={<Button variant="outline" size="sm" onClick={refresh}>Tentar novamente</Button>}>
          Não foi possível carregar a lista de médicos. {error.message}
        </Alert>
      )}
      {!loading && !error && ativos.length === 0 && (
        <p className="empty-state" role="status">Nenhum médico ativo disponível para seleção.</p>
      )}
      {!loading && !error && ativos.length > 0 && medicoId && !disponivel && (
        <Alert tone="info">O médico selecionado está indisponível. Selecione outro médico para continuar.</Alert>
      )}
    </Card>
  );
}
