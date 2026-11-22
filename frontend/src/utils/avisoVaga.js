/**
 * Textos e rotas dos avisos de vaga (#58).
 * Contrato do campo `motivo`: docs/api/fila-espera.md, seção 7.1.
 *
 * @author Murilo Moraes (criador)
 */

/**
 * Explica ao paciente por que um aviso EXPIRADO deixou de valer.
 *
 * @param {'VAGA_OCUPADA'|'PRAZO_ENCERRADO'|'OPORTUNIDADE_DESATUALIZADA'|null|undefined} motivo
 *   vem do backend; pode faltar se a API for mais antiga.
 * @returns {{ rotulo: string, texto: string, buscarOutra: boolean }}
 *   - rotulo: texto curto que aparece embaixo do selo "Expirado" na tabela (ex.: "Vaga ocupada").
 *   - texto: frase completa que aparece no alerta do modal e após falha na confirmação.
 *   - buscarOutra: se a tela deve oferecer o botão "Buscar outra vaga".
 */
export function explicarExpiracao(motivo) {
  switch (motivo) {
    case 'VAGA_OCUPADA':
      return {
        rotulo: 'Vaga ocupada',
        texto: 'Outro paciente confirmou este horário antes de você. Sua inscrição continua na fila para a próxima vaga.',
        buscarOutra: true,
      };
    case 'PRAZO_ENCERRADO':
      // Sem busca: o horário já passou ou a inscrição venceu; o caminho é a fila.
      return {
        rotulo: 'Prazo encerrado',
        texto: 'O prazo deste aviso terminou. Confira sua inscrição na fila de espera ou faça uma nova.',
        buscarOutra: false,
      };
    case 'OPORTUNIDADE_DESATUALIZADA':
      return {
        rotulo: 'Oferta desatualizada',
        texto: 'A agenda do médico mudou e este horário não é mais oferecido. Busque outra vaga com o mesmo médico ou especialidade.',
        buscarOutra: true,
      };
    default:
      return {
        rotulo: 'Indisponível',
        texto: 'A vaga deste aviso não está mais disponível.',
        buscarOutra: true,
      };
  }
}

/**
 * Leva o paciente para "Nova consulta" já filtrada pelo médico e pela especialidade do aviso.
 * Sem esses dados, abre a busca vazia.
 */
export function buscaDeOutraVaga(aviso) {
  const filtros = {};
  if (aviso?.especialidadeId) filtros.especialidadeId = aviso.especialidadeId;
  if (aviso?.especialidadeId && aviso?.medicoId) filtros.medicoId = aviso.medicoId;
  return { to: '/nova-consulta', state: Object.keys(filtros).length ? { buscaVaga: filtros } : undefined };
}
