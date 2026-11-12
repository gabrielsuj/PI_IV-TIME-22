export function formatarHorarioAgendamento(horario, fusoHorario) {
  try {
    if (!fusoHorario) return null;
    const instante = (valor) => typeof valor === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/.test(valor) ? Date.parse(valor) : NaN;
    const inicio = instante(horario?.inicio);
    const fim = instante(horario?.fim);
    if (!Number.isFinite(inicio) || !Number.isFinite(fim) || inicio >= fim) return null;
    const hora = new Intl.DateTimeFormat('pt-BR', { timeZone: fusoHorario, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
    const dia = new Intl.DateTimeFormat('pt-BR', { timeZone: fusoHorario, dateStyle: 'short' });
    const fimExibido = `${hora.format(fim)}${dia.format(inicio) === dia.format(fim) ? '' : ` (${dia.format(fim)})`}`;
    return { inicio, data: dia.format(inicio), periodo: `${hora.format(inicio)} às ${fimExibido}` };
  } catch {
    return null;
  }
}
