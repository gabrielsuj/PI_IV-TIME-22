import { useEffect, useRef, useState } from 'react';
import { conflitoComConsultas, criarBloqueio, listarBloqueios, removerBloqueio } from '../../services/agendaService';
import { diaSeguinte, formatarPeriodo, hoje } from '../../utils/horario';
import Alert from '../ui/Alert';
import Button from '../ui/Button';
import Card from '../ui/Card';
import DataTable from '../ui/DataTable';
import Field from '../ui/Field';
import Modal from '../ui/Modal';
import ConflitoConsultas from './ConflitoConsultas';

const NOVO = { dataInicio: '', dataFim: '', horaInicio: '08:00', horaFim: '12:00', diaInteiro: false, motivo: '' };

// A API interpreta o intervalo no fuso da agenda.
function paraApi(medicoId, form) {
  const dataFim = form.dataFim || form.dataInicio;
  const inicio = form.diaInteiro ? `${form.dataInicio}T00:00` : `${form.dataInicio}T${form.horaInicio}`;
  const fim = form.diaInteiro ? `${diaSeguinte(dataFim)}T00:00` : `${dataFim}T${form.horaFim}`;
  return { medicoId, inicio, fim, motivo: form.motivo.trim() || undefined };
}

function validarFormulario(form) {
  const erros = {};
  if (!form.dataInicio) erros.dataInicio = 'Informe a data de início.';
  if (form.dataInicio && form.dataFim && form.dataFim < form.dataInicio) erros.dataFim = 'A data de fim deve ser igual ou posterior à data de início.';
  if (!form.diaInteiro) {
    if (!form.horaInicio) erros.horaInicio = 'Informe a hora de início.';
    if (!form.horaFim) erros.horaFim = 'Informe a hora de fim.';
    if (form.horaInicio && form.horaFim && (!form.dataFim || form.dataFim === form.dataInicio) && form.horaInicio >= form.horaFim) {
      erros.horaFim = 'O fim do bloqueio deve ser depois do início.';
    }
  }
  return erros;
}

export default function BloqueiosCard({ medicoId, onDirtyChange }) {
  const formRef = useRef(null);
  const [bloqueios, setBloqueios] = useState([]);
  const [carregando, setCarregando] = useState(true);
  const [erroCarga, setErroCarga] = useState(null);
  const [revisao, setRevisao] = useState(0);
  const [form, setForm] = useState(NOVO);
  const [salvando, setSalvando] = useState(false);
  const [falha, setFalha] = useState('');
  const [aviso, setAviso] = useState('');
  const [remover, setRemover] = useState(null);
  const [removendo, setRemovendo] = useState(false);
  const [conflito, setConflito] = useState(null);
  const [erros, setErros] = useState({});

  useEffect(() => { onDirtyChange?.(JSON.stringify(form) !== JSON.stringify(NOVO)); }, [form, onDirtyChange]);
  useEffect(() => {
    if (Object.keys(erros).length) formRef.current?.querySelector('[aria-invalid="true"]')?.focus();
  }, [erros]);

  useEffect(() => {
    const controller = new AbortController();
    const de = hoje();
    const ate = `${Number(de.slice(0, 4)) + 1}${de.slice(4)}`;   // próximos 12 meses
    listarBloqueios(medicoId, { de, ate, signal: controller.signal })
      .then((lista) => { setBloqueios(lista); setErroCarga(null); })
      .catch((error) => { if (!controller.signal.aborted) setErroCarga(error); })
      .finally(() => { if (!controller.signal.aborted) setCarregando(false); });
    return () => controller.abort();
  }, [medicoId, revisao]);

  function recarregar() {
    setCarregando(true);
    setRevisao((r) => r + 1);
  }

  function alterar(event) {
    const { name, value, type, checked } = event.target;
    setForm((atual) => ({ ...atual, [name]: type === 'checkbox' ? checked : value }));
    setFalha('');
    setAviso('');
    setConflito(null);
    setErros({});
  }

  async function enviar(corpo, { confirmar = false, afetadas = 0 } = {}) {
    setSalvando(true);
    setFalha('');
    try {
      await criarBloqueio(corpo, { confirmar });
      setForm(NOVO);
      setConflito(null);
      setAviso(confirmar
        ? `Bloqueio cadastrado. ${afetadas} consulta(s) precisam ser remarcadas.`
        : 'Bloqueio cadastrado.');
      recarregar();
    } catch (error) {
      const atingidas = conflitoComConsultas(error);
      if (atingidas) setConflito({ ...atingidas, corpo });
      else setFalha(error.message);
    } finally {
      setSalvando(false);
    }
  }

  async function salvar(event) {
    event.preventDefault();
    const validacao = validarFormulario(form);
    setErros(validacao);
    if (Object.keys(validacao).length) { setFalha('Revise os campos destacados antes de adicionar o bloqueio.'); return; }
    const corpo = paraApi(medicoId, form);
    await enviar(corpo);
  }

  function bloquearMesmoAssim() {
    return enviar(conflito.corpo, { confirmar: true, afetadas: conflito.consultas.length });
  }

  async function confirmarRemocao() {
    setRemovendo(true);
    try {
      await removerBloqueio(remover.id);
      setAviso('Bloqueio removido.');
      setRemover(null);
      recarregar();
    } catch (error) {
      setFalha(error.message);
      setRemover(null);
    } finally {
      setRemovendo(false);
    }
  }

  const colunas = [
    { key: 'periodo', label: 'Período', render: (b) => formatarPeriodo(b.inicio, b.fim) },
    { key: 'motivo', label: 'Motivo', render: (b) => b.motivo || 'Não informado' },
    {
      key: 'acoes', label: 'Ações', align: 'right',
      render: (b) => <Button variant="ghost" size="sm" aria-label={`Remover bloqueio de ${formatarPeriodo(b.inicio, b.fim)}`} onClick={() => setRemover(b)}>Remover</Button>,
    },
  ];
  const resumo = form.dataInicio && Object.keys(validarFormulario(form)).length === 0 ? paraApi(medicoId, form) : null;

  return (
    <Card title="Bloqueios" description="Datas ou horários em que o médico não atende. Ficam indisponíveis para agendamento.">
      <DataTable caption="Próximos bloqueios" columns={colunas} rows={bloqueios} loading={carregando} error={erroCarga}
        onRetry={recarregar} emptyMessage="Nenhum bloqueio nos próximos 12 meses." />
      {!carregando && !erroCarga && bloqueios.length === 0 && <p className="field__hint">A jornada semanal continua valendo. Cadastre um bloqueio abaixo apenas para os períodos em que não haverá atendimento.</p>}

      <form ref={formRef} className="bloqueio-form" onSubmit={salvar} noValidate>
        <h3 className="bloqueio-form__titulo">Novo bloqueio</h3>
        <p className="field__hint">Escolha as datas e, se necessário, limite o bloqueio a um horário. As consultas já marcadas serão apresentadas para confirmação antes de bloquear.</p>
        <div className="form-grid">
          <Field type="date" name="dataInicio" label="Data de início" required error={erros.dataInicio} value={form.dataInicio} onChange={alterar} disabled={salvando} />
          <Field type="date" name="dataFim" label="Data de fim" min={form.dataInicio || undefined} hint="Deixe em branco se for no mesmo dia. A data escolhida também será bloqueada." error={erros.dataFim} value={form.dataFim} onChange={alterar} disabled={salvando} />
          <label className="checkbox-option form-grid__wide">
            <input type="checkbox" name="diaInteiro" checked={form.diaInteiro} onChange={alterar} disabled={salvando} aria-describedby={`dia-inteiro-${medicoId}-hint`} />
            Dia inteiro
          </label>
          <p id={`dia-inteiro-${medicoId}-hint`} className="field__hint form-grid__wide">Marque para bloquear todos os horários das datas escolhidas.</p>
          {!form.diaInteiro && (
            <>
              <Field type="time" name="horaInicio" label="Hora de início" required error={erros.horaInicio} value={form.horaInicio} onChange={alterar} disabled={salvando} />
              <Field type="time" name="horaFim" label="Hora de fim" required error={erros.horaFim} value={form.horaFim} onChange={alterar} disabled={salvando} />
            </>
          )}
          <Field name="motivo" label="Motivo (opcional)" hint="Por exemplo: férias, congresso ou manutenção. Até 200 caracteres." maxLength={200} className="form-grid__wide" value={form.motivo} onChange={alterar} disabled={salvando} />
        </div>
        <section className="agenda-resumo bloqueio-resumo" aria-label="Resumo do novo bloqueio">
          <h4 className="agenda-resumo__titulo">Confira antes de adicionar</h4>
          {resumo ? <>
            <p>{formatarPeriodo(resumo.inicio, resumo.fim)}</p>
            <p><strong>Motivo:</strong> {form.motivo.trim() || 'Não informado'}</p>
            <p className="field__hint">Esse período ficará indisponível para novos agendamentos.</p>
          </> : <p className="field__hint">Preencha as datas e os horários para conferir o período do bloqueio.</p>}
        </section>
        {falha && <Alert>{falha}</Alert>}
        {aviso && <Alert tone="success">{aviso}</Alert>}
        {conflito ? (
          <ConflitoConsultas conflito={conflito} medicoId={medicoId} acao="Bloquear mesmo assim" confirmando={salvando}
            onConfirmar={bloquearMesmoAssim} onCancelar={() => setConflito(null)} />
        ) : (
          <footer className="form-actions">
            <Button type="submit" loading={salvando}>{salvando ? 'Salvando…' : 'Adicionar bloqueio'}</Button>
          </footer>
        )}
      </form>

      {remover && (
        <Modal title="Remover bloqueio?" description={formatarPeriodo(remover.inicio, remover.fim)} busy={removendo} onClose={() => setRemover(null)}>
          <p>O período volta a ficar disponível para agendamento.</p>
          <footer className="form-actions">
            <Button variant="outline" onClick={() => setRemover(null)} disabled={removendo}>Cancelar</Button>
            <Button variant="danger" onClick={confirmarRemocao} loading={removendo}>Remover</Button>
          </footer>
        </Modal>
      )}
    </Card>
  );
}
