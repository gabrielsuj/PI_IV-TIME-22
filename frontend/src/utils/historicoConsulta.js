import { FUSO_CLINICA, STATUS_CONSULTA } from './consulta';

export const EVENTOS_HISTORICO = {
  CRIACAO: 'Consulta criada', CONFIRMACAO: 'Consulta confirmada', ALTERACAO: 'Consulta alterada',
  CANCELAMENTO: 'Consulta cancelada', CONCLUSAO: 'Consulta concluída', FALTA: 'Falta registrada',
  SINALIZACAO_FILA: 'Fila de espera sinalizada',
};
const PERFIS = { PACIENTE: 'Paciente', MEDICO: 'Médico', SECRETARIA_ADMIN: 'Secretaria / Administrador',
  SECRETARIA: 'Secretaria', ADMINISTRADOR: 'Administrador' };
const CAMPOS = { pacienteId: 'Paciente', medicoId: 'Médico', especialidadeId: 'Especialidade', inicio: 'Início da consulta',
  fim: 'Fim da consulta', dataHora: 'Horário da consulta', dataHoraConsulta: 'Horário da consulta',
  status: 'Status', motivoCancelamento: 'Motivo do cancelamento' };
const DATAS = new Set(['inicio', 'fim', 'dataHora', 'dataHoraConsulta']);
const REFERENCIAS = new Set(['pacienteId', 'medicoId', 'especialidadeId']);
const dataHora = new Intl.DateTimeFormat('pt-BR', { timeZone: FUSO_CLINICA, dateStyle: 'short', timeStyle: 'short' });

export function dataHoraDoHistorico(valor) {
  const instante = typeof valor === 'string' ? Date.parse(valor) : NaN;
  return Number.isFinite(instante) ? dataHora.format(new Date(instante)) : null;
}

export const statusDoHistorico = (valor) => STATUS_CONSULTA[valor]?.rotulo || 'Não informado';

export function responsavelDoHistorico(ator) {
  if (ator?.perfil === 'SISTEMA') return 'Sistema';
  const nome = typeof ator?.nome === 'string' && ator.nome.trim() ? ator.nome.trim() : 'Responsável não identificado';
  const perfil = typeof ator?.perfil === 'string' ? ator.perfil : '';
  return Object.hasOwn(PERFIS, perfil) ? `${nome} · ${PERFIS[perfil]}` : nome;
}

function valorDaAlteracao(campo, valor) {
  if (valor == null) return 'Não informado';
  if (DATAS.has(campo)) return dataHoraDoHistorico(valor) || 'Data não informada';
  if (campo === 'status') return statusDoHistorico(valor);
  return typeof valor === 'string' || (typeof valor === 'number' && Number.isFinite(valor)) ? String(valor) : 'Não informado';
}

export function descricaoDaAlteracao(alteracao) {
  const campo = alteracao?.campo;
  if (typeof campo !== 'string' || !Object.hasOwn(CAMPOS, campo)) return 'Outro dado da consulta foi alterado.';
  if (REFERENCIAS.has(campo)) return `${CAMPOS[campo]} ${campo === 'especialidadeId' ? 'alterada' : 'alterado'}.`;
  return `${CAMPOS[campo]}: ${valorDaAlteracao(campo, alteracao.valorAnterior)} → ${valorDaAlteracao(campo, alteracao.valorNovo)}.`;
}
