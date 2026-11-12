import { useEffect, useState } from 'react';
import { useAuth } from '../../hooks/useAuth';
import { buscarPacientesAgendamento } from '../../services/pacienteAgendamentoService';
import Alert from '../ui/Alert';
import Button from '../ui/Button';
import Card from '../ui/Card';
import Field from '../ui/Field';

function SelecaoPaciente({ value, onChange, disabled }) {
  const [busca, setBusca] = useState('');
  const [consulta, setConsulta] = useState({ busca: '', revision: 0 });
  const [pacientes, setPacientes] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    const controller = new AbortController();
    buscarPacientesAgendamento(consulta.busca, { signal: controller.signal })
      .then((data) => {
        if (controller.signal.aborted) return;
        setPacientes(data);
        setError(null);
      })
      .catch((failure) => { if (!controller.signal.aborted) setError(failure); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [consulta]);

  function pesquisar(texto) {
    if (loading || disabled) return;
    setLoading(true);
    setError(null);
    setConsulta((current) => ({ busca: texto, revision: current.revision + 1 }));
  }

  const options = value && !pacientes.some((paciente) => paciente.id === value.id) ? [value, ...pacientes] : pacientes;

  return (
    <Card title="Quem será atendido" description="Escolha o paciente para quem deseja agendar a consulta.">
      <form onSubmit={(event) => { event.preventDefault(); pesquisar(busca); }}>
        <Field type="search" name="buscaPaciente" label="Buscar paciente por nome ou CPF" value={busca} disabled={disabled} onChange={(event) => setBusca(event.target.value)} />
        <div className="form-actions">
          <Button type="submit" variant="outline" disabled={loading || disabled}>Buscar paciente</Button>
        </div>
      </form>
      {loading && <p role="status">Carregando pacientes…</p>}
      {error && <Alert action={<Button variant="outline" size="sm" disabled={disabled} onClick={() => pesquisar(consulta.busca)}>Tentar novamente</Button>}>Não foi possível carregar os pacientes. {error.message}</Alert>}
      <div className="form-grid">
        <Field as="select" name="pacienteId" label="Paciente" required className="form-grid__wide" value={value?.id || ''}
          disabled={disabled || loading || Boolean(error) || options.length === 0} hint="A seleção é mantida ao fazer outra busca."
          onChange={(event) => {
            const paciente = options.find((option) => option.id === event.target.value);
            onChange(paciente ? { id: paciente.id, nome: paciente.nome, cpf: paciente.cpf } : null);
          }}>
          <option value="">Selecione um paciente</option>
          {options.map((paciente) => <option key={paciente.id} value={paciente.id}>{paciente.nome} — CPF {paciente.cpf}</option>)}
        </Field>
      </div>
      {!loading && !error && pacientes.length === 0 && (
        <>
          <p role="status">Nenhum paciente encontrado{consulta.busca.trim() ? ' para esta busca' : ''}.</p>
          <Button to="/pacientes" variant="outline" disabled={disabled}>Cadastrar paciente</Button>
        </>
      )}
    </Card>
  );
}

export default function PacienteAgendamento({ value, onChange, disabled = false }) {
  const { usuario } = useAuth();
  if (usuario?.perfil === 'SECRETARIA_ADMIN') return <SelecaoPaciente value={value} onChange={onChange} disabled={disabled} />;
  if (usuario?.perfil !== 'PACIENTE') return null;

  // Necessita issue #21 para resolver o paciente da sessão no servidor.
  return <Card title="Quem será atendido"><p>Você está escolhendo um atendimento para você, {usuario.nome}.</p></Card>;
}
