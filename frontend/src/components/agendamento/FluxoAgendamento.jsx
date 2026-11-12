import { useEffect, useRef, useState } from 'react';
import { formatarHorarioAgendamento } from '../../utils/horarioAgendamento';
import Alert from '../ui/Alert';
import Button from '../ui/Button';
import Card from '../ui/Card';
import HorariosDisponiveis from './HorariosDisponiveis';
import ResumoAgendamento from './ResumoAgendamento';

function dataValida(data) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(data || '')) return false;
  const instante = Date.parse(`${data}T00:00:00Z`);
  return Number.isFinite(instante) && new Date(instante).toISOString().slice(0, 10) === data;
}

function validarVagas(resultado, data) {
  if (!resultado || !Array.isArray(resultado.horarios)) throw new Error('Não foi possível ler os horários recebidos.');
  if ((resultado.semAgenda !== undefined && typeof resultado.semAgenda !== 'boolean')
    || (resultado.semAgenda && resultado.horarios.length > 0)) throw new Error('Não foi possível ler os horários recebidos.');
  const inicios = new Set();
  for (const horario of resultado.horarios) {
    const exibicao = formatarHorarioAgendamento(horario, resultado.fusoHorario);
    if (!exibicao || exibicao.data.split('/').reverse().join('-') !== data || inicios.has(exibicao.inicio)) {
      throw new Error('Não foi possível ler os horários recebidos.');
    }
    inicios.add(exibicao.inicio);
  }
  return resultado;
}

const mesmoHorario = (a, b) => a?.inicio === b?.inicio && a?.fim === b?.fim;

// Contrato da API em docs/api/agendamento.md (GET /vagas e POST /consultas, #21).
export default function FluxoAgendamento({ usuario, paciente, especialidade, medico, data, buscarVagas, confirmarAgendamento, onConfirmandoChange, onNovaConsulta }) {
  const [vagas, setVagas] = useState({ status: 'aguardando', horarios: [], fusoHorario: null, error: null });
  const [horario, setHorario] = useState(null);
  const [revisando, setRevisando] = useState(false);
  const [confirmando, setConfirmando] = useState(false);
  const [erroConfirmacao, setErroConfirmacao] = useState(null);
  const [confirmacao, setConfirmacao] = useState(null);
  const [resultadoIncerto, setResultadoIncerto] = useState(false);
  const emConfirmacao = useRef(false);
  const montado = useRef(false);
  const buscaAtual = useRef(null);
  const sequencia = useRef(0);
  const painel = useRef(null);

  useEffect(() => {
    if (revisando || confirmacao || resultadoIncerto) painel.current?.focus();
  }, [revisando, confirmacao, resultadoIncerto]);

  useEffect(() => {
    montado.current = true;
    return () => {
      montado.current = false;
      buscaAtual.current?.abort();
      if (emConfirmacao.current) onConfirmandoChange?.(false);
    };
  }, [onConfirmandoChange]);

  const pessoal = usuario?.perfil === 'PACIENTE';
  const permitido = pessoal || usuario?.perfil === 'SECRETARIA_ADMIN';
  const pronto = permitido && (pessoal || Boolean(paciente?.id)) && especialidade?.ativa === true
    && medico?.ativo === true && medico.especialidadeIds?.includes(especialidade.id) && dataValida(data);
  const selecionado = vagas.status === 'sucesso' && horario && vagas.horarios.some((opcao) => mesmoHorario(opcao, horario));

  async function buscar(preservarErro = false) {
    if (!pronto || emConfirmacao.current || typeof buscarVagas !== 'function') return;
    buscaAtual.current?.abort();
    const controller = new AbortController();
    buscaAtual.current = controller;
    const numero = ++sequencia.current;
    setVagas((current) => ({ ...current, status: 'carregando', error: null }));
    setRevisando(false);
    if (!preservarErro) setErroConfirmacao(null);
    try {
      const resposta = await buscarVagas({ especialidadeId: especialidade.id, medicoId: medico.id, data }, { signal: controller.signal });
      if (!montado.current || controller.signal.aborted || numero !== sequencia.current) return;
      const resultado = validarVagas(resposta, data);
      setVagas({ status: resultado.semAgenda ? 'sem-agenda' : 'sucesso', horarios: resultado.horarios, fusoHorario: resultado.fusoHorario, error: null });
      setHorario((current) => resultado.horarios.find((opcao) => mesmoHorario(opcao, current)) || null);
    } catch (error) {
      if (montado.current && !controller.signal.aborted && numero === sequencia.current) {
        setVagas((current) => ({ ...current, status: 'erro', error }));
      }
    }
  }

  async function confirmar() {
    if (!pronto || !selecionado || !revisando || emConfirmacao.current || resultadoIncerto || typeof confirmarAgendamento !== 'function') return;
    emConfirmacao.current = true;
    setConfirmando(true);
    onConfirmandoChange?.(true);
    setErroConfirmacao(null);
    let atualizarVagas = false;
    try {
      const dados = { especialidadeId: especialidade.id, medicoId: medico.id, inicio: horario.inicio, fim: horario.fim };
      if (!pessoal) dados.pacienteId = paciente.id;
      const resultado = await confirmarAgendamento(dados);
      if (!montado.current) return;
      if (typeof resultado?.id !== 'string' || !resultado.id.trim()) {
        setResultadoIncerto(true);
        return;
      }
      setConfirmacao(resultado);
    } catch (error) {
      if (!montado.current) return;
      setErroConfirmacao(error);
      if (error?.status === 409) {
        setHorario(null);
        setRevisando(false);
        atualizarVagas = true;
      }
    } finally {
      emConfirmacao.current = false;
      if (montado.current) {
        setConfirmando(false);
        onConfirmandoChange?.(false);
        if (atualizarVagas) buscar(true);
      }
    }
  }

  if (confirmacao) return (
    <Card title="Agendamento realizado" ref={painel} tabIndex={-1}>
      <Alert tone="success">Consulta agendada. Código da consulta: {confirmacao.id}.</Alert>
      <div className="form-actions"><Button onClick={onNovaConsulta}>Nova consulta</Button></div>
    </Card>
  );

  if (resultadoIncerto) return (
    <Card title="Verifique o agendamento" ref={painel} tabIndex={-1}>
      <Alert>Não foi possível verificar o resultado da confirmação. Confira o agendamento antes de tentar novamente.</Alert>
      <div className="form-actions"><Button to="/" variant="outline">Voltar ao início</Button></div>
    </Card>
  );

  if (revisando) return <div ref={painel} tabIndex={-1}>
    <ResumoAgendamento usuario={usuario} paciente={paciente} especialidade={especialidade} medico={medico}
      horario={horario} fusoHorario={vagas.fusoHorario} confirmando={confirmando} error={erroConfirmacao}
      onVoltar={() => { if (!emConfirmacao.current) { setRevisando(false); setErroConfirmacao(null); } }}
      onConfirmar={typeof confirmarAgendamento === 'function' ? confirmar : undefined} />
  </div>;

  return (
    <>
      {erroConfirmacao?.status === 409 && <Alert>Este horário não está mais disponível. Escolha outro horário na lista atualizada.</Alert>}
      <HorariosDisponiveis {...vagas} value={horario} disabled={confirmando || !pronto} onRetry={() => buscar()}
        onChange={(opcao) => {
          if (!emConfirmacao.current && vagas.status === 'sucesso' && vagas.horarios.some((item) => mesmoHorario(item, opcao))) {
            setHorario(opcao);
            setErroConfirmacao(null);
          }
        }} />
      <div className="form-actions">
        <Button variant="outline" disabled={!pronto || confirmando || vagas.status === 'carregando'} onClick={() => buscar()}>Buscar horários</Button>
        <Button disabled={!pronto || !selecionado || confirmando} onClick={() => setRevisando(true)}>Revisar agendamento</Button>
      </div>
    </>
  );
}
