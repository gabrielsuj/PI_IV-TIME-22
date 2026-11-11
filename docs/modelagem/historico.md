# Dicionário de Dados da Coleção `historico` do Banco de Dados MongoDB do ConsultaFácil – Sistema de Agendamento de Consultas com Fila de Espera para Clínicas e Consultórios

> Implementação Java e compatibilidade com a autenticação atual: [registro e recuperação de histórico](historico-implementacao.md). O documento abaixo mantém a modelagem original; o complemento descreve os ajustes de atores, campos e sequência para a implementação.

## Histórico de Versões/Revisões

| Data | Autor | Versão | Comentários |
|------|-------|--------|-------------|
| 2026-11-05 | Davi Vitoreti | v1.0.0 | Versão inicial da coleção `historico`: campos, referências, domínio de status da consulta, eventos, transições e índices. |

## Entidades

A seguir é documentada a entidade (coleção MongoDB) e seus dados (atributos) necessários para registrar todas as alterações de agendamento e de status das consultas (requisito "Histórico" do MVP). Esta coleção também é a **definição de referência do status da consulta**, usada pelas coleções `consultas`, `filaEspera` e `notificacoes`.

### 1. Historico (coleção `historico`)

Descrição: Representa um **evento** ocorrido em uma consulta: criação, confirmação, alteração, cancelamento, conclusão, falta ou sinalização da vaga à fila de espera. A coleção é de **apenas inserção** (*append-only*): documentos nunca são alterados nem removidos. Uma correção é registrada como um novo evento. Cada consulta tem uma linha do tempo ordenada por `sequencia`. O painel e os indicadores de faltas e cancelamentos são calculados a partir desta coleção.

Atributos:

| Nome | Tipo | Obrigatoriedade | Exemplos/Comentários |
|------|------|-----------------|----------------------|
| `_id` | ObjectId | Sim | Gerado automaticamente pelo MongoDB. Ex.: `6650f1a2c3d4e5f607185501`. |
| `consultaId` | ObjectId | Sim | Referência a `consultas._id`. Consulta à qual o evento pertence. |
| `sequencia` | Inteiro (Int32) | Sim | Número do evento dentro da consulta: 1, 2, 3... A API calcula o último valor + 1; o índice único `uq_hist_consulta_sequencia` rejeita concorrência. |
| `evento` | String (lista literal e estática) | Sim | Valores: `CRIACAO`, `CONFIRMACAO`, `ALTERACAO`, `CANCELAMENTO`, `CONCLUSAO`, `FALTA`, `SINALIZACAO_FILA`. Ver tabela "Eventos". |
| `statusAnterior` | String (lista literal e estática) ou null | Condicional | Status da consulta antes do evento. `null` apenas em `CRIACAO`. Valores em "Status da consulta". |
| `statusNovo` | String (lista literal e estática) | Sim | Status da consulta após o evento. Em `ALTERACAO` e `SINALIZACAO_FILA` é igual a `statusAnterior`. |
| `pacienteId` | ObjectId | Sim | Referência a `pacientes._id`. Cópia do paciente da consulta, para consultas por paciente sem junção. |
| `medicoId` | ObjectId | Sim | Referência a `medicos._id`. Médico da consulta **após** o evento (em `ALTERACAO` de médico, é o novo). |
| `dataHoraConsulta` | Date (UTC) | Sim | Início do horário da consulta **após** o evento. Em `ALTERACAO` de data/horário, é o novo valor (o anterior fica em `alteracoes`). |
| `ator` | Object | Sim | Quem causou o evento: `{ perfil, id }`. `perfil`: `PACIENTE`, `MEDICO`, `SECRETARIA`, `ADMINISTRADOR` ou `SISTEMA`. `id`: identificador do usuário; `null` quando `SISTEMA`. |
| `alteracoes` | Array de Object | Condicional | Obrigatório (ao menos um item) quando `evento = ALTERACAO`; ausente nos demais. Cada item: `{ campo: String, valorAnterior, valorNovo }`, onde `campo` é o nome do atributo em `consultas` (ex.: `dataHora`, `medicoId`) e os valores são tipos BSON simples (String, número, Date, ObjectId). |
| `motivo` | String ou null | Não | Até 255 caracteres. Usado principalmente em `CANCELAMENTO`. **Não deve conter dados clínicos.** |
| `totalSinalizados` | Inteiro (Int32) | Condicional | Obrigatório quando `evento = SINALIZACAO_FILA`: quantos interessados da fila foram sinalizados (pode ser `0`). Quem foi sinalizado é consultável em `notificacoes` (`tipo = VAGA_LIBERADA`, mesmo `consultaId`). Evita um array sem limite de tamanho. |
| `ocorridoEm` | Date (UTC) | Sim | Momento do evento, definido pelo servidor (não pelo cliente). Exibir no fuso America/Sao_Paulo. |

## Referências

| Campo | Coleção referenciada | Cardinalidade | Obrigatória | Observação |
|-------|----------------------|---------------|-------------|------------|
| `consultaId` | `consultas` | N:1 | Sim | Uma consulta tem vários eventos. |
| `pacienteId` | `pacientes` | N:1 | Sim | Cópia do valor da consulta. |
| `medicoId` | `medicos` | N:1 | Sim | Valor após o evento. |
| `ator.id` | `pacientes`, `medicos` ou cadastro de usuários internos (conforme `ator.perfil`) | N:1 | Condicional | `null` quando `SISTEMA`. A coleção de usuários da secretaria/administrador não consta na lista de coleções do relatório e precisa ser definida pela equipe (módulo de Acesso). |

A existência dos documentos referenciados é validada na camada de serviços da API.

## Status da consulta

Domínio único de valores para `consultas.status`, `historico.statusAnterior`, `historico.statusNovo` e `notificacoes.statusConsulta`.

| Status | Significado | Ocupa o horário (ativa)? | Evento que o produz |
|--------|-------------|--------------------------|---------------------|
| `AGENDADA` | Horário reservado após validação de disponibilidade e de conflitos (ato de agendamento). | Sim | `CRIACAO` |
| `CONFIRMADA` | Comparecimento confirmado pelo paciente (lembrete), pelo médico ou pela secretaria. | Sim | `CONFIRMACAO` |
| `CANCELADA` | Consulta cancelada; o horário é liberado e a fila é acionada. | Não | `CANCELAMENTO` |
| `CONCLUIDA` | Atendimento realizado. | Não (horário já passou) | `CONCLUSAO` |
| `FALTA` | Paciente não compareceu. | Não (horário já passou) | `FALTA` |

O índice único de reservas em `consultas` (médico + data + horário) deve ter como filtro parcial os status ativos: `status` em `["AGENDADA", "CONFIRMADA"]`.

Transições permitidas:

| De | Para |
|----|------|
| (não existe) | `AGENDADA` |
| `AGENDADA` | `CONFIRMADA`, `CANCELADA`, `CONCLUIDA`, `FALTA` |
| `CONFIRMADA` | `CANCELADA`, `CONCLUIDA`, `FALTA` |
| `CANCELADA`, `CONCLUIDA`, `FALTA` | Nenhum (status finais; reagendar significa criar uma nova consulta) |

## Eventos

| Evento | `statusAnterior` | `statusNovo` | Atores permitidos | Campos específicos |
|--------|------------------|--------------|-------------------|--------------------|
| `CRIACAO` | `null` | `AGENDADA` | `PACIENTE`, `SECRETARIA`, `ADMINISTRADOR` | — |
| `CONFIRMACAO` | `AGENDADA` | `CONFIRMADA` | `PACIENTE`, `MEDICO`, `SECRETARIA`, `ADMINISTRADOR` | — |
| `ALTERACAO` | `AGENDADA` ou `CONFIRMADA` | igual ao anterior | `SECRETARIA`, `ADMINISTRADOR` | `alteracoes` obrigatório |
| `CANCELAMENTO` | `AGENDADA` ou `CONFIRMADA` | `CANCELADA` | `PACIENTE`, `MEDICO`, `SECRETARIA`, `ADMINISTRADOR` | `motivo` opcional |
| `CONCLUSAO` | `AGENDADA` ou `CONFIRMADA` | `CONCLUIDA` | `MEDICO`, `SECRETARIA`, `ADMINISTRADOR` | — |
| `FALTA` | `AGENDADA` ou `CONFIRMADA` | `FALTA` | `MEDICO`, `SECRETARIA`, `ADMINISTRADOR` | — |
| `SINALIZACAO_FILA` | `CANCELADA` | `CANCELADA` | `SISTEMA` | `totalSinalizados` obrigatório |

No fluxo de cancelamento são gravados, na mesma operação, `CANCELAMENTO` (`sequencia` n) e `SINALIZACAO_FILA` (`sequencia` n+1), atendendo ao cenário de aceite "a vaga é liberada, o histórico é atualizado e a fila recebe a sinalização".

## Índices

| Nome | Chaves | Opções | Finalidade |
|------|--------|--------|------------|
| `uq_hist_consulta_sequencia` | `{ consultaId: 1, sequencia: 1 }` | `unique: true` | Garantir ordem e ausência de duplicidade dos eventos de uma consulta; serve também à linha do tempo da consulta. |
| `idx_hist_paciente_data` | `{ pacienteId: 1, ocorridoEm: -1 }` | — | Histórico de um paciente, do mais recente ao mais antigo. |
| `idx_hist_medico_data` | `{ medicoId: 1, ocorridoEm: -1 }` | — | Histórico das consultas de um médico. |
| `idx_hist_evento_data` | `{ evento: 1, ocorridoEm: -1 }` | — | Indicadores por período: faltas, cancelamentos, confirmações. |
| `idx_hist_data` | `{ ocorridoEm: -1 }` | — | Alterações recentes exibidas no painel. |

Não é usado índice TTL: o histórico é permanente.

Script de criação (mongosh):

```javascript
db.historico.createIndex({ consultaId: 1, sequencia: 1 }, { name: "uq_hist_consulta_sequencia", unique: true });
db.historico.createIndex({ pacienteId: 1, ocorridoEm: -1 }, { name: "idx_hist_paciente_data" });
db.historico.createIndex({ medicoId: 1, ocorridoEm: -1 }, { name: "idx_hist_medico_data" });
db.historico.createIndex({ evento: 1, ocorridoEm: -1 }, { name: "idx_hist_evento_data" });
db.historico.createIndex({ ocorridoEm: -1 }, { name: "idx_hist_data" });
```

Recomendação de imutabilidade: o usuário do banco usado pela API deve ter apenas as permissões `find` e `insert` nesta coleção, e a camada de repositórios não deve expor operações de atualização ou remoção.

## Exemplos de documentos

Cancelamento pelo paciente seguido da sinalização à fila:

```json
[
  {
    "_id": { "$oid": "6650f1a2c3d4e5f607185501" },
    "consultaId": { "$oid": "6650f1a2c3d4e5f607183301" },
    "sequencia": 3,
    "evento": "CANCELAMENTO",
    "statusAnterior": "CONFIRMADA",
    "statusNovo": "CANCELADA",
    "pacienteId": { "$oid": "6650f1a2c3d4e5f607180012" },
    "medicoId": { "$oid": "6650f1a2c3d4e5f607180031" },
    "dataHoraConsulta": { "$date": "2026-11-20T13:00:00Z" },
    "ator": { "perfil": "PACIENTE", "id": { "$oid": "6650f1a2c3d4e5f607180012" } },
    "motivo": "Compromisso inesperado",
    "ocorridoEm": { "$date": "2026-11-12T14:32:09Z" }
  },
  {
    "_id": { "$oid": "6650f1a2c3d4e5f607185502" },
    "consultaId": { "$oid": "6650f1a2c3d4e5f607183301" },
    "sequencia": 4,
    "evento": "SINALIZACAO_FILA",
    "statusAnterior": "CANCELADA",
    "statusNovo": "CANCELADA",
    "pacienteId": { "$oid": "6650f1a2c3d4e5f607180012" },
    "medicoId": { "$oid": "6650f1a2c3d4e5f607180031" },
    "dataHoraConsulta": { "$date": "2026-11-20T13:00:00Z" },
    "ator": { "perfil": "SISTEMA", "id": null },
    "totalSinalizados": 2,
    "ocorridoEm": { "$date": "2026-11-12T14:32:10Z" }
  }
]
```

Alteração de horário pela secretaria:

```json
{
  "_id": { "$oid": "6650f1a2c3d4e5f607185503" },
  "consultaId": { "$oid": "6650f1a2c3d4e5f607183302" },
  "sequencia": 2,
  "evento": "ALTERACAO",
  "statusAnterior": "AGENDADA",
  "statusNovo": "AGENDADA",
  "pacienteId": { "$oid": "6650f1a2c3d4e5f607180011" },
  "medicoId": { "$oid": "6650f1a2c3d4e5f607180031" },
  "dataHoraConsulta": { "$date": "2026-11-20T17:00:00Z" },
  "ator": { "perfil": "SECRETARIA", "id": { "$oid": "6650f1a2c3d4e5f607180041" } },
  "alteracoes": [
    { "campo": "dataHora", "valorAnterior": { "$date": "2026-11-20T13:00:00Z" }, "valorNovo": { "$date": "2026-11-20T17:00:00Z" } }
  ],
  "ocorridoEm": { "$date": "2026-11-13T12:00:00Z" }
}
```
