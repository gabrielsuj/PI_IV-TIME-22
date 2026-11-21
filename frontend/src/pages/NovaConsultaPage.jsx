
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';

import FiltrosAgendamento from '../components/agendamento/FiltrosAgendamento';
import PacienteAgendamento from '../components/agendamento/PacienteAgendamento';
import FluxoAgendamento from '../components/agendamento/FluxoAgendamento';
import Alert from '../components/ui/Alert';
import Button from '../components/ui/Button';
import PageHeader from '../components/ui/PageHeader';

import { buscarCatalogosAgendamento } from '../services/catalogoAgendamentoService';
import {
  guardarRascunhoAgendamento,
  recuperarRascunhoAgendamento,
  removerRascunhoAgendamento
} from '../services/rascunhoAgendamento';

import { useAuth } from '../hooks/useAuth';

/**
 * @author Vinicius (criador)
 * @author Gabriel (modificador)
 * @author Murilo Moraes (modificador)
 * @author Murilo Rigoni (modificador)
 */

// Converte uma data ISO para a data local de São Paulo.
function dataLocalBrasil(iso) {
  if (!iso || !Number.isFinite(Date.parse(iso))) return '';

  const partes = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(new Date(iso));

  const valor = (tipo) =>
    partes.find((item) => item.type === tipo)?.value;

  return `${valor('year')}-${valor('month')}-${valor('day')}`;
}

// buscarVagas e confirmarAgendamento vêm do serviço de agendamento.
// A fila de espera pode iniciar o fluxo com uma vaga pré-selecionada.
export default function NovaConsultaPage({
  buscarVagas,
  confirmarAgendamento
}) {
  const { usuario } = useAuth();

  // Dados recebidos da fila de espera.
  const location = useLocation();
  const origemFila = location.state?.origemFila;

  // Recuperação do rascunho (develop).
  const [recuperado, setRecuperado] = useState(
    () => recuperarRascunhoAgendamento(usuario)
  );

  const [catalogos, setCatalogos] = useState({
    especialidades: [],
    medicos: []
  });

  const [filtros, setFiltros] = useState(
    () => recuperado?.filtros || {
      especialidadeId: '',
      medicoId: '',
      data: ''
    }
  );

  const [paciente, setPaciente] = useState(
    () => recuperado?.paciente || null
  );

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);
  const [confirmando, setConfirmando] = useState(false);

  // Indica se o agendamento ainda está usando a vaga da fila.
  const [origemAtiva, setOrigemAtiva] = useState(
    Boolean(origemFila)
  );

  // Salva o rascunho enquanto os dados são preenchidos.
  useEffect(() => {
    guardarRascunhoAgendamento(usuario, {
      filtros,
      paciente
    });
  }, [usuario, filtros, paciente]);

  // Preserva o comportamento original da develop.
  useEffect(
    () => () => removerRascunhoAgendamento(usuario),
    [usuario]
  );

  // Carrega especialidades e médicos.
  useEffect(() => {
    const controller = new AbortController();

    buscarCatalogosAgendamento({
      signal: controller.signal
    })
      .then((data) => {
        if (controller.signal.aborted) return;

        setCatalogos(data);

        setFiltros((current) => {
          const preferencia = origemAtiva ? origemFila : null;

          const especialidadeId =
            preferencia?.especialidadeId ||
            current.especialidadeId;

          const medicoId =
            preferencia?.medicoId ||
            current.medicoId;

          const especialidadeValida =
            data.especialidades.some(
              (especialidade) =>
                especialidade.id === especialidadeId
            );

          const medicoValido =
            especialidadeValida &&
            data.medicos.some(
              (medico) =>
                medico.id === medicoId &&
                medico.especialidadeIds.includes(especialidadeId)
            );

          return {
            especialidadeId: especialidadeValida
              ? especialidadeId
              : '',
            medicoId: medicoValido ? medicoId : '',
            data: medicoValido && preferencia
              ? dataLocalBrasil(preferencia.inicio)
              : current.data
          };
        });

        // Pré-seleciona paciente quando a origem é a fila.
        if (
          origemAtiva &&
          origemFila?.pacienteId &&
          usuario?.perfil === 'SECRETARIA_ADMIN'
        ) {
          setPaciente({
            id: origemFila.pacienteId,
            nome: origemFila.pacienteNome || 'Paciente da fila',
            cpf: 'não informado'
          });
        }

        setError(null);
      })
      .catch((failure) => {
        if (!controller.signal.aborted) {
          setError(failure);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      });

    return () => controller.abort();
  }, [revision, origemAtiva, origemFila, usuario?.perfil]);

  function retry() {
    setLoading(true);
    setError(null);
    setRevision((current) => current + 1);
  }

  // Limpa o rascunho após confirmação.
  function concluirRascunho() {
    removerRascunhoAgendamento(usuario);
    setRecuperado(null);
  }

  // Inicia um novo agendamento, sem seleção anterior.
  function novaConsulta() {
    concluirRascunho();
    setOrigemAtiva(false);
    setFiltros({
      especialidadeId: '',
      medicoId: '',
      data: ''
    });
    setPaciente(null);
  }

  const especialidade = catalogos.especialidades.find(
    (item) => item.id === filtros.especialidadeId
  );

  const medico = catalogos.medicos.find(
    (item) => item.id === filtros.medicoId
  );

  const preferencia = origemAtiva ? origemFila : null;

  // Remonta o fluxo quando os dados relevantes mudam.
  const contexto = JSON.stringify([
    usuario?.id,
    usuario?.perfil,
    paciente?.id,
    filtros.especialidadeId,
    filtros.medicoId,
    filtros.data,
    preferencia?.inicio ?? null
  ]);

  return (
    <div className="page">
      <PageHeader
        title="Nova consulta"
        description="Escolha a especialidade, o médico e a data para o atendimento."
        action={
          <Button
            to={origemAtiva ? '/fila-espera' : '/'}
            variant="outline"
            disabled={confirmando}
          >
            {origemAtiva ? 'Voltar à fila' : 'Voltar ao início'}
          </Button>
        }
      />

      {preferencia && (
        <Alert tone="info">
          Agendamento iniciado pela fila de espera.
          Confira os dados e confirme apenas se o horário
          estiver disponível.
        </Alert>
      )}

      {recuperado && (
        <Alert tone="info">
          Dados do agendamento recuperados após o login.
          Busque os horários novamente antes de confirmar.
        </Alert>
      )}

      <PacienteAgendamento
        value={paciente}
        onChange={(value) => {
          setPaciente(value);
          setOrigemAtiva(false);
        }}
        disabled={confirmando}
      />

      <FiltrosAgendamento
        {...catalogos}
        value={filtros}
        onChange={(value) => {
          setFiltros(value);
          setOrigemAtiva(false);
        }}
        loading={loading}
        error={error}
        onRetry={retry}
        disabled={confirmando}
      />

      {typeof buscarVagas === 'function' && (
        <FluxoAgendamento
          key={contexto}
          usuario={usuario}
          paciente={paciente}
          especialidade={especialidade}
          medico={medico}
          data={filtros.data}
          buscarVagas={buscarVagas}
          confirmarAgendamento={confirmarAgendamento}
          vagaInicial={preferencia?.inicio}
          onConfirmandoChange={setConfirmando}
          onConfirmado={concluirRascunho}
          onNovaConsulta={novaConsulta}
        />
      )}
    </div>
  );
}
