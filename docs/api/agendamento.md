# API de disponibilidade, busca de vagas e agendamento

> Issues #20 (cálculo de disponibilidade) e #21 (busca de vagas e confirmação do agendamento) · Sprint 2
> Relacionadas: #23 (calendário), #24 (telas de agendamento), #25 (conflitos), #53 (testes da agenda)

## 1. Rotas e permissões

Todas as chamadas exigem `Authorization: Bearer <token>`.

| Método | Caminho | Resultado | Perfis autorizados |
|---|---|---|---|
| GET | `/vagas?medicoId=&especialidadeId=&data=AAAA-MM-DD` | 200, vagas de um dia | PACIENTE, MEDICO, SECRETARIA_ADMIN |
| GET | `/vagas?medicoId=&especialidadeId=&de=AAAA-MM-DD&ate=AAAA-MM-DD` | 200, vagas do intervalo (até 31 dias, datas inclusivas) | PACIENTE, MEDICO, SECRETARIA_ADMIN |
| POST | `/consultas` | 201, consulta criada | PACIENTE (só para si mesmo), SECRETARIA_ADMIN |

A matriz completa de permissões fica em `api/Rotas.java` e é testada em `RotasAutorizacaoTest`.

## 2. Cálculo de disponibilidade (#20)

Implementado em `service/CalculadoraDisponibilidade.java` (sem banco) e `service/DisponibilidadeService.java` (busca os dados).

1. Pega a agenda do médico. Sem agenda → `semAgenda: true` e lista vazia.
2. Procura a jornada do dia da semana pedido (ISO: 1 = segunda … 7 = domingo). Sem jornada nesse dia → lista vazia.
3. Em cada faixa, gera horários de `duracaoMinutos` em `duracaoMinutos`, a partir do início da faixa. Um horário só entra se **couber inteiro** na faixa.
4. Remove os horários que se sobrepõem a um **bloqueio** ou a uma **consulta ativa** (`AGENDADA`/`CONFIRMADA`). Sobreposição: `inicio < outro.fim` e `fim > outro.inicio` (início incluído, fim excluído, igual ao banco).
5. Remove os horários que **já começaram**.

Exemplo (cenário de referência de [medicos-agenda.md §6](../modelagem/medicos-agenda.md)): Ana, segunda 26/10/2026, jornada 08h–12h, consultas de 30 min, bloqueio das 09h às 10h → `08:00, 08:30, 10:00, 10:30, 11:00, 11:30`.

O mesmo cálculo é usado na confirmação (`DisponibilidadeService.estaLivre`). O horário enviado precisa ser **exatamente** uma das vagas livres, e por isso nunca se agenda fora da jornada, fora da grade de horários, em bloqueio ou no passado.

## 3. GET /vagas (#21)

```
GET /vagas?medicoId=100000000000000000000001&especialidadeId=000000000000000000000002&data=2026-11-16
```

```json
{
  "medicoId": "100000000000000000000001",
  "fusoHorario": "America/Sao_Paulo",
  "semAgenda": false,
  "horarios": [
    { "inicio": "2026-11-16T11:00:00Z", "fim": "2026-11-16T11:30:00Z" },
    { "inicio": "2026-11-16T11:30:00Z", "fim": "2026-11-16T12:00:00Z" }
  ]
}
```

- `inicio` e `fim` estão em **UTC** (ISO 8601). A interface converte para o `fusoHorario` da agenda: `11:00Z` = 08:00 em São Paulo.
- `data` é a data no fuso da agenda. Para um intervalo, use `de` e `ate` no lugar de `data`.
- Lista vazia com `semAgenda: false` = não há horário livre nessa data.

## 4. POST /consultas (#21)

Corpo (os horários são copiados de `GET /vagas`):

```json
{
  "pacienteId": "400000000000000000000002",
  "especialidadeId": "000000000000000000000002",
  "medicoId": "100000000000000000000001",
  "inicio": "2026-11-16T11:00:00Z",
  "fim": "2026-11-16T11:30:00Z"
}
```

Resposta 201:

```json
{
  "id": "6730a1f3c2b4d5e6f7a8b9c0",
  "pacienteId": "400000000000000000000002",
  "medicoId": "100000000000000000000001",
  "especialidadeId": "000000000000000000000002",
  "inicio": "2026-11-16T11:00:00Z",
  "fim": "2026-11-16T11:30:00Z",
  "status": "AGENDADA"
}
```

- **Secretaria:** `pacienteId` é obrigatório (agenda em nome do paciente).
- **Paciente:** pode omitir `pacienteId`. A API usa o cadastro ligado ao login (`pacientes.usuarioId`, ver [usuarios-pacientes.md](../modelagem/usuarios-pacientes.md)). Informar outro paciente → 403.
- A disponibilidade é **revalidada no momento da confirmação**. Se duas pessoas confirmarem a mesma vaga ao mesmo tempo, o índice único `uk_consulta_medico_inicio` aceita só uma (ver [consultas.md §5](../modelagem/consultas.md)).
- A gravação passa pelo `RegistroConsultaService`, que registra o evento `CRIACAO` no histórico.

## 5. Erros

Formato: `{ "status": 409, "erro": "mensagem" }`.

| Status | Quando |
|---|---|
| 400 | ID inválido, data fora do formato, intervalo invertido ou maior que 31 dias, médico que não atende a especialidade, secretaria sem `pacienteId` |
| 401 | Sem sessão ou sessão expirada |
| 403 | Perfil sem acesso; paciente agendando para outra pessoa; login de paciente sem cadastro ligado |
| 404 | Médico, especialidade ou paciente inexistente ou inativo |
| 409 | O horário não está livre: ocupado, bloqueado, fora da jornada/grade ou já passou |

## 6. Testes

| Arquivo | Precisa do MongoDB? | O que cobre |
|---|---|---|
| `unit/CalculadoraDisponibilidadeTest` | não | Jornada × duração, bloqueio (cenário de referência), consulta ativa, sobreposição parcial, dia sem jornada, horário passado, horário que não cabe na faixa |
| `integration/AgendamentoIntegrationTest` | sim (`consultafacil_test`) | Busca por dia e por intervalo, médico sem agenda, especialidade errada, agendamento pela secretaria e pelo paciente, `deveImpedirReservaEmPeriodoBloqueado` (Quadro 4), fora da jornada, horário passado, 409 para horário já reservado |
| `integration/RotasAutorizacaoTest` | não | Perfis de `/vagas` e `/consultas` |
| `frontend/src/__tests__/agendamentoService.test.js` | não | Chamadas do front para `/vagas` e `/consultas` |

```bash
cd backend
./mvnw test -Dtest=CalculadoraDisponibilidadeTest               # sem banco
./mvnw test -Dtest=AgendamentoIntegrationTest                   # com o Mongo no ar
```

## 7. Pendências relacionadas

- **#25:** recusar sobreposição de horário do **mesmo paciente** com outro médico quando os inícios forem diferentes (há um `TODO #25` em `AgendamentoService`). O mesmo instante de início já é barrado pelo índice `uk_consulta_paciente_inicio`.
- **#44:** ao alterar a agenda ou criar bloqueio, tratar consultas já marcadas no período.
- Pacientes cadastrados antes desta mudança não têm `usuarioId`. O seed de demonstração liga o Paulo ao login `paciente@consultafacil.demo`.
