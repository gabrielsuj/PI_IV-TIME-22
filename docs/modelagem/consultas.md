# Modelagem: consultas e proteção das reservas

> Issue #26 · Relacionada a #21, #25, #27, #28, #29 e #55 · Sprint 3

Este documento descreve como as consultas são armazenadas no MongoDB, quais status existem e como o próprio banco impede que a mesma vaga seja confirmada duas vezes, mesmo com requisições simultâneas.

**Arquivos relacionados**

| Arquivo | Conteúdo |
|---|---|
| `database/init/03-esquema-consultas.js` | Validador (`$jsonSchema`) e índices da coleção `consultas` |
| `backend/.../domain/enums/StatusConsulta.java` | Status da consulta e lista dos status ativos |
| `backend/.../repository/ConsultaRepository.java` | Gravação (com tradução da violação de índice para 409) e busca de consultas ativas por período |
| `backend/src/test/.../integration/ConsultaReservaIntegrationTest.java` | Testes de concorrência e das regras de reserva |

---

## 1. Visão geral

```text
pacientes ◄── consultas.pacienteId
medicos   ◄── consultas.medicoId          (mesmo médico de agendas e bloqueios)
especialidades ◄── consultas.especialidadeId
```

- Cada consulta ocupa um intervalo **`[inicio, fim)`** gravado como `Date` (UTC), **na mesma representação dos bloqueios**. Assim, consultas e bloqueios podem ser comparados com a mesma condição de sobreposição.
- O **status** é a única fonte de verdade sobre a consulta ocupar ou não a vaga. Não existe campo booleano separado (`ativa`).

---

## 2. Coleção `consultas`

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `_id` | ObjectId | sim | Identificador |
| `pacienteId` | ObjectId | sim | Referência a `pacientes._id` |
| `medicoId` | ObjectId | sim | Referência a `medicos._id` |
| `especialidadeId` | ObjectId | sim | Especialidade do atendimento; deve ser uma das `especialidadeIds` do médico |
| `inicio` | Date | sim | Instante de início (UTC) |
| `fim` | Date | sim | Instante de fim (UTC). Deve ser posterior a `inicio` |
| `status` | string | sim | Um dos valores da seção 3 |
| `criadaEm` | Date | sim | Momento em que a consulta foi registrada |
| `atualizadaEm` | Date | não | Última alteração (status, cancelamento etc.) |
| `motivoCancelamento` | string | não | Preenchido no cancelamento (#27) |

```js
{
  _id: ObjectId("400000000000000000000001"),
  pacienteId: ObjectId("500000000000000000000001"),
  medicoId: ObjectId("100000000000000000000001"),
  especialidadeId: ObjectId("000000000000000000000002"),
  inicio: ISODate("2026-11-09T12:00:00Z"),   // 09:00 em São Paulo
  fim:    ISODate("2026-11-09T12:30:00Z"),   // 09:30 em São Paulo
  status: "AGENDADA",
  criadaEm: ISODate("2026-11-05T13:00:00Z")
}
```

O `fim` é gravado (e não calculado na leitura) porque a duração das consultas pode mudar na agenda do médico depois que a consulta foi marcada.

---

## 3. Status

| Status | Ocupa a vaga? | Significado |
|---|---|---|
| `AGENDADA` | **sim** | Consulta marcada |
| `CONFIRMADA` | **sim** | Presença confirmada pelo paciente ou pela secretaria |
| `CANCELADA` | não | Cancelada; a vaga volta a ficar disponível |
| `CONCLUIDA` | não | Atendimento realizado |
| `FALTA` | não | Paciente não compareceu |

`AGENDADA` e `CONFIRMADA` são os **status ativos** (`StatusConsulta.ATIVOS`). O mesmo conjunto é usado no filtro dos índices parciais e nas buscas da API, por isso deve ser alterado sempre nos dois lugares.

### Transições permitidas

```text
AGENDADA ──► CONFIRMADA ──► CONCLUIDA
    │             │    └──► FALTA
    └──────┬──────┘
           ▼
       CANCELADA
```

`CANCELADA`, `CONCLUIDA` e `FALTA` são finais. A verificação das transições fica na API (#27 e #28); cada mudança deve gerar um registro em `historico` (#29).

---

## 4. Índices

| Nome | Campos | Tipo | Para que serve |
|---|---|---|---|
| `uk_consulta_medico_inicio` | `medicoId`, `inicio` | único **parcial** (só status ativos) | O médico não tem duas consultas ativas começando no mesmo instante |
| `uk_consulta_paciente_inicio` | `pacienteId`, `inicio` | único **parcial** (só status ativos) | O paciente não tem duas consultas ativas começando no mesmo instante, nem com médicos diferentes |
| `ix_consulta_medico_periodo` | `medicoId`, `inicio`, `fim` | composto | Busca de consultas que se sobrepõem a um período (disponibilidade, conflitos, alteração de agenda) |
| `ix_consulta_paciente` | `pacienteId`, `inicio` (desc.) | composto | Listagem das consultas do paciente, das mais recentes para as mais antigas |

Filtro dos índices parciais:

```js
{ status: { $in: ["AGENDADA", "CONFIRMADA"] } }
```

**Por que parcial:** sem o filtro, uma consulta cancelada continuaria "segurando" o horário e ninguém mais conseguiria marcá-lo. Com o filtro, só as consultas ativas entram no índice e a vaga é liberada assim que a consulta sai desses status.

> O uso de `$in` em `partialFilterExpression` exige MongoDB 6 ou superior. O `docker-compose.yml` usa o MongoDB 7.

### Consulta atendida pelo índice de período

```js
// Consultas ativas que se sobrepõem a uma janela [inicioJanela, fimJanela)
db.consultas.find({
  medicoId,
  status: { $in: ["AGENDADA", "CONFIRMADA"] },
  inicio: { $lt: fimJanela },
  fim:    { $gt: inicioJanela }
})
```

É a mesma condição usada para bloqueios (início incluído, fim excluído) e está implementada em `ConsultaRepository.ativasNoPeriodo()`.

---

## 5. Garantias de reserva

### 5.1 Garantidas pelo banco

| Regra | Como |
|---|---|
| Duas reservas simultâneas para a mesma vaga do médico: só uma é confirmada | Índice `uk_consulta_medico_inicio` (a segunda gravação falha com código 11000) |
| O mesmo paciente não marca duas consultas no mesmo horário | Índice `uk_consulta_paciente_inicio` |
| Vaga de consulta cancelada pode ser marcada de novo | Filtro parcial dos índices únicos |
| Status só aceita os cinco valores da seção 3 | `$jsonSchema` (`enum`) |
| Início anterior ao fim | `$expr` no validador |
| Campos obrigatórios e tipos corretos | `$jsonSchema` |

Como a regra está no índice, ela vale mesmo com requisições concorrentes: o MongoDB aceita apenas uma das gravações, sem depender de verificações feitas antes pela API.

### 5.2 Na API

- **Tradução dos erros:** a violação de índice único (11000) vira **409** com a mensagem "Horário indisponível…", e a recusa do validador (121) vira **400** (`MongoErros`).
- **Sobreposição com início diferente:** os índices só comparam o **instante de início**. Uma consulta de 09:00–09:30 e outra de 09:15–09:45 **não** são barradas pelo banco. Essa verificação é feita na API com `ativasNoPeriodo()` (#25), além dos índices.
- **Jornada e bloqueios:** a consulta precisa estar dentro de uma faixa da agenda do médico e fora de bloqueios (#20 e #21).
- **Referências:** paciente, médico e especialidade precisam existir e estar ativos, e a especialidade precisa ser uma das do médico.
- **Transições de status:** conforme a seção 3 (#27 e #28).

### 5.3 Testes

`ConsultaReservaIntegrationTest`:

| Teste | Verifica |
|---|---|
| `deveConfirmarApenasUmAgendamentoParaMesmaVaga` | 10 gravações simultâneas na mesma vaga, de pacientes diferentes: 1 confirmada e 9 recusadas com 409 |
| `deveRecusarMesmoPacienteNoMesmoHorario` | Mesmo paciente, dois médicos, mesmo horário: 409 |
| `devePermitirReservarVagaDeConsultaCancelada` | Depois do cancelamento, a vaga aceita nova consulta |
| `deveRecusarStatusInvalido` | Status fora da lista: 400 |
| `deveListarSomenteAtivasNoPeriodo` | Consultas canceladas não aparecem na busca por período |

O teste equivalente via API (com o endpoint de agendamento) e o `deveRecusarSobreposicaoDeHorario()` ficam para a #55.

---

## 6. Decisões

| ID | Decisão | Motivo | Situação |
|---|---|---|---|
| D6 | `inicio` e `fim` como `Date` (UTC), no lugar de `data` + `horario` | Mesma representação dos bloqueios; permite comparar consultas e bloqueios com a mesma condição de sobreposição | Adotada |
| D7 | O status define se a consulta é ativa (sem campo `ativa`) | Uma única fonte de verdade; cancelar não exige atualizar dois campos | Adotada |
| D8 | Índices únicos parciais por médico e por paciente | Garantia de reserva no próprio banco, inclusive com concorrência | Adotada |
| D9 | `fim` gravado na consulta | A duração da agenda pode mudar depois da marcação | Adotada |

O índice anterior, `uk_consulta_ativa` (`medicoId` + `data` + `horario`, filtrado por `ativa: true`), foi removido do `01-init.js`. O `03-esquema-consultas.js` também o apaga em bancos que já o tinham.

---

## 7. Como executar e verificar

```bash
# Aplicar o validador e os índices em um banco já existente
docker exec -i consultafacil-mongo mongosh consultafacil      --quiet < database/init/03-esquema-consultas.js
docker exec -i consultafacil-mongo mongosh consultafacil_test --quiet < database/init/03-esquema-consultas.js

# Conferir os índices
docker exec -i consultafacil-mongo mongosh consultafacil --quiet --eval 'db.consultas.getIndexes().map(i => i.name)'

# Rodar os testes (requer o MongoDB no ar)
cd backend && ./mvnw test -Dtest=ConsultaReservaIntegrationTest
```

Em um banco criado do zero (`docker compose down -v` e `up -d mongo`), o script é executado automaticamente junto com os demais de `database/init`.
