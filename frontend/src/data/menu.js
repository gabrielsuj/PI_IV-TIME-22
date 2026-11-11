/** Nome de exibição de cada perfil devolvido pela API. */
export const NOME_PERFIL = {
  SECRETARIA_ADMIN: 'Secretaria / Administrador',
  MEDICO: 'Médico',
  PACIENTE: 'Paciente',
};

const TODOS = ['SECRETARIA_ADMIN', 'MEDICO', 'PACIENTE'];

/**
 * Itens do menu lateral e os perfis que enxergam cada um.
 *
 * O servidor continua sendo quem bloqueia o acesso (403, ver api/Rotas.java);
 * esta lista só evita mostrar no menu o que o perfil não pode usar.
 *
 * TODO(Gabriel): ajustar `perfis` de cada item conforme a matriz de permissões.
 * Hoje todos os itens aparecem para os três perfis.
 */
export const NAV_ITEMS = [
  { to: '/', label: 'Início', end: true, perfis: TODOS },
  { to: '/pacientes', label: 'Pacientes', perfis: TODOS },
  { to: '/medicos', label: 'Médicos', perfis: TODOS },
  { to: '/especialidades', label: 'Especialidades', perfis: TODOS },
  { to: '/agenda', label: 'Agenda', perfis: TODOS },
  { to: '/perfis', label: 'Perfis de acesso', perfis: TODOS },
  { to: '/status', label: 'Status do sistema', perfis: TODOS },
];

export function itensDoMenu(perfil) {
  return NAV_ITEMS.filter((item) => item.perfis.includes(perfil));
}
