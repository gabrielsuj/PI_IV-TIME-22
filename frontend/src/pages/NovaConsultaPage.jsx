import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import FiltrosAgendamento from '../components/agendamento/FiltrosAgendamento';
import PacienteAgendamento from '../components/agendamento/PacienteAgendamento';
import FluxoAgendamento from '../components/agendamento/FluxoAgendamento';
import Alert from '../components/ui/Alert';
import Button from '../components/ui/Button';
import PageHeader from '../components/ui/PageHeader';
import { buscarCatalogosAgendamento } from '../services/catalogoAgendamentoService';
import { useAuth } from '../hooks/useAuth';

function dataLocalBrasil(iso) {
  if (!iso || !Number.isFinite(Date.parse(iso))) return '';
  const partes = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date(iso));
  const valor = (tipo) => partes.find((item) => item.type === tipo)?.value;
  return `${valor('year')}-${valor('month')}-${valor('day')}`;
}

export default function NovaConsultaPage({ buscarVagas, confirmarAgendamento }) {
  const { usuario } = useAuth();
  const location = useLocation();
  const origemFila = location.state?.origemFila;
  const [catalogos, setCatalogos] = useState({ especialidades: [], medicos: [] });
  const [filtros, setFiltros] = useState({ especialidadeId: '', medicoId: '', data: '' });
  const [paciente, setPaciente] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);
  const [confirmando, setConfirmando] = useState(false);
  const [origemAtiva, setOrigemAtiva] = useState(Boolean(origemFila));

  useEffect(() => {
    const controller = new AbortController();
    buscarCatalogosAgendamento({ signal: controller.signal })
      .then((data) => {
        if (controller.signal.aborted) return;
        setCatalogos(data);
        setFiltros((current) => {
          const preferencia = origemAtiva ? origemFila : null;
          const especialidadeId = preferencia?.especialidadeId || current.especialidadeId;
          const medicoId = preferencia?.medicoId || current.medicoId;
          const especialidadeValida = data.especialidades.some((e) => e.id === especialidadeId);
          const medicoValido = especialidadeValida && data.medicos.some((m) => m.id === medicoId
            && m.especialidadeIds.includes(especialidadeId));
          return { especialidadeId: especialidadeValida ? especialidadeId : '',
            medicoId: medicoValido ? medicoId : '',
            data: medicoValido && preferencia ? dataLocalBrasil(preferencia.inicio) : current.data };
        });
        if (origemAtiva && origemFila?.pacienteId && usuario?.perfil === 'SECRETARIA_ADMIN') {
          setPaciente({ id: origemFila.pacienteId, nome: origemFila.pacienteNome || 'Paciente da fila', cpf: 'não informado' });
        }
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
    setOrigemAtiva(false);
    setFiltros({ especialidadeId: '', medicoId: '', data: '' });
    setPaciente(null);
  }
  const especialidade = catalogos.especialidades.find((item) => item.id === filtros.especialidadeId);
  const medico = catalogos.medicos.find((item) => item.id === filtros.medicoId);
  const contexto = JSON.stringify([usuario?.id, usuario?.perfil, paciente?.id, filtros.especialidadeId, filtros.medicoId, filtros.data, origemAtiva ? origemFila?.inicio : null]);
  const preferencia = origemAtiva ? origemFila : null;
  return (
    <div className="page">
      <PageHeader title="Nova consulta" description="Escolha a especialidade, o médico e a data para o atendimento."
        action={<Button to="/fila-espera" variant="outline" disabled={confirmando}>{origemAtiva ? 'Voltar à fila' : 'Voltar ao início'}</Button>} />
      {preferencia && <Alert tone="info">Agendamento iniciado pela fila de espera. Confira os dados e confirme apenas se o horário estiver disponível.</Alert>}
      <PacienteAgendamento value={paciente} onChange={(value) => { setPaciente(value); setOrigemAtiva(false); }} disabled={confirmando} />
      <FiltrosAgendamento {...catalogos} value={filtros} onChange={(value) => { setFiltros(value); setOrigemAtiva(false); }} loading={loading} error={error} onRetry={retry} disabled={confirmando} />
      {typeof buscarVagas === 'function' && <FluxoAgendamento key={contexto} usuario={usuario} paciente={paciente} especialidade={especialidade} medico={medico}
        data={filtros.data} buscarVagas={buscarVagas} confirmarAgendamento={confirmarAgendamento}
        vagaInicial={preferencia?.inicio} onConfirmandoChange={setConfirmando} onNovaConsulta={novaConsulta} />}
    </div>
  );
}
