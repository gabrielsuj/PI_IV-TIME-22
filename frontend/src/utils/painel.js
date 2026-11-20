export function dataPainelValida(valor) {
  if (typeof valor !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(valor) || valor.startsWith('0000')) return false;
  const data = new Date(`${valor}T00:00:00Z`);
  return Number.isFinite(data.getTime()) && data.toISOString().slice(0, 10) === valor;
}

export function erroPeriodoPainel(de, ate) {
  if (!dataPainelValida(de) || !dataPainelValida(ate)) return 'Informe datas válidas para o período dos indicadores.';
  if (ate < de) return 'O fim dos indicadores deve ser igual ou posterior ao início.';
  if ((Date.parse(`${ate}T00:00:00Z`) - Date.parse(`${de}T00:00:00Z`)) / 86400000 + 1 > 62)
    return 'O período dos indicadores deve ter no máximo 62 dias.';
  return null;
}
