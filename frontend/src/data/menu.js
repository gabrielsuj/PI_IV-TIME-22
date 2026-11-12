/** Nome de exibição de cada perfil devolvido pela API. */
export const NOME_PERFIL = {
  SECRETARIA_ADMIN: 'Secretaria / Administrador',
  MEDICO: 'Médico',
  PACIENTE: 'Paciente',
};

const TODOS = ['SECRETARIA_ADMIN', 'MEDICO', 'PACIENTE'];
const EQUIPE = ['SECRETARIA_ADMIN', 'MEDICO'];
const SECRETARIA = ['SECRETARIA_ADMIN'];
const AGENDAMENTO = ['SECRETARIA_ADMIN', 'PACIENTE'];

/**
 * Itens do menu lateral e os perfis que podem abrir cada tela.
 *
 * Regra: cadastros-base (médicos, especialidades) e a área técnica (status)
 * ficam só com a secretaria. Médico usa pacientes (consulta) e agenda.
 * Secretaria e paciente podem iniciar agendamento; a tela com as próprias
 * consultas do paciente entra com a #30.
 *
 * O servidor continua sendo quem bloqueia o acesso aos dados (403, ver
 * api/Rotas.java); aqui decidimos o que aparece no menu e quais telas abrem.
 */
export const NAV_ITEMS = [
  { to: '/', label: 'Início', end: true, perfis: TODOS },
  { to: '/nova-consulta', label: 'Nova consulta', perfis: AGENDAMENTO },
  { to: '/pacientes', label: 'Pacientes', perfis: EQUIPE },
  { to: '/medicos', label: 'Médicos', perfis: SECRETARIA },
  { to: '/especialidades', label: 'Especialidades', perfis: SECRETARIA },
  { to: '/agenda', label: 'Agenda', perfis: EQUIPE },
  { to: '/perfis', label: 'Perfis de acesso', perfis: TODOS },
  { to: '/status', label: 'Status do sistema', perfis: SECRETARIA },
];

export function itensDoMenu(perfil) {
  return NAV_ITEMS.filter((item) => item.perfis.includes(perfil));
}

/** Se o perfil pode abrir a tela do endereço. Endereços fora do menu (ex.: 404) ficam liberados. */
export function podeAbrir(pathname, perfil) {
  const item = NAV_ITEMS.find(({ to }) => (to === '/' ? pathname === '/' : pathname === to || pathname.startsWith(`${to}/`)));
  return !item || item.perfis.includes(perfil);
}
