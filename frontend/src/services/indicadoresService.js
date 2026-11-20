import { api } from './api';

const METRICAS = ['ocupacao', 'faltas', 'cancelamento', 'confirmacao', 'preenchimentoFila'];
const CONTAGENS = ['totalConsultas', 'consultasNaoCanceladas', 'consultasComDesfecho', 'capacidade', 'vagasOcupadas',
  'agendadas', 'confirmadas', 'concluidas', 'faltas', 'canceladas', 'comConfirmacaoRegistrada', 'preenchidasPelaFila'];
const QUALIDADE = ['medicosSemAgenda', 'consultasComProjecaoPendente', 'consultasSemBaseHistorica', 'inscricoesComVinculoInvalido'];
const contagemValida = (valor) => Number.isSafeInteger(valor) && valor >= 0;
const idValido = (valor) => typeof valor === 'string' && /^[a-f\d]{24}$/i.test(valor);

function taxaValida(taxa) {
  if (!taxa || !contagemValida(taxa.numerador) || !contagemValida(taxa.denominador) || taxa.numerador > taxa.denominador) return false;
  if (taxa.situacao === 'DISPONIVEL') return taxa.denominador > 0 && typeof taxa.percentual === 'number'
    && Number.isFinite(taxa.percentual) && taxa.percentual >= 0 && taxa.percentual <= 100;
  if (taxa.situacao === 'SEM_BASE') return taxa.numerador === 0 && taxa.denominador === 0 && taxa.percentual === null;
  return ['DADOS_PENDENTES', 'DADOS_INCOMPLETOS'].includes(taxa.situacao) && taxa.percentual === null;
}

export async function buscarIndicadores({ de, ate, medicoId } = {}, { signal } = {}) {
  const dados = await api.get('/indicadores', { params: { de, ate, medicoId: medicoId || undefined }, signal });
  if (!dados || dados.de !== de || dados.ate !== ate || dados.fusoHorario !== 'America/Sao_Paulo'
    || (dados.medicoId !== null && !idValido(dados.medicoId))
    || (idValido(medicoId) && dados.medicoId?.toLowerCase() !== medicoId.toLowerCase())
    || !METRICAS.every((metrica) => taxaValida(dados[metrica]))
    || !CONTAGENS.every((campo) => contagemValida(dados.contagens?.[campo]))
    || !QUALIDADE.every((campo) => contagemValida(dados.qualidade?.[campo]))
    || typeof dados.qualidade?.capacidadeEstimada !== 'boolean'
    || typeof dados.qualidade?.observacaoCapacidade !== 'string') {
    throw new Error('Não foi possível ler os indicadores recebidos. Tente novamente.');
  }
  return dados;
}
