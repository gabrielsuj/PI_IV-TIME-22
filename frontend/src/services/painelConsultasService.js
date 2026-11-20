import { buscarAgenda } from './agendaService';
import { listarConsultas as listarCalendario } from './calendarioService';
import { LIMITE_LISTAGEM, listarConsultas } from './consultasService';
import { FUSO_CLINICA, STATUS_ATIVOS, STATUS_CONSULTA } from '../utils/consulta';

export const LIMITE_PROXIMAS_PAINEL = 5;
const texto = (valor) => typeof valor === 'string' && valor.trim().length > 0;
const NOMES = ['pacienteNome', 'medicoNome', 'especialidadeNome'];

function validarConsultas(consultas, local = false) {
  const ids = new Set();
  const formato = local ? horarioLocal : horarioUtc;
  if (!Array.isArray(consultas) || consultas.some((consulta) => {
    if (!consulta || !texto(consulta.id) || !texto(consulta.medicoId) || ids.has(consulta.id) || !Object.hasOwn(STATUS_CONSULTA, consulta.status)
      || !NOMES.every((campo) => consulta[campo] == null || typeof consulta[campo] === 'string')
      || !formato(consulta.inicio) || !formato(consulta.fim) || Date.parse(consulta.inicio + (local ? 'Z' : ''))
        >= Date.parse(consulta.fim + (local ? 'Z' : ''))) return true;
    ids.add(consulta.id);
    return false;
  })) throw new Error('Não foi possível ler as consultas recebidas. Tente novamente.');
}

function horarioLocal(valor) {
  return typeof valor === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(valor)
    && Number.isFinite(Date.parse(`${valor}Z`)) && new Date(`${valor}Z`).toISOString().slice(0, 16) === valor;
}

const horarioUtc = (valor) => typeof valor === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/.test(valor) && Number.isFinite(Date.parse(valor));

export async function buscarAgendaDoDia({ medicoId, dia }, { signal } = {}) {
  const agenda = await buscarAgenda(medicoId, { signal });
  signal?.throwIfAborted();
  if (agenda !== null && (!texto(agenda?.medicoId) || medicoId !== 'meu' && agenda.medicoId !== medicoId))
    throw new Error('A agenda recebida não corresponde ao médico selecionado. Tente novamente.');
  const fusoHorario = agenda === null ? FUSO_CLINICA : agenda?.fusoHorario;
  if (!texto(fusoHorario)) throw new Error('Não foi possível ler a agenda recebida. Tente novamente.');
  try { new Intl.DateTimeFormat('pt-BR', { timeZone: fusoHorario }); }
  catch { throw new Error('Não foi possível ler o fuso da agenda recebida. Tente novamente.'); }
  const consultas = await listarCalendario(medicoId, { de: dia, ate: dia, fusoHorario: FUSO_CLINICA, signal });
  validarConsultas(consultas, true);
  const cadastroId = medicoId === 'meu' ? agenda?.medicoId : medicoId;
  if (cadastroId && consultas.some((consulta) => consulta.medicoId !== cadastroId))
    throw new Error('As consultas recebidas não correspondem ao médico selecionado. Tente novamente.');
  return { consultas: [...consultas].sort((a, b) => a.inicio.localeCompare(b.inicio) || a.id.localeCompare(b.id)),
    fusoHorario: FUSO_CLINICA, semAgenda: agenda === null };
}

export async function buscarProximasConsultas({ medicoId } = {}, { signal } = {}) {
  const consultas = await listarConsultas({ periodo: 'proximas', medicoId, somenteAtivas: true }, { signal });
  validarConsultas(consultas);
  if (consultas.length > LIMITE_LISTAGEM || consultas.some((consulta) =>
    !STATUS_ATIVOS.includes(consulta.status) || medicoId && consulta.medicoId !== medicoId))
    throw new Error('A listagem de próximas consultas recebida é inválida. Tente novamente.');
  const ordenadas = [...consultas].sort((a, b) => Date.parse(a.inicio) - Date.parse(b.inicio) || a.id.localeCompare(b.id));
  return { consultas: ordenadas.slice(0, LIMITE_PROXIMAS_PAINEL), totalRecebido: consultas.length,
    limiteAtingido: consultas.length === LIMITE_LISTAGEM };
}
