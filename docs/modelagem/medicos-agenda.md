# Modelagem: especialidades, médicos, agendas e bloqueios

> Issue #51 · Relacionada a #17 · Sprint 2 (Dados)

Este documento descreve como os cadastros de especialidades e médicos, a configuração de atendimento (agenda) e os bloqueios pontuais são armazenados no MongoDB, quais regras o próprio banco garante e quais ficam a cargo da API.

**Arquivos relacionados**

| Arquivo | Conteúdo |
|---|---|
| `database/init/01-init.js` | Criação das coleções |
| `database/init/02-esquema-agenda.js` | Validadores (`$jsonSchema`) e índices desta modelagem |
| `database/seed/01-agenda.js` | Dados fictícios de demonstração (idempotente) |
| `scripts/verificar-modelagem.js` | Verificação automática dos dados, índices e validações |

---

## 1. Visão geral

```text
especialidades ◄──── medicos.especialidadeIds[]   (N:N, referência por _id)
                        ▲
                        │ medicoId
            ┌───────────┴───────────┐
         agendas (1:1)          bloqueios (1:N)
   configuração semanal       períodos indisponíveis
                               em datas específicas
```

- **Especialidade** é um documento próprio, porque pode ser compartilhada por vários médicos.
- **Médico** guarda a lista de `_id` das especialidades em que atende.
- **Agenda** guarda a configuração semanal do médico. As jornadas e faixas ficam **incorporadas** no documento, porque são pequenas e sempre lidas juntas.
- **Bloqueio** fica em coleção **separada**, porque representa ocorrências em datas específicas que se acumulam ao longo do tempo.

---

## 2. Coleções

### 2.1 `especialidades`

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `_id` | ObjectId | sim | Identificador |
| `codigo` | string | sim | Identificador estável, minúsculo, com hífens (`^[a-z0-9-]+$`). Único. Mudar o `nome` não muda o `codigo` |
| `nome` | string | sim | Nome exibido na interface |
| `ativa` | bool | sim | Permite desativar sem apagar |

```js
{
  _id: ObjectId("000000000000000000000002"),
  codigo: "cardiologia",
  nome: "Cardiologia",
  ativa: true
}
```

### 2.2 `medicos`

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `_id` | ObjectId | sim | Identificador |
| `nome` | string | sim | Nome completo |
| `crmNumero` | string | sim | Número do CRM como **texto**, preservando zeros à esquerda (`^[0-9]{1,10}$`) |
| `crmUf` | string | sim | UF do CRM, duas letras maiúsculas |
| `especialidadeIds` | ObjectId[] | sim | Referências a `especialidades._id`. Mínimo 1, sem repetição |
| `ativo` | bool | sim | Permite desativar o cadastro sem apagá-lo |

O par `crmUf` + `crmNumero` é único.

```js
{
  _id: ObjectId("100000000000000000000001"),
  nome: "Ana Martins — Fictícia",
  crmNumero: "000123",
  crmUf: "SP",
  especialidadeIds: [
    ObjectId("000000000000000000000001"),
    ObjectId("000000000000000000000002")
  ],
  ativo: true
}
```

### 2.3 `agendas`

Na primeira versão há **uma configuração semanal por médico**, com a mesma duração de consulta para todas as suas especialidades (ver decisão D1).

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `_id` | ObjectId | sim | Identificador |
| `medicoId` | ObjectId | sim | Referência a `medicos._id`. Único |
| `fusoHorario` | string | sim | Fuso IANA usado para interpretar as faixas (ex.: `America/Sao_Paulo`) |
| `duracaoMinutos` | int | sim | Duração de cada consulta, entre 5 e 240 |
| `jornadas` | array | sim | Um item por dia da semana com atendimento |
| `jornadas[].diaSemana` | int | sim | Dia da semana no padrão **ISO 8601: 1 = segunda … 7 = domingo** |
| `jornadas[].faixas` | array | sim | Pelo menos uma faixa de atendimento no dia |
| `faixas[].inicioMinuto` | int | sim | Início, em minutos desde a meia-noite (0–1439) |
| `faixas[].fimMinuto` | int | sim | Fim, em minutos desde a meia-noite (1–1440) |

```js
{
  _id: ObjectId("200000000000000000000001"),
  medicoId: ObjectId("100000000000000000000001"),
  fusoHorario: "America/Sao_Paulo",
  duracaoMinutos: 30,
  jornadas: [
    { diaSemana: 1, faixas: [ { inicioMinuto: 480, fimMinuto: 720 },     // 08h–12h
                              { inicioMinuto: 780, fimMinuto: 1020 } ] }, // 13h–17h
    { diaSemana: 3, faixas: [ { inicioMinuto: 480, fimMinuto: 720 } ] }   // 08h–12h
  ]
}
```

Leitura: Ana atende segunda das 08h às 12h e das 13h às 17h, e quarta das 08h às 12h, com consultas de 30 minutos.

### 2.4 `bloqueios`

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `_id` | ObjectId | sim | Identificador |
| `medicoId` | ObjectId | sim | Referência a `medicos._id` |
| `inicio` | Date | sim | Instante de início (armazenado em UTC) |
| `fim` | Date | sim | Instante de fim (armazenado em UTC). Deve ser posterior a `inicio` |
| `motivo` | string | não | Descrição livre |

```js
{
  _id: ObjectId("300000000000000000000001"),
  medicoId: ObjectId("100000000000000000000001"),
  inicio: ISODate("2026-10-26T12:00:00Z"),   // 09:00 em São Paulo
  fim:    ISODate("2026-10-26T13:00:00Z"),   // 10:00 em São Paulo
  motivo: "Compromisso pessoal — exemplo fictício"
}
```

---

## 3. Convenções

| Convenção | Regra |
|---|---|
| Dia da semana | ISO 8601: `1` = segunda … `7` = domingo. Corresponde a `java.time.DayOfWeek.getValue()`. **Não** usar o padrão do JavaScript (`0` = domingo) nem o `$dayOfWeek` do MongoDB (`1` = domingo) sem converter |
| Horário da jornada | Minutos desde a meia-noite, no fuso da agenda (`8 × 60 = 480`). A interface exibe `08:00` |
| Datas específicas | BSON `Date`, que representa instantes em UTC. O `fusoHorario` da agenda orienta a exibição e o cálculo da jornada local |
| Intervalos | **Início incluído, fim excluído** (`[inicio, fim)`). Um bloqueio de 09h às 10h impede uma consulta das 09h30 às 10h, mas permite outra começando às 10h |
| Desativação | Médicos e especialidades são desativados (`ativo`/`ativa = false`), não apagados |
| Dados de demonstração | Todos fictícios. IDs fixos permitem repetir a carga sem duplicar |

---

## 4. Índices

| Coleção | Nome | Campos | Tipo | Para que serve |
|---|---|---|---|---|
| `especialidades` | `uk_especialidade_codigo` | `codigo` | único | Impede código repetido |
| `medicos` | `uk_medico_crm` | `crmUf`, `crmNumero` | único | Impede CRM repetido na mesma UF |
| `medicos` | `ix_medico_especialidade` | `especialidadeIds`, `ativo` | multikey | Busca "médicos ativos de uma especialidade" |
| `agendas` | `uk_agenda_medico` | `medicoId` | único | Garante uma agenda por médico (D1) |
| `bloqueios` | `ix_bloqueio_medico_periodo` | `medicoId`, `inicio`, `fim` | composto | Busca bloqueios que se sobrepõem a um período |

### Consultas de disponibilidade atendidas

```js
// Médicos ativos de uma especialidade
db.medicos.find({ especialidadeIds: especialidadeId, ativo: true })

// Agenda de um médico
db.agendas.findOne({ medicoId })

// Bloqueios que se sobrepõem a uma janela [inicioJanela, fimJanela)
db.bloqueios.find({
  medicoId,
  inicio: { $lt: fimJanela },
  fim:    { $gt: inicioJanela }
})
```

A condição de sobreposição (`inicio < fimJanela` e `fim > inicioJanela`) já segue a convenção de início incluído e fim excluído.

---

## 5. Regras de negócio

### 5.1 Garantidas pelo banco

| Regra | Como |
|---|---|
| O médico precisa ter pelo menos uma especialidade | `$jsonSchema`: `especialidadeIds` com `minItems: 1` |
| CRM único por UF | Índice `uk_medico_crm` |
| Código de especialidade único | Índice `uk_especialidade_codigo` |
| Uma agenda por médico | Índice `uk_agenda_medico` |
| Duração positiva | `$jsonSchema`: `duracaoMinutos` entre 5 e 240 |
| `diaSemana` entre 1 e 7 | `$jsonSchema` |
| Toda faixa tem início anterior ao fim | `$expr` no validador de `agendas` |
| Todo bloqueio tem início anterior ao fim | `$expr` no validador de `bloqueios` |
| Campos obrigatórios e tipos corretos | `$jsonSchema` em todas as coleções |

### 5.2 A cargo da API (camada de serviço em Java)

Estas regras não são expressas de forma simples em validador e devem ser verificadas antes de gravar:

- As especialidades referenciadas em `especialidadeIds` precisam **existir** (e estar ativas).
- As faixas de um mesmo dia **não podem se sobrepor**.
- Um mesmo `diaSemana` não pode aparecer duas vezes em `jornadas`.
- A duração precisa **caber** na faixa de atendimento (`fimMinuto − inicioMinuto ≥ duracaoMinutos`).
- O `medicoId` de agendas e bloqueios precisa referenciar um médico existente.
- Dias ausentes de `jornadas` não oferecem atendimento.

---

## 6. Cenário de referência

Com a agenda da Ana (segunda 08h–12h, consultas de 30 min) e o bloqueio de 26/10/2026 (segunda-feira) das 09h às 10h, a manhã desse dia deve oferecer:

```text
08:00, 08:30, 10:00, 10:30, 11:00 e 11:30
```

Este cenário considera apenas jornada e bloqueio. Quando as consultas existentes forem incluídas, os horários ocupados também devem sair da lista. Ele serve de base para o teste de unidade `deveImpedirReservaEmPeriodoBloqueado()`.

---

## 7. Decisões

| ID | Decisão | Motivo | Situação |
|---|---|---|---|
| D1 | Uma configuração semanal por médico, com duração única para todas as especialidades | Simplificar a primeira versão e o primeiro teste | Provisória; pode evoluir para agendas por especialidade ou por período de vigência |
| D2 | Jornadas incorporadas na agenda | Dados pequenos, sempre lidos juntos | Adotada |
| D3 | Bloqueios em coleção separada | Ocorrências datadas que se acumulam | Adotada |
| D4 | Horários da jornada em minutos desde a meia-noite | Facilita os cálculos de vagas | Adotada |
| D5 | CRM como texto | Preserva zeros à esquerda | Adotada |

**Ponto a alinhar:** o índice atual de `consultas` (`uk_consulta_ativa`) usa `data` e `horario` separados, enquanto os bloqueios usam instantes (`Date`). Convém definir a representação das consultas de forma compatível ao modelar essa coleção.

---

## 8. Como executar e verificar

```bash
# Recriar o banco local (os scripts de database/init só rodam com volume vazio)
docker compose down -v
docker compose up -d mongo

# Carregar os dados fictícios (pode ser executado mais de uma vez)
docker exec -i consultafacil-mongo mongosh consultafacil --quiet < database/seed/01-agenda.js

# Verificar dados, índices e validações (todas as linhas devem começar com OK)
docker exec -i consultafacil-mongo mongosh consultafacil --quiet < scripts/verificar-modelagem.js

# Banco de testes
docker exec -i consultafacil-mongo mongosh consultafacil_test --quiet < database/init/02-esquema-agenda.js
docker exec -i consultafacil-mongo mongosh consultafacil_test --quiet < database/seed/01-agenda.js
```
