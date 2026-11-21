export function indicadores({ de = '2026-11-23', ate = de, medicoId = null } = {}) {
  return {
    de, ate, medicoId, fusoHorario: 'America/Sao_Paulo',
    contagens: { totalConsultas: 6, consultasNaoCanceladas: 5, consultasComDesfecho: 3,
      capacidade: 8, vagasOcupadas: 6, agendadas: 1, confirmadas: 1, concluidas: 2,
      faltas: 1, canceladas: 1, comConfirmacaoRegistrada: 4, preenchidasPelaFila: 2 },
    ocupacao: { numerador: 6, denominador: 8, percentual: 75, situacao: 'DISPONIVEL' },
    faltas: { numerador: 1, denominador: 3, percentual: 33.33, situacao: 'DISPONIVEL' },
    cancelamento: { numerador: 1, denominador: 6, percentual: 16.67, situacao: 'DISPONIVEL' },
    confirmacao: { numerador: 4, denominador: 6, percentual: 66.67, situacao: 'DISPONIVEL' },
    preenchimentoFila: { numerador: 2, denominador: 5, percentual: 40, situacao: 'DISPONIVEL' },
    qualidade: { medicosSemAgenda: 0, consultasComProjecaoPendente: 0, consultasSemBaseHistorica: 0,
      inscricoesComVinculoInvalido: 0, capacidadeEstimada: true,
      observacaoCapacidade: 'Capacidade calculada pela agenda atual; alterações históricas de jornada, duração e bloqueios não são reconstruídas.' },
  };
}

export function indicadoresDaUrl(url, medicoId = null) {
  const params = new URL(url).searchParams;
  return indicadores({ de: params.get('de'), ate: params.get('ate'), medicoId: params.get('medicoId') || medicoId });
}
