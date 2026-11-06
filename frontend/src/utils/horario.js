/** Dias da semana no padrão ISO 8601 (1 = segunda … 7 = domingo), o mesmo usado pela API. */
export const DIAS_SEMANA = [
  { valor: 1, nome: 'Segunda-feira' },
  { valor: 2, nome: 'Terça-feira' },
  { valor: 3, nome: 'Quarta-feira' },
  { valor: 4, nome: 'Quinta-feira' },
  { valor: 5, nome: 'Sexta-feira' },
  { valor: 6, nome: 'Sábado' },
  { valor: 7, nome: 'Domingo' },
];

/** 480 → "08:00". 1440 (meia-noite do fim do dia) → "00:00". */
export function minutosParaHora(minutos) {
  const total = minutos % 1440;
  const horas = String(Math.floor(total / 60)).padStart(2, '0');
  const resto = String(total % 60).padStart(2, '0');
  return `${horas}:${resto}`;
}

/** "08:00" → 480. Com { fim: true }, "00:00" vira 1440 (atendimento até a meia-noite). */
export function horaParaMinutos(hora, { fim = false } = {}) {
  const [horas, minutos] = hora.split(':').map(Number);
  const total = horas * 60 + minutos;
  return fim && total === 0 ? 1440 : total;
}

/** "2026-11-10" → "2026-11-11" */
export function diaSeguinte(data) {
  const dia = new Date(`${data}T00:00:00Z`);
  dia.setUTCDate(dia.getUTCDate() + 1);
  return dia.toISOString().slice(0, 10);
}

/** Data de hoje no fuso do navegador, no formato AAAA-MM-DD. */
export function hoje() {
  return new Date().toLocaleDateString('sv-SE');
}

function dataBr(data) {
  const [ano, mes, dia] = data.split('-');
  return `${dia}/${mes}/${ano}`;
}

/** Texto amigável para um período "AAAA-MM-DDTHH:MM" → "AAAA-MM-DDTHH:MM". */
export function formatarPeriodo(inicio, fim) {
  const [dataIni, horaIni] = inicio.split('T');
  const [dataFim, horaFim] = fim.split('T');
  if (horaIni === '00:00' && horaFim === '00:00') {
    const ultimoDia = new Date(`${dataFim}T00:00:00Z`);
    ultimoDia.setUTCDate(ultimoDia.getUTCDate() - 1);
    const ultimo = ultimoDia.toISOString().slice(0, 10);
    return ultimo === dataIni ? `${dataBr(dataIni)} (dia inteiro)` : `${dataBr(dataIni)} a ${dataBr(ultimo)} (dias inteiros)`;
  }
  if (dataIni === dataFim) return `${dataBr(dataIni)}, ${horaIni} às ${horaFim}`;
  return `${dataBr(dataIni)} ${horaIni} até ${dataBr(dataFim)} ${horaFim}`;
}