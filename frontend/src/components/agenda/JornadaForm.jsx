import { useEffect, useState } from 'react';
import { buscarAgenda, conflitoComConsultas, salvarAgenda } from '../../services/agendaService';
import { DIAS_SEMANA, horaParaMinutos, minutosParaHora } from '../../utils/horario';
import Alert from '../ui/Alert';
import Button from '../ui/Button';
import Card from '../ui/Card';
import Field from '../ui/Field';
import ConflitoConsultas from './ConflitoConsultas';

const DURACOES = [10, 15, 20, 30, 40, 45, 60, 90];
const FAIXA_PADRAO = { inicio: '08:00', fim: '12:00' };

function diasVazios() {
  return Object.fromEntries(DIAS_SEMANA.map(({ valor }) => [valor, { atende: false, faixas: [] }]));
}

/** Converte a resposta da API (minutos) para o estado do formulário (horas). */
function paraFormulario(agenda) {
  const dias = diasVazios();
  for (const jornada of agenda?.jornadas ?? []) {
    dias[jornada.diaSemana] = {
      atende: true,
      faixas: jornada.faixas.map((faixa) => ({ inicio: minutosParaHora(faixa.inicioMinuto), fim: minutosParaHora(faixa.fimMinuto) })),
    };
  }
  return { duracao: String(agenda?.duracaoMinutos ?? 30), dias };
}

/** Converte o estado do formulário para o corpo do PUT /agendas. */
function paraApi({ duracao, dias }) {
  return {
    duracaoMinutos: Number(duracao),
    jornadas: DIAS_SEMANA.filter(({ valor }) => dias[valor].atende).map(({ valor }) => ({
      diaSemana: valor,
      faixas: dias[valor].faixas.map((faixa) => ({
        inicioMinuto: horaParaMinutos(faixa.inicio),
        fimMinuto: horaParaMinutos(faixa.fim, { fim: true }),
      })),
    })),
  };
}

/** Validação rápida no navegador; as regras completas ficam na API. */
function primeiroErro({ dias }) {
  for (const { valor, nome } of DIAS_SEMANA) {
    if (!dias[valor].atende) continue;
    for (const faixa of dias[valor].faixas) {
      if (!faixa.inicio || !faixa.fim) return `Preencha início e fim de todas as faixas de ${nome}.`;
      if (horaParaMinutos(faixa.inicio) >= horaParaMinutos(faixa.fim, { fim: true })) return `Em ${nome}, o fim deve ser depois do início.`;
    }
  }
  return null;
}

export default function JornadaForm({ medicoId }) {
  const [form, setForm] = useState(() => paraFormulario(null));
  const [carregando, setCarregando] = useState(true);
  const [erroCarga, setErroCarga] = useState(null);
  const [configurada, setConfigurada] = useState(false);
  const [revisao, setRevisao] = useState(0);
  const [salvando, setSalvando] = useState(false);
  const [falha, setFalha] = useState('');
  const [aviso, setAviso] = useState('');
  const [conflito, setConflito] = useState(null); // #44: consultas fora da nova jornada, aguardando confirmação

  useEffect(() => {
    const controller = new AbortController();
    buscarAgenda(medicoId, { signal: controller.signal })
      .then((agenda) => {
        setForm(paraFormulario(agenda));
        setConfigurada(Boolean(agenda));
        setErroCarga(null);
      })
      .catch((error) => { if (!controller.signal.aborted) setErroCarga(error); })
      .finally(() => { if (!controller.signal.aborted) setCarregando(false); });
    return () => controller.abort();
  }, [medicoId, revisao]);

  function alterarDia(valor, mudanca) {
    setForm((atual) => ({ ...atual, dias: { ...atual.dias, [valor]: { ...atual.dias[valor], ...mudanca } } }));
    setAviso('');
    setConflito(null);
  }

  function alternarDia(valor, atende) {
    alterarDia(valor, { atende, faixas: atende && form.dias[valor].faixas.length === 0 ? [{ ...FAIXA_PADRAO }] : form.dias[valor].faixas });
  }

  function alterarFaixa(valor, indice, campo, hora) {
    alterarDia(valor, { faixas: form.dias[valor].faixas.map((faixa, i) => (i === indice ? { ...faixa, [campo]: hora } : faixa)) });
  }

  function adicionarFaixa(valor) {
    const faixas = form.dias[valor].faixas;
    const ultima = faixas.at(-1);
    alterarDia(valor, { faixas: [...faixas, ultima ? { inicio: ultima.fim, fim: ultima.fim } : { ...FAIXA_PADRAO }] });
  }

  function removerFaixa(valor, indice) {
    const faixas = form.dias[valor].faixas.filter((_, i) => i !== indice);
    alterarDia(valor, { faixas, atende: faixas.length > 0 });
  }

  async function enviar({ confirmar = false, afetadas = 0 } = {}) {
    setSalvando(true);
    setFalha('');
    try {
      const agenda = await salvarAgenda(medicoId, paraApi(form), { confirmar });
      setForm(paraFormulario(agenda));
      setConfigurada(true);
      setConflito(null);
      setAviso(confirmar ? `Agenda salva. ${afetadas} consulta(s) precisam ser remarcadas.` : 'Agenda salva.');
    } catch (error) {
      const atingidas = conflitoComConsultas(error);
      if (atingidas) setConflito(atingidas);
      else setFalha(error.message);
    } finally {
      setSalvando(false);
    }
  }

  async function salvar(event) {
    event.preventDefault();
    const erro = primeiroErro(form);
    if (erro) { setFalha(erro); return; }
    await enviar();
  }

  if (carregando) return <Card title="Jornada semanal"><p role="status">Carregando agenda…</p></Card>;
  if (erroCarga) {
    return (
      <Card title="Jornada semanal">
        <Alert action={<Button variant="outline" size="sm" onClick={() => { setCarregando(true); setRevisao((r) => r + 1); }}>Tentar novamente</Button>}>
          Não foi possível carregar a agenda. {erroCarga.message}
        </Alert>
      </Card>
    );
  }

  return (
    <Card title="Jornada semanal" description="Dias e horários de atendimento e a duração de cada consulta.">
      {!configurada && <Alert tone="info">Este médico ainda não tem agenda. Marque os dias de atendimento e salve.</Alert>}
      <form className="jornada-form" onSubmit={salvar} noValidate>
        <Field as="select" label="Duração da consulta" name="duracao" value={form.duracao} disabled={salvando}
          onChange={(event) => { setForm((atual) => ({ ...atual, duracao: event.target.value })); setAviso(''); setConflito(null); }}>
          {DURACOES.map((minutos) => <option key={minutos} value={minutos}>{minutos} minutos</option>)}
        </Field>

        {DIAS_SEMANA.map(({ valor, nome }) => {
          const dia = form.dias[valor];
          return (
            <fieldset key={valor} className="jornada-dia" disabled={salvando}>
              <legend className="sr-only">{nome}</legend>
              <label className="checkbox-option jornada-dia__toggle">
                <input type="checkbox" checked={dia.atende} onChange={(event) => alternarDia(valor, event.target.checked)} />
                {nome}
              </label>
              {dia.atende ? (
                <div className="jornada-dia__faixas">
                  {dia.faixas.map((faixa, indice) => (
                    <div key={indice} className="faixa-row">
                      <Field type="time" label="Início" value={faixa.inicio} onChange={(event) => alterarFaixa(valor, indice, 'inicio', event.target.value)} />
                      <Field type="time" label="Fim" value={faixa.fim} onChange={(event) => alterarFaixa(valor, indice, 'fim', event.target.value)} />
                      <Button variant="ghost" size="sm" aria-label={`Remover faixa ${indice + 1} de ${nome}`} onClick={() => removerFaixa(valor, indice)}>Remover</Button>
                    </div>
                  ))}
                  <Button variant="outline" size="sm" onClick={() => adicionarFaixa(valor)}>
                    <span aria-hidden="true">＋</span> Adicionar faixa em {nome}
                  </Button>
                </div>
              ) : <p className="field__hint">Sem atendimento</p>}
            </fieldset>
          );
        })}

        {falha && <Alert>{falha}</Alert>}
        {aviso && <Alert tone="success">{aviso}</Alert>}
        {conflito ? (
          <ConflitoConsultas conflito={conflito} medicoId={medicoId} acao="Salvar mesmo assim" confirmando={salvando}
            onConfirmar={() => enviar({ confirmar: true, afetadas: conflito.consultas.length })} onCancelar={() => setConflito(null)} />
        ) : (
          <footer className="form-actions">
            <Button type="submit" loading={salvando}>{salvando ? 'Salvando…' : 'Salvar jornada'}</Button>
          </footer>
        )}
      </form>
    </Card>
  );
}
