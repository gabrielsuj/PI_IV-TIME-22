/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { Fragment, useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import Alert from '../components/ui/Alert';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import Field from '../components/ui/Field';
import PageHeader from '../components/ui/PageHeader';
import SeletorMedico from '../components/agenda/SeletorMedico';
import { useAuth } from '../hooks/useAuth';
import { useRecords } from '../hooks/useRecords';
import { buscarAgenda, listarBloqueios } from '../services/agendaService';
import { listarConsultas } from '../services/calendarioService';
import {
  agoraNoFuso, diasDaAgendaNoDiaClinica, diasDaSemana, horariosDoCalendario, rotuloDia, rotuloDiaCompleto, somarDias,
} from '../utils/calendario';
import { FUSO_CLINICA } from '../utils/consulta';
import { hoje } from '../utils/horario';
import { dataPainelValida, origemPainel, retornoPainel } from '../utils/painel';

const ESTADOS = {
  livre: { rotulo: 'Livre', tom: 'success' },
  ocupado: { rotulo: 'Ocupado', tom: 'info' },
  conflito: { rotulo: 'Remarcar', tom: 'warning' },
  bloqueado: { rotulo: 'Bloqueado', tom: 'danger' },
  passado: { rotulo: 'Indisponível', tom: 'neutral' },
};
const DESFECHO = { CONCLUIDA: 'concluída', FALTA: 'falta', CANCELADA: 'cancelada', AGENDADA: 'aguardando baixa', CONFIRMADA: 'aguardando baixa' };
const STATUS = { AGENDADA: 'Agendada', CONFIRMADA: 'Confirmada', CONCLUIDA: 'Concluída', FALTA: 'Falta', CANCELADA: 'Cancelada' };
const idDe = (registro) => registro.id || registro._id;
const dataBr = (data) => data.split('-').reverse().join('/');

function useDadosDoCalendario(medicoId, de, ate, diaClinica) {
  const [revisao, setRevisao] = useState(0);
  const chave = `${medicoId}|${de}|${ate}|${diaClinica}|${revisao}`;
  const [dados, setDados] = useState({ chave: null, erro: null, agenda: null, bloqueios: [], consultas: [] });

  useEffect(() => {
    if (!medicoId) return undefined;
    const controller = new AbortController();
    const opcoes = { signal: controller.signal };
    async function carregar() {
      try {
        const pedidoAgenda = buscarAgenda(medicoId, opcoes);
        const diasLocais = diaClinica ? diasDaAgendaNoDiaClinica(diaClinica, (await pedidoAgenda)?.fusoHorario ?? FUSO_CLINICA) : null;
        if (controller.signal.aborted) return;
        const periodo = { de: diasLocais?.[0] ?? de, ate: diasLocais?.at(-1) ?? ate, ...opcoes };
        const [agenda, bloqueios, consultas] = await Promise.all([
          pedidoAgenda, listarBloqueios(medicoId, periodo), listarConsultas(medicoId, periodo),
        ]);
        if (!controller.signal.aborted) setDados({ chave, erro: null, agenda, bloqueios: bloqueios ?? [], consultas: consultas ?? [], diasLocais });
      } catch (erro) {
        if (!controller.signal.aborted) setDados({ chave, erro, agenda: null, bloqueios: [], consultas: [] });
      }
    }
    carregar();
    return () => controller.abort();
  }, [medicoId, de, ate, diaClinica, chave]);

  return { ...dados, carregando: Boolean(medicoId) && dados.chave !== chave, recarregar: () => setRevisao((r) => r + 1) };
}

function textoDoHorario({ estado, consulta, bloqueio, foraDaJornada }) {
  const paciente = consulta?.pacienteNome ?? 'Consulta';
  if (estado === 'passado') return consulta ? `Indisponível (${DESFECHO[consulta.status] ?? consulta.status})` : 'Indisponível';
  if (foraDaJornada) return `Remarcar · ${paciente}`;
  if (estado === 'conflito') return `${paciente} · remarcar`;
  if (estado === 'ocupado') return paciente;
  if (estado === 'bloqueado') return bloqueio?.motivo || 'Bloqueado';
  return consulta ? 'Livre (cancelada)' : 'Livre';
}

function detalheDoHorario({ estado, consulta, bloqueio, foraDaJornada }) {
  const partes = [];
  if (consulta) partes.push(`${consulta.pacienteNome} · ${consulta.especialidadeNome} · ${STATUS[consulta.status] ?? consulta.status}`);
  if (bloqueio) partes.push(`Bloqueio: ${bloqueio.motivo || 'período bloqueado'}`);
  if (foraDaJornada) partes.push('Consulta fora da jornada');
  else if (estado === 'conflito') partes.push('Consulta dentro de um bloqueio: remarcar');
  return partes.join(' — ');
}

function Semana({ dias, porDia, aoAbrirDia }) {
  const horas = [...new Set(dias.flatMap((dia) => porDia[dia].map((h) => h.hora)))].sort();
  if (horas.length === 0) return <p className="empty-state" role="status">Sem atendimento nesta semana.</p>;
  return (
    <div className="cal-tabela-wrap" role="region" aria-label="Horários da semana" tabIndex={0}>
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
                if (!horario) return <td key={dia} className="cal-celula cal-celula--fora"><span className="sr-only">Sem atendimento</span><span aria-hidden="true">—</span></td>;
                const texto = textoDoHorario(horario);
                const info = ESTADOS[horario.estado];
                const detalhe = detalheDoHorario(horario);
                return (
                  <td key={dia} className={`cal-celula cal-celula--${horario.estado}`}
                    title={[`${rotuloDia(dia)} ${hora}`, texto, detalhe].filter(Boolean).join(' · ')}>
                    <span className="cal-celula__estado"><Badge tone={info.tom}>{info.rotulo}</Badge></span>
                    {texto !== info.rotulo && <span className="cal-celula__texto">{texto}</span>}
                    {detalhe && <span className="cal-celula__detalhe">{detalhe}</span>}
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

function Dia({ horarios, canceladas, agendaUrl, podeAgendar }) {
  const livres = horarios.filter((horario) => horario.estado === 'livre').length;
  const futuro = horarios.some((horario) => horario.estado !== 'passado');
  return (
    <>
      {horarios.length === 0 ? (
        <div className="empty-state" role="status">
          <p>Sem atendimento neste dia.</p>
          <p>Escolha outra data ou ajuste os dias e horários da jornada.</p>
          <Link to={agendaUrl}>Configurar agenda</Link>
        </div>
      ) : (
        <>
          <div className="cal-disponibilidade" role="status">
            {livres > 0 ? <p>{livres} {livres === 1 ? 'horário livre neste dia.' : 'horários livres neste dia.'}</p>
              : <p>{futuro ? 'Sem vagas disponíveis neste dia. Os horários estão ocupados, bloqueados ou precisam de remarcação.' : 'Os horários deste dia já passaram e estão indisponíveis para agendamento.'}</p>}
            {livres > 0 && podeAgendar && <Button to="/nova-consulta" variant="outline" size="sm">Agendar consulta</Button>}
          </div>
          <ol className="cal-lista" aria-label="Horários do dia">
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
        </>
      )}
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

function Dias({ dias, porDia, consultas, diaClinica, agendaUrl, podeAgendar }) {
  const multiplosDias = dias.length > 1;
  return (
    <>
      {multiplosDias && <p className="page__lead">O dia {dataBr(diaClinica)} no fuso da clínica abrange os dias abaixo no fuso da agenda.</p>}
      {dias.map((dia) => (
        <Fragment key={dia}>
          {multiplosDias && <h2>{rotuloDiaCompleto(dia)}</h2>}
          <Dia horarios={porDia[dia]} canceladas={consultas.filter((consulta) => consulta.status === 'CANCELADA' && consulta.inicio.startsWith(dia))}
            agendaUrl={agendaUrl} podeAgendar={podeAgendar} />
        </Fragment>
      ))}
    </>
  );
}

export default function CalendarioPage() {
  const [params, setParams] = useSearchParams();
  const { usuario } = useAuth();
  const location = useLocation();
  const origem = origemPainel(location, usuario);
  const retorno = retornoPainel(location, usuario);
  const agendaPropria = usuario.perfil === 'MEDICO';
  const medicoId = agendaPropria ? 'meu' : params.get('medicoId') ?? '';
  const visao = params.get('visao') === 'dia' ? 'dia' : 'semana';
  const data = dataPainelValida(params.get('data')) ? params.get('data') : hoje();
  // O atalho do painel marca o dia da clínica para preservar a data selecionada mesmo
  // quando a agenda usa outro fuso.
  const diaClinica = visao === 'dia' && params.get('diaClinica') === '1' ? data : null;
  const dias = useMemo(() => visao === 'semana' ? diasDaSemana(data) : [data], [data, visao]);
  const de = dias[0];
  const ate = dias[dias.length - 1];

  const medicos = useRecords(agendaPropria ? null : 'medicos');
  const ativos = medicos.records.filter((m) => m.ativo !== false);
  const medicoIndisponivel = !agendaPropria && medicoId && !medicos.loading && !medicos.error && !ativos.some((medico) => idDe(medico) === medicoId);
  const dados = useDadosDoCalendario(medicoIndisponivel ? '' : medicoId, de, ate, diaClinica);
  const diasAgenda = dados.carregando ? dias : dados.diasLocais ?? dias;

  const ir = (mudancas) => {
    const proximo = { medicoId: agendaPropria ? undefined : medicoId, visao, data,
      diaClinica: diaClinica && mudancas.visao !== 'semana' ? '1' : undefined, ...mudancas };
    setParams(Object.fromEntries(Object.entries(proximo).filter(([, valor]) => valor)), { state: origem });
  };
  const passo = visao === 'semana' ? 7 : 1;

  const porDia = useMemo(() => {
    if (dados.carregando) return {};
    const agora = agoraNoFuso(dados.agenda?.fusoHorario);
    return Object.fromEntries(diasAgenda.map((dia) => [
      dia, horariosDoCalendario(dados.agenda, dia, dados.consultas, dados.bloqueios, agora),
    ]));
  }, [dados.carregando, dados.agenda, dados.consultas, dados.bloqueios, diasAgenda]);

  const contagem = Object.values(porDia).flat().reduce((total, h) => ({ ...total, [h.estado]: (total[h.estado] ?? 0) + 1 }), {});
  const periodo = visao === 'semana' ? `${dataBr(de).slice(0, 5)} a ${dataBr(ate)}` : rotuloDiaCompleto(data);
  const agendaUrl = agendaPropria ? '/agenda' : `/agenda?medicoId=${encodeURIComponent(medicoId)}`;
  const jornadaVazia = dados.agenda && !dados.agenda.jornadas?.some((jornada) => jornada.faixas?.length > 0);

  return (
    <div className="page calendario-page">
      <PageHeader title={agendaPropria ? 'Meu calendário' : 'Calendário'} description="Agenda de cada médico por dia ou semana: horários livres, ocupados e bloqueados."
        action={retorno && <Button to={retorno} variant="outline">‹ Voltar ao painel</Button>} />
      {medicoId && !medicoIndisponivel && !dados.carregando && dados.agenda && <p className="page__lead">Horários no fuso da agenda: {dados.agenda.fusoHorario}.</p>}

      <div className="cal-controles calendario-selecao">
        {!agendaPropria && (
          <SeletorMedico medicoId={medicoId} medicos={medicos} onChange={(valor) => ir({ medicoId: valor })} />
        )}
        <div className="cal-nav" role="group" aria-label="Visualização">
          <Button variant={visao === 'semana' ? 'primary' : 'outline'} size="sm" aria-pressed={visao === 'semana'} onClick={() => ir({ visao: 'semana' })}>Semana</Button>
          <Button variant={visao === 'dia' ? 'primary' : 'outline'} size="sm" aria-pressed={visao === 'dia'} onClick={() => ir({ visao: 'dia' })}>Dia</Button>
        </div>
      </div>

      {medicoIndisponivel ? (
        <Alert tone="info">O médico selecionado não está disponível. Selecione um médico ativo para consultar o calendário.</Alert>
      ) : !medicoId ? (
        !medicos.loading && !medicos.error && ativos.length > 0 &&
          <Card><p className="empty-state" role="status">Selecione um médico para ver o calendário.</p></Card>
      ) : (
        <Card>
          <div className="cal-cabecalho">
            <div className="cal-nav" role="group" aria-label={visao === 'semana' ? 'Navegar entre semanas' : 'Navegar entre dias'}>
              <Button variant="outline" size="sm" onClick={() => ir({ data: somarDias(data, -passo) })}>‹ Anterior</Button>
              <Button variant="ghost" size="sm" onClick={() => ir({ data: diaClinica ? agoraNoFuso(FUSO_CLINICA).slice(0, 10) : hoje() })}>Hoje</Button>
              <Button variant="outline" size="sm" onClick={() => ir({ data: somarDias(data, passo) })}>Próximo ›</Button>
              <span className="cal-periodo" aria-live="polite">{periodo}{diaClinica && ' no fuso da clínica'}</span>
            </div>
            <Field type="date" label="Ir para a data" className="cal-data" value={data} min="0001-01-01" max="9999-12-31"
              onChange={(event) => { if (dataPainelValida(event.target.value)) ir({ data: event.target.value }); }} />
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
            <Alert tone="info">Este médico ainda não tem agenda configurada. Configure a jornada para disponibilizar horários de atendimento. <Link to={agendaUrl}>Configurar agenda</Link></Alert>
          )}
          {!dados.carregando && !dados.erro && jornadaVazia && <Alert tone="info">A jornada deste médico está vazia. Defina os dias e horários de atendimento. <Link to={agendaUrl}>Configurar agenda</Link></Alert>}
          {!dados.carregando && !dados.erro && dados.agenda && (visao === 'semana'
            ? <Semana dias={dias} porDia={porDia} aoAbrirDia={(dia) => ir({ visao: 'dia', data: dia })} />
            : <Dias dias={diasAgenda} porDia={porDia} consultas={dados.consultas} diaClinica={diaClinica} agendaUrl={agendaUrl} podeAgendar={!agendaPropria} />)}
        </Card>
      )}
    </div>
  );
}
