import { useEffect, useState } from 'react';
import FiltrosAgendamento from '../components/agendamento/FiltrosAgendamento';
import PacienteAgendamento from '../components/agendamento/PacienteAgendamento';
import FluxoAgendamento from '../components/agendamento/FluxoAgendamento';
import Alert from '../components/ui/Alert';
import Button from '../components/ui/Button';
import PageHeader from '../components/ui/PageHeader';
import { buscarCatalogosAgendamento } from '../services/catalogoAgendamentoService';
import { guardarRascunhoAgendamento, recuperarRascunhoAgendamento, removerRascunhoAgendamento } from '../services/rascunhoAgendamento';
import { useAuth } from '../hooks/useAuth';

// buscarVagas e confirmarAgendamento vêm de services/agendamentoService.js (GET /vagas e POST /consultas, #21),
// ligados em routes/index.jsx. Os testes podem trocar por funções falsas.
export default function NovaConsultaPage({ buscarVagas, confirmarAgendamento }) {
  const { usuario } = useAuth();
  const [recuperado, setRecuperado] = useState(() => recuperarRascunhoAgendamento(usuario));
  const [catalogos, setCatalogos] = useState({ especialidades: [], medicos: [] });
  const [filtros, setFiltros] = useState(() => recuperado?.filtros || { especialidadeId: '', medicoId: '', data: '' });
  const [paciente, setPaciente] = useState(() => recuperado?.paciente || null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);
  const [confirmando, setConfirmando] = useState(false);

  useEffect(() => {
    guardarRascunhoAgendamento(usuario, { filtros, paciente });
  }, [usuario, filtros, paciente]);

  useEffect(() => () => removerRascunhoAgendamento(usuario), [usuario]);

  useEffect(() => {
    const controller = new AbortController();
    buscarCatalogosAgendamento({ signal: controller.signal })
      .then((data) => {
        if (controller.signal.aborted) return;
        setCatalogos(data);
        setFiltros((current) => {
          const especialidadeValida = data.especialidades.some((especialidade) => especialidade.id === current.especialidadeId);
          const medicoValido = especialidadeValida && data.medicos.some((medico) => medico.id === current.medicoId
            && medico.especialidadeIds.includes(current.especialidadeId));
          return { ...current, especialidadeId: especialidadeValida ? current.especialidadeId : '', medicoId: medicoValido ? current.medicoId : '' };
        });
        setError(null);
      })
      .catch((failure) => { if (!controller.signal.aborted) setError(failure); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [revision]);

  function retry() {
    setLoading(true);
    setError(null);
    setRevision((current) => current + 1);
  }

  function novaConsulta() {
    concluirRascunho();
    setFiltros({ especialidadeId: '', medicoId: '', data: '' });
    setPaciente(null);
  }

  function concluirRascunho() {
    removerRascunhoAgendamento(usuario);
    setRecuperado(null);
  }

  const especialidade = catalogos.especialidades.find((item) => item.id === filtros.especialidadeId);
  const medico = catalogos.medicos.find((item) => item.id === filtros.medicoId);
  const contexto = JSON.stringify([usuario?.id, usuario?.perfil, paciente?.id, filtros.especialidadeId, filtros.medicoId, filtros.data]);

  return (
    <div className="page">
      <PageHeader title="Nova consulta" description="Escolha a especialidade, o médico e a data para o atendimento."
        action={<Button to="/" variant="outline" disabled={confirmando}>Voltar ao início</Button>} />
      {recuperado && <Alert tone="info">Dados do agendamento recuperados após o login. Busque os horários novamente antes de confirmar.</Alert>}
      <PacienteAgendamento value={paciente} onChange={setPaciente} disabled={confirmando} />
      <FiltrosAgendamento {...catalogos} value={filtros} onChange={setFiltros} loading={loading} error={error} onRetry={retry} disabled={confirmando} />
      {typeof buscarVagas === 'function' && <FluxoAgendamento key={contexto} usuario={usuario} paciente={paciente} especialidade={especialidade} medico={medico}
        data={filtros.data} buscarVagas={buscarVagas} confirmarAgendamento={confirmarAgendamento}
        onConfirmandoChange={setConfirmando} onConfirmado={concluirRascunho} onNovaConsulta={novaConsulta} />}
    </div>
  );
}
