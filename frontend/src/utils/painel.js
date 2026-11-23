/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

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

function buscaPainel(busca, perfil) {
  const params = new URLSearchParams(busca);
  const seguros = new URLSearchParams();
  for (const campo of ['de', 'ate', 'dia']) if (params.has(campo)) seguros.set(campo, params.get(campo));
  if (perfil === 'SECRETARIA_ADMIN' && params.has('medicoId')) seguros.set('medicoId', params.get('medicoId').toLowerCase());
  return seguros.toString();
}

export function origemPainel(location, usuario) {
  if (!usuario?.id || !['MEDICO', 'SECRETARIA_ADMIN'].includes(usuario.perfil)) return undefined;
  // A origem fica vinculada à conta que abriu o painel, evitando recuperar filtros de
  // navegação deixados por outro usuário.
  const anterior = location.state?.origemPainel;
  const busca = location.pathname === '/' ? location.search
    : anterior?.usuarioId === usuario.id && typeof anterior.busca === 'string' ? anterior.busca : null;
  return busca === null ? undefined : { origemPainel: { usuarioId: usuario.id, busca: buscaPainel(busca, usuario.perfil) } };
}

export function retornoPainel(location, usuario) {
  const origem = origemPainel(location, usuario)?.origemPainel;
  return origem ? `/${origem.busca ? `?${origem.busca}` : ''}` : null;
}

export function atalhosPainel(busca, perfil, hoje) {
  const params = new URLSearchParams(busca);
  const medico = perfil === 'SECRETARIA_ADMIN' ? params.get('medicoId')?.toLowerCase() : undefined;
  const valido = !medico || /^[a-f\d]{24}$/i.test(medico);
  const dia = params.get('dia') ?? hoje;
  const filtro = medico ? new URLSearchParams({ medicoId: medico }) : new URLSearchParams();
  const calendario = new URLSearchParams(filtro);
  calendario.set('visao', 'dia');
  calendario.set('data', dia);
  calendario.set('diaClinica', '1');
  return {
    consultas: valido ? `/consultas${filtro.size ? `?${filtro}` : ''}` : null,
    calendario: valido && dataPainelValida(dia) ? `/calendario?${calendario}` : null,
  };
}
