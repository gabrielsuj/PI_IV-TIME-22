# Dicionário de Dados da Coleção `filaEspera` do Banco de Dados MongoDB do ConsultaFácil – Sistema de Agendamento de Consultas com Fila de Espera para Clínicas e Consultórios

## Histórico de Versões/Revisões

| Data | Autor | Versão | Comentários |
|------|-------|--------|-------------|
| 2026-11-06 | Davi Vitoreti | v1.0.0 | Versão inicial da coleção `filaEspera`: campos, referências, status, regras de integração com o status da consulta e índices. |

## Entidades

A seguir é documentada a entidade (coleção MongoDB) e seus dados (atributos) necessários para suportar o registro de interesse de pacientes em vagas liberadas por cancelamentos e a sinalização dessas vagas (item I7 da ideação, versão simplificada do MVP).

### 1. FilaEspera (coleção `filaEspera`)

Descrição: Representa o **interesse** registrado por um paciente em ser avisado quando uma vaga compatível for liberada, por especialidade ou por médico específico. Cada documento é um interesse, e não uma vaga: a vaga é sempre a combinação médico + data + horário de uma consulta cancelada (coleção `consultas`). A ordem de atendimento dos interessados é a ordem de criação (`criadoEm`, mais antigo primeiro). É importante destacar que, no MVP, a vaga é apenas **sinalizada** (aviso simulado); a oferta sequencial com prazo de resposta (I8) é evolução futura, portanto não há campos de prazo de oferta. Quando mais de um interessado é sinalizado para a mesma vaga, vale a primeira confirmação: o índice único de `consultas` (médico + data + horário, apenas consultas ativas) impede reserva duplicada.

Atributos:

| Nome | Tipo | Obrigatoriedade | Exemplos/Comentários |
|------|------|-----------------|----------------------|
| `_id` | ObjectId | Sim | Gerado automaticamente pelo MongoDB. Ex.: `6650f1a2c3d4e5f607182901`. |
| `pacienteId` | ObjectId | Sim | Referência a `pacientes._id`. Paciente que aguarda a vaga. |
| `especialidadeId` | ObjectId | Sim | Referência a `especialidades._id`. Sempre informado, mesmo quando o interesse é por um médico específico; nesse caso deve ser uma especialidade do médico informado em `medicoId`. |
| `medicoId` | ObjectId ou null | Não | Referência a `medicos._id`. `null` significa "qualquer médico da especialidade". Deve ser gravado como `null` explicitamente (nunca omitido) para que o índice único de interesse ativo funcione de forma previsível. |
| `turnoPreferido` | String (lista literal e estática) | Sim | Valores: `MANHA`, `TARDE`, `QUALQUER`. Padrão: `QUALQUER`. Corte sugerido: manhã antes das 12h, tarde a partir das 12h (horário de America/Sao_Paulo). |
| `dataInicio` | Date (UTC) | Não | Início da janela de datas desejada. Se ausente, vale a partir do momento do registro. |
| `dataFim` | Date (UTC) | Não | Fim da janela de datas desejada. Se informado, deve ser maior ou igual a `dataInicio`. Se ausente, vale até `validoAte`. |
| `status` | String (lista literal e estática) | Sim | Valores: `AGUARDANDO`, `SINALIZADA`, `ATENDIDA`, `CANCELADA`, `EXPIRADA`. Ver seção "Status do interesse". Padrão na criação: `AGUARDANDO`. |
| `ultimaSinalizacao` | Object ou null | Condicional | Obrigatório quando `status = SINALIZADA`; `null` caso contrário. Estrutura na tabela abaixo. |
| `consultaAgendadaId` | ObjectId ou null | Condicional | Referência a `consultas._id`. Obrigatório quando `status = ATENDIDA` (consulta criada pelo paciente a partir da vaga sinalizada); `null` caso contrário. |
| `atendidaEm` | Date (UTC) ou null | Condicional | Obrigatório quando `status = ATENDIDA`. Base do indicador "vagas preenchidas pela fila". |
| `validoAte` | Date (UTC) | Sim | Data-limite do interesse; após ela o interesse passa a `EXPIRADA`. Sugestão: `criadoEm` + 90 dias (valor a confirmar com as clínicas parceiras). |
| `registradoPor` | Object | Sim | `{ perfil, id }`. `perfil`: `PACIENTE` (autoatendimento), `SECRETARIA` ou `ADMINISTRADOR`. `id`: identificador do usuário que registrou. |
| `criadoEm` | Date (UTC) | Sim | Momento do registro. Define a posição na fila (FIFO). Não deve ser alterado. |
| `atualizadoEm` | Date (UTC) | Sim | Atualizado a cada mudança de `status` ou de preferências. |

Subdocumento `ultimaSinalizacao`:

| Nome | Tipo | Obrigatoriedade | Exemplos/Comentários |
|------|------|-----------------|----------------------|
| `consultaOrigemId` | ObjectId | Sim | Referência a `consultas._id`: consulta com status `CANCELADA` que liberou a vaga. |
| `medicoId` | ObjectId | Sim | Referência a `medicos._id`: médico da vaga liberada. |
| `dataHora` | Date (UTC) | Sim | Início do horário liberado. |
| `sinalizadaEm` | Date (UTC) | Sim | Momento em que o interessado foi sinalizado. |
| `notificacaoId` | ObjectId | Sim | Referência a `notificacoes._id` (tipo `VAGA_LIBERADA`) gerada para este interessado. |

## Referências

| Campo | Coleção referenciada | Cardinalidade | Obrigatória | Observação |
|-------|----------------------|---------------|-------------|------------|
| `pacienteId` | `pacientes` | N:1 | Sim | Um paciente pode ter vários interesses (em especialidades/médicos distintos). |
| `especialidadeId` | `especialidades` | N:1 | Sim | |
| `medicoId` | `medicos` | N:1 | Não | `null` = qualquer médico da especialidade. |
| `consultaAgendadaId` | `consultas` | N:1 | Condicional | Apenas em `ATENDIDA`. |
| `ultimaSinalizacao.consultaOrigemId` | `consultas` | N:1 | Condicional | Apenas em `SINALIZADA`. A consulta deve estar `CANCELADA`. |
| `ultimaSinalizacao.medicoId` | `medicos` | N:1 | Condicional | Apenas em `SINALIZADA`. |
| `ultimaSinalizacao.notificacaoId` | `notificacoes` | N:1 | Condicional | Apenas em `SINALIZADA`. |

O MongoDB não impõe integridade referencial: a existência dos documentos referenciados é validada na camada de serviços da API.

## Status do interesse

| Status | Significado | Ativo? | Final? |
|--------|-------------|--------|--------|
| `AGUARDANDO` | Interesse registrado; nenhuma vaga compatível sinalizada no momento. | Sim | Não |
| `SINALIZADA` | Uma vaga compatível foi liberada e o paciente foi avisado; aguarda ação do paciente. | Sim | Não |
| `ATENDIDA` | O paciente agendou a vaga (`consultaAgendadaId` preenchido). | Não | Sim |
| `CANCELADA` | O paciente desistiu ou a secretaria removeu o interesse. | Não | Sim |
| `EXPIRADA` | `validoAte` vencido ou `dataFim` ultrapassada sem atendimento. | Não | Sim |

Transições permitidas:

| De | Para | Gatilho |
|----|------|---------|
| `AGUARDANDO` | `SINALIZADA` | Consulta compatível passa a `CANCELADA`. |
| `SINALIZADA` | `AGUARDANDO` | A vaga sinalizada foi ocupada por outro paciente ou deixou de estar disponível; o interessado mantém `criadoEm` (posição original). |
| `AGUARDANDO` ou `SINALIZADA` | `ATENDIDA` | Paciente cria consulta (`AGENDADA`) para a vaga. |
| `AGUARDANDO` ou `SINALIZADA` | `CANCELADA` | Ação do paciente ou da secretaria. |
| `AGUARDANDO` ou `SINALIZADA` | `EXPIRADA` | Rotina de expiração (`validoAte` ou `dataFim` vencidos). |

## Relação com o status da consulta

Os status da consulta (`AGENDADA`, `CONFIRMADA`, `CANCELADA`, `CONCLUIDA`, `FALTA`) são definidos em `historico.md`. O efeito de cada mudança de status da consulta nesta coleção é:

| Evento na consulta | Efeito em `filaEspera` |
|--------------------|------------------------|
| Consulta passa a `CANCELADA` | Busca interessados compatíveis (regra abaixo), na ordem de `criadoEm`; cada um vai a `SINALIZADA`, recebe `ultimaSinalizacao` e uma notificação `VAGA_LIBERADA`. |
| Nova consulta `AGENDADA` na vaga sinalizada, pelo próprio interessado | Interesse vai a `ATENDIDA`, com `consultaAgendadaId` e `atendidaEm`. |
| Nova consulta `AGENDADA` na mesma vaga, por outro paciente | Demais interessados `SINALIZADA` daquela vaga voltam a `AGUARDANDO`. |
| Consulta `CONFIRMADA`, `CONCLUIDA` ou `FALTA` | Sem efeito. |

Regra de compatibilidade entre um interessado e a vaga liberada (todas devem ser verdadeiras):

1. `status = AGUARDANDO` e `validoAte` no futuro.
2. `especialidadeId` igual à especialidade do médico da vaga.
3. `medicoId` é `null` ou igual ao médico da vaga.
4. Data da vaga dentro de `dataInicio` e `dataFim` (quando informados) e no `turnoPreferido`.
5. O paciente não possui outra consulta ativa sobreposta ao horário da vaga.

### Implementação da sinalização (#32)

`SinalizacaoFilaService`, acionado pelo `RegistroConsultaService` sempre que uma consulta passa a `CANCELADA`:

- Só sinaliza se o horário liberado ainda está no futuro. O turno é avaliado pela hora de início da vaga em São Paulo (`MANHA` antes das 12:00, `TARDE` a partir das 12:00).
- Quem cancelou não é avisado da própria vaga, e cada paciente recebe no máximo um aviso por vaga (o interesse mais antigo dele é o sinalizado).
- Todos os compatíveis são avisados; a vaga não fica reservada e quem agendar primeiro fica com ela (índices únicos de `consultas`).
- A notificação `VAGA_LIBERADA` é criada com `status = AGENDADA`; exibir ou enviar o aviso fica para quem consome `notificacoes` (#57). Ela é gravada antes de o interesse passar a `SINALIZADA`, de modo que `ultimaSinalizacao.notificacaoId` sempre aponta para uma notificação existente.
- O histórico da consulta recebe `SINALIZACAO_FILA` com `totalSinalizados`, inclusive quando é `0`.
- O cadastro dos interesses pela API é da #31 ([docs/api/fila-espera.md](../api/fila-espera.md)).

### Vaga sinalizada ocupada ou vencida

`SinalizacaoFilaService.consultaAgendada`, acionado pelo `RegistroConsultaService` sempre que uma consulta é criada. A vaga é identificada pelo médico e pelo horário de início (`ultimaSinalizacao.medicoId` e `ultimaSinalizacao.dataHora`):

- Se quem agendou é um dos sinalizados daquela vaga, a inscrição dele passa a `ATENDIDA` (`consultaAgendadaId`, `atendidaEm`).
- Os outros sinalizados daquela vaga voltam a `AGUARDANDO`, mantendo `criadoEm` (a posição na fila). O aviso `VAGA_LIBERADA` deles, se ainda estiver `AGENDADA`, passa a `CANCELADA`.
- Consulta em outro horário ou com outro médico não altera a fila.

Como não há rotina agendada no MVP, a fila é revisada antes de cada leitura (listagem, inscrição e sinalização): sinalizações de vagas que já passaram voltam a `AGUARDANDO` (e podem ser sinalizadas de novo), e inscrições com `validoAte` vencido passam a `EXPIRADA`.

## Índices

| Nome | Chaves | Opções | Finalidade |
|------|--------|--------|------------|
| `idx_fila_paciente_status` | `{ pacienteId: 1, status: 1 }` | — | Listar os interesses de um paciente (autoatendimento) e filtrar os ativos. |
| `idx_fila_compatibilidade` | `{ status: 1, especialidadeId: 1, medicoId: 1, criadoEm: 1 }` | — | Localizar interessados compatíveis após um cancelamento, em ordem FIFO. A consulta usa `medicoId: { $in: [<médico da vaga>, null] }`. |
| `uq_fila_interesse_ativo` | `{ pacienteId: 1, especialidadeId: 1, medicoId: 1 }` | `unique: true`; `partialFilterExpression: { status: { $in: ["AGUARDANDO", "SINALIZADA"] } }` | Impedir interesses ativos duplicados do mesmo paciente para a mesma especialidade/médico. Interesses encerrados não participam do índice. Requer MongoDB 6.0 ou superior; em versões anteriores, usar um campo booleano `ativo`. |
| `idx_fila_validade` | `{ validoAte: 1 }` | `partialFilterExpression: { status: { $in: ["AGUARDANDO", "SINALIZADA"] } }` | Rotina de expiração: localizar interesses ativos vencidos. |
| `idx_fila_atendidas` | `{ atendidaEm: 1 }` | `partialFilterExpression: { status: "ATENDIDA" }` | Indicador do painel: vagas preenchidas pela fila em um período. |

Não é usado índice TTL: interesses encerrados são mantidos para os indicadores.

Script de criação (mongosh):

```javascript
db.filaEspera.createIndex({ pacienteId: 1, status: 1 }, { name: "idx_fila_paciente_status" });
db.filaEspera.createIndex({ status: 1, especialidadeId: 1, medicoId: 1, criadoEm: 1 }, { name: "idx_fila_compatibilidade" });
db.filaEspera.createIndex(
  { pacienteId: 1, especialidadeId: 1, medicoId: 1 },
  { name: "uq_fila_interesse_ativo", unique: true,
    partialFilterExpression: { status: { $in: ["AGUARDANDO", "SINALIZADA"] } } }
);
db.filaEspera.createIndex(
  { validoAte: 1 },
  { name: "idx_fila_validade", partialFilterExpression: { status: { $in: ["AGUARDANDO", "SINALIZADA"] } } }
);
db.filaEspera.createIndex(
  { atendidaEm: 1 },
  { name: "idx_fila_atendidas", partialFilterExpression: { status: "ATENDIDA" } }
);
```

## Exemplo de documento

Interesse sinalizado após o cancelamento de uma consulta:

```json
{
  "_id": { "$oid": "6650f1a2c3d4e5f607182901" },
  "pacienteId": { "$oid": "6650f1a2c3d4e5f607180011" },
  "especialidadeId": { "$oid": "6650f1a2c3d4e5f607180021" },
  "medicoId": null,
  "turnoPreferido": "MANHA",
  "dataInicio": { "$date": "2026-11-16T03:00:00Z" },
  "dataFim": { "$date": "2026-11-30T03:00:00Z" },
  "status": "SINALIZADA",
  "ultimaSinalizacao": {
    "consultaOrigemId": { "$oid": "6650f1a2c3d4e5f607183301" },
    "medicoId": { "$oid": "6650f1a2c3d4e5f607180031" },
    "dataHora": { "$date": "2026-11-20T13:00:00Z" },
    "sinalizadaEm": { "$date": "2026-11-12T14:32:10Z" },
    "notificacaoId": { "$oid": "6650f1a2c3d4e5f607184401" }
  },
  "consultaAgendadaId": null,
  "atendidaEm": null,
  "validoAte": { "$date": "2027-02-05T15:20:00Z" },
  "registradoPor": { "perfil": "PACIENTE", "id": { "$oid": "6650f1a2c3d4e5f607180011" } },
  "criadoEm": { "$date": "2026-11-07T15:20:00Z" },
  "atualizadoEm": { "$date": "2026-11-12T14:32:10Z" }
}
```
