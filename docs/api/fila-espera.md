# API da fila de espera

> Issue #31 (registro de interesse na fila) · Sprint 4
> Relacionadas: #32 (sinalização após cancelamento), #57 (avisos de vaga). Modelo: [filaEspera.md](../modelagem/filaEspera.md)

O paciente entra na fila de uma especialidade, com um médico específico ou com qualquer médico dela. Quando uma consulta compatível é cancelada, ele é avisado (#32) e quem agendar primeiro fica com a vaga.

## 1. Rotas e permissões

Todas exigem `Authorization: Bearer <token>`.

| Método | Caminho | Resultado | PACIENTE | MEDICO | SECRETARIA_ADMIN |
|---|---|---|---|---|---|
| POST | `/fila-espera` | 201, inscrição criada | ✓ (para si mesmo) | - | ✓ (informa `pacienteId`) |
| GET | `/fila-espera` | 200, lista de inscrições | ✓ (só as próprias) | - | ✓ (todas, com filtros) |
| DELETE | `/fila-espera/{id}` | 200, inscrição removida | ✓ (só as próprias) | - | ✓ |

O login de paciente precisa estar ligado a um cadastro (`pacientes.usuarioId`); senão a resposta é 403.

## 2. POST /fila-espera

```json
{
  "especialidadeId": "000000000000000000000002",
  "medicoId": null,
  "turnoPreferido": "MANHA",
  "dataInicio": "2026-11-20",
  "dataFim": "2026-12-10"
}
```

| Campo | Obrigatório | Regra |
|---|---|---|
| `especialidadeId` | Sim | Especialidade ativa. |
| `medicoId` | Não | `null` ou ausente = qualquer médico da especialidade. Se informado, médico ativo que atende a especialidade. |
| `turnoPreferido` | Não | `MANHA` (antes das 12:00), `TARDE` (a partir das 12:00) ou `QUALQUER` (padrão). Horário de São Paulo. |
| `dataInicio` | Não | `AAAA-MM-DD`, a partir de hoje. Ausente = a partir de agora. |
| `dataFim` | Não | `AAAA-MM-DD`, igual ou depois de `dataInicio`, no máximo 90 dias à frente. Ausente = até a validade. |
| `pacienteId` | Só secretaria | Paciente para quem é a inscrição. Paciente logado não envia (ou envia o próprio). |

A inscrição vale até o fim de `dataFim` ou, sem `dataFim`, por 90 dias (`validoAte`). Depois disso passa a `EXPIRADA`.

Respostas de erro:

| Status | Quando |
|---|---|
| 400 | Campo inválido: id mal formado, turno desconhecido, data fora do formato, `dataInicio` no passado, `dataFim` antes de `dataInicio` ou além de 90 dias, médico que não atende a especialidade, secretaria sem `pacienteId`. |
| 403 | Paciente tentando inscrever outra pessoa, ou login sem cadastro de paciente. Médico. |
| 404 | Especialidade, médico ou paciente não encontrado ou inativo. |
| 409 | O paciente já tem inscrição ativa (`AGUARDANDO` ou `SINALIZADA`) para a mesma especialidade e o mesmo médico (ou "qualquer médico"). |

## 3. Formato da inscrição (resposta)

```json
{
  "id": "6740a1b2c3d4e5f6a7b8c9d0",
  "pacienteId": "300000000000000000000001",
  "pacienteNome": "Ana Souza",
  "especialidadeId": "000000000000000000000002",
  "especialidadeNome": "Cardiologia",
  "medicoId": null,
  "medicoNome": null,
  "turnoPreferido": "MANHA",
  "dataInicio": "2026-11-20",
  "dataFim": "2026-12-10",
  "status": "SINALIZADA",
  "validoAte": "2026-12-11T02:59:59.999Z",
  "criadoEm": "2026-11-13T12:30:00Z",
  "ultimaSinalizacao": {
    "consultaOrigemId": "6740a1b2c3d4e5f6a7b8c9aa",
    "medicoId": "100000000000000000000001",
    "medicoNome": "Dra. Paula Lima",
    "dataHora": "2026-11-24T13:00:00Z",
    "sinalizadaEm": "2026-11-13T14:00:00Z"
  }
}
```

- **Campos sem valor não aparecem na resposta** (a API omite `null`, como nas outras rotas). No front, trate campo ausente como vazio.
- `medicoId`/`medicoNome` ficam sem valor quando a inscrição vale para qualquer médico.
- `dataInicio`/`dataFim` são datas locais (`AAAA-MM-DD`), sem valor quando não foram informadas. Datas com hora estão em ISO 8601 UTC.
- `ultimaSinalizacao` só aparece quando `status = SINALIZADA`: é a vaga liberada que o paciente pode tentar agendar em `POST /consultas` (com o `medicoId` e o horário dela).

Status: `AGUARDANDO`, `SINALIZADA`, `ATENDIDA`, `CANCELADA`, `EXPIRADA` (significados em [filaEspera.md](../modelagem/filaEspera.md#status-do-interesse)).

## 4. GET /fila-espera

Lista em ordem de chegada (`criadoEm`).

| Parâmetro | Quem usa | Efeito |
|---|---|---|
| `status` | todos | Um dos 5 status. Sem ele, só as ativas (`AGUARDANDO` e `SINALIZADA`). |
| `pacienteId` | secretaria | Só as inscrições desse paciente. |
| `especialidadeId` | secretaria | Só as inscrições dessa especialidade. |

Antes de responder, inscrições ativas vencidas passam a `EXPIRADA`.

## 5. DELETE /fila-espera/{id}

Remove a inscrição: o status passa a `CANCELADA` e a resposta (200) traz a inscrição atualizada. Sem corpo.

| Status | Quando |
|---|---|
| 403 | Paciente tentando remover inscrição de outra pessoa. |
| 404 | Inscrição não encontrada. |
| 409 | Inscrição já encerrada (`ATENDIDA`, `CANCELADA` ou `EXPIRADA`). |

## 6. Sugestão para a tela do paciente (front)

- Formulário: especialidade (`GET /especialidades`), médico opcional filtrado pela especialidade (`GET /medicos`), turno e período opcional.
- Lista "Minhas inscrições" (`GET /fila-espera`) com o status. Em `SINALIZADA`, mostrar a vaga liberada e um atalho para agendar.
- Botão "Sair da fila" (`DELETE /fila-espera/{id}`).
- Mensagens de erro: usar o campo `erro` da resposta (`{ "status": 409, "erro": "..." }`), como nas outras telas.
