import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import Alert from '../components/ui/Alert';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import Field from '../components/ui/Field';
import PageHeader from '../components/ui/PageHeader';
import { useRecords } from '../hooks/useRecords';
import { buscarAgenda, listarBloqueios } from '../services/agendaService';
import { listarConsultas } from '../services/calendarioService';
import {
  agoraNoFuso, classificarHorario, diasDaSemana, horariosDoDia, rotuloDia, rotuloDiaCompleto, somarDias,
} from '../utils/calendario';
import { hoje } from '../utils/horario';

const ESTADOS = {
  livre: { rotulo: 'Livre', tom: 'success' },
  ocupado: { rotulo: 'Ocupado', tom: 'info' },
  conflito: { rotulo: 'Remarcar', tom: 'warning' },
  bloqueado: { rotulo: 'Bloqueado', tom: 'danger' },
  passado: { rotulo: 'Indisponível', tom: 'neutral' },
};
/** Complemento de "Indisponível" para horários que já passaram. */
const DESFECHO = { CONCLUIDA: 'concluída', FALTA: 'falta', CANCELADA: 'cancelada', AGENDADA: 'aguardando baixa', CONFIRMADA: 'aguardando baixa' };
const STATUS = { AGENDADA: 'Agendada', CONFIRMADA: 'Confirmada', CONCLUIDA: 'Concluída', FALTA: 'Falta', CANCELADA: 'Cancelada' };
const DATA_VALIDA = /^\d{4}-\d{2}-\d{2}$/;
const idDe = (registro) => registro.id || registro._id;
const dataBr = (data) => data.split('-').reverse().join('/');

/** Agenda, bloqueios e consultas do médico no período, recarregados quando o período muda. */
function useDadosDoCalendario(medicoId, de, ate) {
  const [revisao, setRevisao] = useState(0);
  const chave = `${medicoId}|${de}|${ate}|${revisao}`;
  const [dados, setDados] = useState({ chave: null, erro: null, agenda: null, bloqueios: [], consultas: [] });

  useEffect(() => {
    if (!medicoId) return undefined;
    const controller = new AbortController();
    const opcoes = { signal: controller.signal };
    Promise.all([
      buscarAgenda(medicoId, opcoes),
      listarBloqueios(medicoId, { de, ate, ...opcoes }),
      listarConsultas(medicoId, { de, ate, ...opcoes }),
    ])
      .then(([agenda, bloqueios, consultas]) => setDados({ chave, erro: null, agenda, bloqueios: bloqueios ?? [], consultas: consultas ?? [] }))
      .catch((erro) => { if (!controller.signal.aborted) setDados({ chave, erro, agenda: null, bloqueios: [], consultas: [] }); });
    return () => controller.abort();
  }, [medicoId, de, ate, chave]);

  return { ...dados, carregando: Boolean(medicoId) && dados.chave !== chave, recarregar: () => setRevisao((r) => r + 1) };
}

function textoDoHorario({ estado, consulta, bloqueio }) {
  const paciente = consulta?.pacienteNome ?? 'Consulta';
  if (estado === 'passado') return consulta ? `Indisponível (${DESFECHO[consulta.status] ?? consulta.status})` : 'Indisponível';
  if (estado === 'conflito') return `${paciente} · remarcar`;
  if (estado === 'ocupado') return paciente;
  if (estado === 'bloqueado') return bloqueio?.motivo || 'Bloqueado';
  return consulta ? 'Livre (cancelada)' : 'Livre';
}

/** Texto completo do horário (dica da célula e detalhe da visão do dia). */
function detalheDoHorario({ estado, consulta, bloqueio }) {
  const partes = [];
  if (consulta) partes.push(`${consulta.pacienteNome} · ${consulta.especialidadeNome} · ${STATUS[consulta.status] ?? consulta.status}`);
  if (bloqueio) partes.push(`Bloqueio: ${bloqueio.motivo || 'período bloqueado'}`);
  if (estado === 'conflito') partes.push('Consulta dentro de um bloqueio: remarcar');
  return partes.join(' — ');
}

function Semana({ dias, porDia, aoAbrirDia }) {
  const horas = [...new Set(dias.flatMap((dia) => porDia[dia].map((h) => h.hora)))].sort();
  if (horas.length === 0) return <p className="empty-state" role="status">Sem atendimento nesta semana.</p>;
  return (
    <div className="cal-tabela-wrap">
      <table className="cal-tabela">
        <caption className="sr-only">Horários da semana por dia</caption>
        <thead>
          <tr>
            <th scope="col" className="cal-hora">Hora</th>
            {dias.map((dia) => (
              <th scope="col" key={dia}>
                <button type="button" className="cal-dia-titulo" onClick={() => aoAbrirDia(dia)} aria-label={`Ver o dia ${rotuloDiaCompleto(dia)}`}>
                  {rotuloDia(dia)}
                </button>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {horas.map((hora) => (
            <tr key={hora}>
              <th scope="row" className="cal-hora">{hora}</th>
              {dias.map((dia) => {
                const horario = porDia[dia].find((h) => h.hora === hora);
                if (!horario) return <td key={dia} className="cal-celula cal-celula--fora" />;
                const texto = textoDoHorario(horario);
                return (
                  <td key={dia} className={`cal-celula cal-celula--${horario.estado}`}
                    title={[`${rotuloDia(dia)} ${hora}`, texto, detalheDoHorario(horario)].filter(Boolean).join(' · ')}>
                    <span className="cal-celula__texto">{texto}</span>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Dia({ horarios, canceladas }) {
  if (horarios.length === 0) return <p className="empty-state" role="status">Sem atendimento neste dia.</p>;
  return (
    <>
      <ol className="cal-lista">
        {horarios.map((h) => {
          const info = ESTADOS[h.estado] ?? ESTADOS.livre;
          return (
            <li key={h.inicio} className={`cal-lista__item cal-lista__item--${h.estado}`}>
              <span className="cal-lista__hora">{h.hora} – {h.fim.slice(11)}</span>
              <Badge tone={info.tom}>{h.estado === 'passado' || h.estado === 'livre' ? textoDoHorario(h) : info.rotulo}</Badge>
              <span className="cal-lista__detalhe">{detalheDoHorario(h)}</span>
            </li>
          );
        })}
      </ol>
      {canceladas.length > 0 && (
        <div className="cal-canceladas">
          <h3>Canceladas neste dia</h3>
          <ul>
            {canceladas.map((c) => <li key={c.id}>{c.inicio.slice(11)} · {c.pacienteNome} · {c.especialidadeNome}</li>)}
          </ul>
        </div>
      )}
    </>
  );
}

export default function CalendarioPage() {
  const [params, setParams] = useSearchParams();
  const medicoId = params.get('medicoId') ?? '';
  const visao = params.get('visao') === 'dia' ? 'dia' : 'semana';
  const data = DATA_VALIDA.test(params.get('data') ?? '') ? params.get('data') : hoje();
  const dias = visao === 'semana' ? diasDaSemana(data) : [data];
  const de = dias[0];
  const ate = dias[dias.length - 1];

  const medicos = useRecords('medicos');
  const ativos = medicos.records.filter((m) => m.ativo !== false);
  const dados = useDadosDoCalendario(medicoId, de, ate);

  const ir = (mudancas) => {
    const proximo = { medicoId, visao, data, ...mudancas };
    setParams(Object.fromEntries(Object.entries(proximo).filter(([, valor]) => valor)));
  };
  const passo = visao === 'semana' ? 7 : 1;

  const porDia = useMemo(() => {
    const agora = agoraNoFuso(dados.agenda?.fusoHorario);
    return Object.fromEntries(dias.map((dia) => [dia, horariosDoDia(dados.agenda, dia)
      .map((h) => ({ ...h, ...classificarHorario(h, dados.consultas, dados.bloqueios, agora) }))]));
  }, [dados.agenda, dados.consultas, dados.bloqueios, de, ate]); // eslint-disable-line react-hooks/exhaustive-deps

  const contagem = Object.values(porDia).flat().reduce((total, h) => ({ ...total, [h.estado]: (total[h.estado] ?? 0) + 1 }), {});
  const periodo = visao === 'semana' ? `${dataBr(de).slice(0, 5)} a ${dataBr(ate)}` : rotuloDiaCompleto(data);

  return (
    <div className="page">
      <PageHeader title="Calendário" description="Agenda de cada médico por dia ou semana: horários livres, ocupados e bloqueados." />

      <Card className="search-card">
        <div className="cal-controles">
          <Field as="select" label="Médico" className="search-field" value={medicoId} disabled={medicos.loading}
            onChange={(event) => ir({ medicoId: event.target.value })}>
            <option value="">{medicos.loading ? 'Carregando médicos…' : 'Selecione um médico'}</option>
            {ativos.map((m) => <option key={idDe(m)} value={idDe(m)}>{m.nome}</option>)}
          </Field>
          <div className="cal-nav" role="group" aria-label="Visualização">
            <Button variant={visao === 'semana' ? 'primary' : 'outline'} size="sm" aria-pressed={visao === 'semana'} onClick={() => ir({ visao: 'semana' })}>Semana</Button>
            <Button variant={visao === 'dia' ? 'primary' : 'outline'} size="sm" aria-pressed={visao === 'dia'} onClick={() => ir({ visao: 'dia' })}>Dia</Button>
          </div>
        </div>
        {medicos.error && <Alert tone="info">Não foi possível carregar a lista de médicos ({medicos.error.message}).</Alert>}
      </Card>

      {!medicoId ? (
        <Card><p className="empty-state" role="status">Selecione um médico para ver o calendário.</p></Card>
      ) : (
        <Card>
          <div className="cal-cabecalho">
            <div className="cal-nav">
              <Button variant="outline" size="sm" onClick={() => ir({ data: somarDias(data, -passo) })}>‹ Anterior</Button>
              <Button variant="ghost" size="sm" onClick={() => ir({ data: hoje() })}>Hoje</Button>
              <Button variant="outline" size="sm" onClick={() => ir({ data: somarDias(data, passo) })}>Próximo ›</Button>
              <span className="cal-periodo" aria-live="polite">{periodo}</span>
            </div>
            <div className="cal-legenda" aria-label="Legenda">
              {Object.entries(ESTADOS).map(([estado, info]) => (
                <Badge key={estado} tone={info.tom}>{info.rotulo}{contagem[estado] ? ` (${contagem[estado]})` : ''}</Badge>
              ))}
            </div>
          </div>

          {dados.carregando && <p className="empty-state" role="status">Carregando calendário…</p>}
          {!dados.carregando && dados.erro && (
            <Alert action={<Button variant="outline" size="sm" onClick={dados.recarregar}>Tentar novamente</Button>}>
              Não foi possível carregar o calendário. {dados.erro.message}
            </Alert>
          )}
          {!dados.carregando && !dados.erro && !dados.agenda && (
            <Alert tone="info">Este médico ainda não tem agenda configurada. <Link to={`/agenda?medicoId=${medicoId}`}>Configurar agenda</Link></Alert>
          )}
          {!dados.carregando && !dados.erro && dados.agenda && (visao === 'semana'
            ? <Semana dias={dias} porDia={porDia} aoAbrirDia={(dia) => ir({ visao: 'dia', data: dia })} />
            : <Dia horarios={porDia[data]} canceladas={dados.consultas.filter((c) => c.status === 'CANCELADA' && c.inicio.startsWith(data))} />)}
        </Card>
      )}
    </div>
  );
}
