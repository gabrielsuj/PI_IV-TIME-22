# Dicionário de Dados da Coleção `notificacoes` do Banco de Dados MongoDB do ConsultaFácil – Sistema de Agendamento de Consultas com Fila de Espera para Clínicas e Consultórios

## Histórico de Versões/Revisões

| Data | Autor | Versão | Comentários |
|------|-------|--------|-------------|
| 2026-11-07 | Davi Vitoreti | v1.0.0 | Versão inicial da coleção `notificacoes`: campos, referências, status, regras por status da consulta e índices. |

## Entidades

A seguir é documentada a entidade (coleção MongoDB) e seus dados (atributos) necessários para suportar confirmações, lembretes e avisos de vaga liberada (itens I9 e I7 da ideação). No MVP todas as notificações são **simuladas**: apresentadas no sistema ou enviadas como e-mail de teste. A integração com WhatsApp e SMS reais (I10) é evolução futura.

> Observação: a coleção `notificacoes` não consta na lista de coleções da seção 3.2.3 do relatório de ideação (pacientes, medicos, especialidades, agendas, consultas, filaEspera e historico). Ela deve ser acrescentada à lista e ao script de inicialização do banco.

### 1. Notificacao (coleção `notificacoes`)

Descrição: Representa uma mensagem gerada pelo sistema para um paciente ou médico em decorrência de um evento de uma consulta ou da fila de espera. Cada documento é uma mensagem para **um** destinatário em **um** canal, o que permite acompanhar entrega, leitura e confirmação individualmente. Lembretes são agendados com antecedência (`agendadaPara`) e podem ser múltiplos para a mesma consulta, pois o envio de várias notificações mostrou-se mais eficaz que o de uma única (Robotham et al., 2016). As mensagens não devem expor dados sensíveis (ver comentário do campo `mensagem`).

Atributos:

| Nome | Tipo | Obrigatoriedade | Exemplos/Comentários |
|------|------|-----------------|----------------------|
| `_id` | ObjectId | Sim | Gerado automaticamente pelo MongoDB. Ex.: `6650f1a2c3d4e5f607184401`. |
| `destinatarioPerfil` | String (lista literal e estática) | Sim | Valores: `PACIENTE`, `MEDICO`. Define em qual coleção `destinatarioId` deve ser procurado. A secretaria acompanha os avisos pelo painel e pelo histórico, sem receber notificações próprias no MVP. |
| `destinatarioId` | ObjectId | Sim | Referência a `pacientes._id` (quando `PACIENTE`) ou `medicos._id` (quando `MEDICO`). |
| `tipo` | String (lista literal e estática) | Sim | Valores: `CONFIRMACAO_AGENDAMENTO`, `LEMBRETE_CONSULTA`, `CANCELAMENTO_CONSULTA`, `VAGA_LIBERADA`. Ver tabela "Tipo × status da consulta". |
| `canal` | String (lista literal e estática) | Sim | Valores no MVP: `SISTEMA` (notificação interna) e `EMAIL_TESTE`. `SMS` e `WHATSAPP` ficam reservados para evolução futura e não devem ser usados agora. |
| `simulada` | Boolean | Sim | `true` em todas as notificações do MVP. Permite distinguir, no futuro, envios reais de simulados. |
| `consultaId` | ObjectId | Sim | Referência a `consultas._id`. Em `VAGA_LIBERADA`, é a consulta `CANCELADA` que originou a vaga. |
| `filaEsperaId` | ObjectId ou null | Condicional | Referência a `filaEspera._id`. Obrigatório quando `tipo = VAGA_LIBERADA`; `null` nos demais tipos. |
| `statusConsulta` | String (lista literal e estática) | Sim | Status da consulta **no momento da criação** da notificação (cópia): `AGENDADA`, `CONFIRMADA`, `CANCELADA`, `CONCLUIDA` ou `FALTA` (definições em `historico.md`). Serve para decidir se o envio ainda faz sentido. |
| `antecedenciaHoras` | Inteiro (Int32) ou null | Condicional | Obrigatório quando `tipo = LEMBRETE_CONSULTA` (ex.: `48`, `24`; valores a confirmar); `null` nos demais tipos. Deve ser gravado como `null` explicitamente. |
| `titulo` | String | Sim | Até 120 caracteres. Ex.: "Lembrete de consulta". |
| `mensagem` | String | Sim | Até 500 caracteres. Deve apresentar de forma objetiva data, horário e médico. **Não deve conter** CPF/documento, motivo, diagnóstico ou outro dado de saúde, principalmente no canal `EMAIL_TESTE`. |
| `dadosConsulta` | Object | Sim | Cópia dos dados exibidos: `{ dataHora: Date (UTC), medicoNome: String }`. Mantém a mensagem correta mesmo que a consulta seja alterada depois. |
| `status` | String (lista literal e estática) | Sim | Valores: `AGENDADA`, `ENVIADA`, `FALHA`, `CANCELADA`. Ver seção "Status da notificação". Esse é o status de **entrega**, não o da consulta. |
| `agendadaPara` | Date (UTC) | Sim | Momento programado do envio. Para notificações imediatas, igual a `criadoEm`. |
| `enviadaEm` | Date (UTC) ou null | Condicional | Obrigatório quando `status = ENVIADA`. |
| `tentativas` | Inteiro (Int32) | Sim | Número de tentativas de envio. Padrão `0`; máximo `3`. |
| `erro` | String ou null | Condicional | Obrigatório quando `status = FALHA`: motivo resumido da falha. |
| `lidaEm` | Date (UTC) ou null | Não | Preenchido quando o destinatário abre a notificação no sistema. |
| `confirmadaEm` | Date (UTC) ou null | Não | Apenas para `LEMBRETE_CONSULTA`: momento em que o paciente confirmou presença. Ao preenchê-lo, a consulta passa a `CONFIRMADA` e o evento `CONFIRMACAO` é registrado em `historico`. Base da taxa de confirmação dos lembretes. |
| `criadoEm` | Date (UTC) | Sim | Momento de criação do documento. |

## Referências

| Campo | Coleção referenciada | Cardinalidade | Obrigatória | Observação |
|-------|----------------------|---------------|-------------|------------|
| `destinatarioId` | `pacientes` ou `medicos` (conforme `destinatarioPerfil`) | N:1 | Sim | Referência polimórfica. |
| `consultaId` | `consultas` | N:1 | Sim | Uma consulta pode ter várias notificações (confirmação, lembretes, cancelamento). |
| `filaEsperaId` | `filaEspera` | N:1 | Condicional | Apenas em `VAGA_LIBERADA`. A coleção `filaEspera` referencia de volta esta notificação em `ultimaSinalizacao.notificacaoId`. |

A existência dos documentos referenciados é validada na camada de serviços da API.

## Status da notificação

| Status | Significado | Final? |
|--------|-------------|--------|
| `AGENDADA` | Criada e aguardando o momento de envio (`agendadaPara`). | Não |
| `ENVIADA` | Entregue no sistema ou como e-mail de teste (`enviadaEm` preenchido). | Sim |
| `FALHA` | Não foi possível enviar após as tentativas permitidas (`erro` preenchido). | Sim |
| `CANCELADA` | Descartada antes do envio porque deixou de fazer sentido (ex.: consulta cancelada). | Sim |

Transições permitidas: `AGENDADA` → `ENVIADA`, `AGENDADA` → `FALHA` e `AGENDADA` → `CANCELADA`. Notificações imediatas são criadas já em `AGENDADA` e enviadas pelo mesmo fluxo, para manter um único caminho de envio.

## Relação com o status da consulta

Tipo × status da consulta aceito em `statusConsulta` na criação:

| Tipo | Quando é criada | `statusConsulta` aceito | Destinatário |
|------|-----------------|--------------------------|--------------|
| `CONFIRMACAO_AGENDAMENTO` | Consulta criada (evento `CRIACAO`). | `AGENDADA` | Paciente |
| `LEMBRETE_CONSULTA` | Junto com a criação da consulta, com `agendadaPara` = data/hora da consulta − `antecedenciaHoras`. | `AGENDADA` ou `CONFIRMADA` | Paciente |
| `CANCELAMENTO_CONSULTA` | Consulta passa a `CANCELADA`. | `CANCELADA` | Demais partes (paciente e/ou médico, exceto quem cancelou) |
| `VAGA_LIBERADA` | Interessado da fila é sinalizado após um cancelamento. | `CANCELADA` (consulta de origem) | Paciente da fila |

Regras por mudança de status da consulta:

| Consulta passa a | Efeito em `notificacoes` |
|------------------|--------------------------|
| `AGENDADA` | Cria `CONFIRMACAO_AGENDAMENTO` e os `LEMBRETE_CONSULTA` programados. |
| `CONFIRMADA` | Nenhuma notificação nova. Lembretes ainda pendentes continuam. |
| `CANCELADA` | Lembretes `AGENDADA` da consulta passam a `CANCELADA`; criam-se `CANCELAMENTO_CONSULTA` e, para a fila, `VAGA_LIBERADA`. |
| `CONCLUIDA` ou `FALTA` | Lembretes pendentes remanescentes passam a `CANCELADA`. |

Antes de enviar um lembrete, o serviço relê a consulta: se o status atual não for `AGENDADA` ou `CONFIRMADA`, a notificação passa a `CANCELADA` sem envio.

## Índices

| Nome | Chaves | Opções | Finalidade |
|------|--------|--------|------------|
| `idx_notif_destinatario` | `{ destinatarioPerfil: 1, destinatarioId: 1, criadoEm: -1 }` | — | Caixa de notificações do usuário, da mais recente para a mais antiga; contagem de não lidas filtrando `lidaEm: null`. |
| `idx_notif_envio_pendente` | `{ agendadaPara: 1 }` | `partialFilterExpression: { status: "AGENDADA" }` | Rotina de envio: buscar notificações vencidas. O índice é pequeno, pois só contém as pendentes. |
| `idx_notif_consulta` | `{ consultaId: 1, tipo: 1, status: 1 }` | — | Listar notificações de uma consulta e cancelar lembretes pendentes quando ela é cancelada, concluída ou marcada como falta. |
| `uq_notif_idempotencia` | `{ consultaId: 1, tipo: 1, destinatarioId: 1, canal: 1, antecedenciaHoras: 1 }` | `unique: true` | Impedir notificações duplicadas em reprocessamentos ou novas tentativas. Para tipos sem antecedência, `antecedenciaHoras` é `null` e vale uma por consulta/tipo/destinatário/canal. Se um paciente tiver duas entradas de fila compatíveis com a mesma vaga, gera-se apenas uma `VAGA_LIBERADA`. |
| `idx_notif_lembretes_periodo` | `{ agendadaPara: 1 }` | `partialFilterExpression: { tipo: "LEMBRETE_CONSULTA" }` | Indicador do painel: taxa de confirmação dos lembretes (`confirmadaEm` preenchido) em um período. |

Não é usado índice TTL: as notificações são mantidas para os indicadores. Uma política de retenção pode ser definida após a validação com as clínicas.

Script de criação (mongosh):

```javascript
db.notificacoes.createIndex({ destinatarioPerfil: 1, destinatarioId: 1, criadoEm: -1 }, { name: "idx_notif_destinatario" });
db.notificacoes.createIndex({ agendadaPara: 1 }, { name: "idx_notif_envio_pendente", partialFilterExpression: { status: "AGENDADA" } });
db.notificacoes.createIndex({ consultaId: 1, tipo: 1, status: 1 }, { name: "idx_notif_consulta" });
db.notificacoes.createIndex(
  { consultaId: 1, tipo: 1, destinatarioId: 1, canal: 1, antecedenciaHoras: 1 },
  { name: "uq_notif_idempotencia", unique: true }
);
db.notificacoes.createIndex({ agendadaPara: 1 }, { name: "idx_notif_lembretes_periodo", partialFilterExpression: { tipo: "LEMBRETE_CONSULTA" } });
```

## Exemplo de documento

Lembrete enviado 24 horas antes da consulta e já confirmado pelo paciente:

```json
{
  "_id": { "$oid": "6650f1a2c3d4e5f607184402" },
  "destinatarioPerfil": "PACIENTE",
  "destinatarioId": { "$oid": "6650f1a2c3d4e5f607180011" },
  "tipo": "LEMBRETE_CONSULTA",
  "canal": "SISTEMA",
  "simulada": true,
  "consultaId": { "$oid": "6650f1a2c3d4e5f607183302" },
  "filaEsperaId": null,
  "statusConsulta": "AGENDADA",
  "antecedenciaHoras": 24,
  "titulo": "Lembrete de consulta",
  "mensagem": "Sua consulta está marcada para 20/11/2026 às 10:00 com Dra. Ana Souza. Confirme sua presença.",
  "dadosConsulta": { "dataHora": { "$date": "2026-11-20T13:00:00Z" }, "medicoNome": "Dra. Ana Souza" },
  "status": "ENVIADA",
  "agendadaPara": { "$date": "2026-11-19T13:00:00Z" },
  "enviadaEm": { "$date": "2026-11-19T13:00:04Z" },
  "tentativas": 1,
  "erro": null,
  "lidaEm": { "$date": "2026-11-19T14:10:00Z" },
  "confirmadaEm": { "$date": "2026-11-19T14:11:30Z" },
  "criadoEm": { "$date": "2026-11-12T15:00:00Z" }
}
```
