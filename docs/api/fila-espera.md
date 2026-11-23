<!--
Autoria: Gabriel Martins de Almeida
RA: 25006162
-->

# API da fila de espera

> Issue #31 (registro de interesse na fila) · Sprint 4
> Relacionadas: #32 (sinalização após cancelamento), #57 (avisos de vaga). Modelo: [filaEspera.md](../dicionario/filaEspera.md)

O paciente entra na fila de uma especialidade, com um médico específico ou com qualquer médico dela. Quando uma consulta compatível é cancelada, ele é avisado (#32) e pode procurar a secretaria para agendar. A primeira reserva confirmada pela secretaria fica com a vaga.

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
- `ultimaSinalizacao` só aparece quando `status = SINALIZADA`: inclui o `notificacaoId` usado para consultar o aviso de vaga.

Status: `AGUARDANDO`, `SINALIZADA`, `ATENDIDA`, `CANCELADA`, `EXPIRADA` (significados em [filaEspera.md](../dicionario/filaEspera.md#status-do-interesse)).

## 4. GET /fila-espera

Lista em ordem de chegada (`criadoEm`).

| Parâmetro | Quem usa | Efeito |
|---|---|---|
| `status` | todos | Um dos 5 status. Sem ele, só as ativas (`AGUARDANDO` e `SINALIZADA`). |
| `pacienteId` | secretaria | Só as inscrições desse paciente. |
| `especialidadeId` | secretaria | Só as inscrições dessa especialidade. |

Antes de responder, a fila é revisada: inscrições ativas vencidas passam a `EXPIRADA`, e sinalizações de vagas que já passaram voltam a `AGUARDANDO`.

Uma inscrição `SINALIZADA` muda sozinha quando a vaga é ocupada: vira `ATENDIDA` se a secretaria agendou aquele horário para o interessado (`POST /consultas` com o médico e o início da `ultimaSinalizacao`), ou volta a `AGUARDANDO` se a secretaria agendou primeiro para outro paciente.

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

## 7. Avisos de vaga e agendamento (#57)

Na tela da fila, o paciente abre o aviso indicado por `ultimaSinalizacao.notificacaoId`. A secretaria pode iniciar o agendamento em nome do paciente.

A opção **Avisos de vaga** aparece no menu do paciente. O aviso nasce quando uma consulta é cancelada e há uma inscrição compatível. O paciente pode abrir o aviso e conferir o horário. Para agendar, deve procurar a secretaria; a tela não oferece confirmação nem links para Nova consulta. A lista atualiza a cada 15 segundos, ao recuperar o foco e pela atualização manual.

| Método | Caminho | Resultado |
|---|---|---|
| GET | `/avisos-vaga` | Lista os próprios avisos de vaga, incluindo expirados e agendados, do mais recente ao mais antigo. |
| POST | `/avisos-vaga` | Agendamento pelo aviso desabilitado: 403 para qualquer perfil autenticado. A secretaria agenda em `POST /consultas`. |

A leitura exige token de paciente vinculado a um cadastro. Sem sessão, as rotas retornam 401. `POST /avisos-vaga` retorna 403 antes de processar o corpo; os próprios avisos continuam disponíveis em `GET /avisos-vaga`.

Depois de salvar um bloqueio ou uma alteração de jornada, a API revalida as ofertas do médico com as regras de disponibilidade do agendamento. Se o intervalo já não puder ser reservado, o aviso aparece como `EXPIRADO`. A inscrição ainda válida volta a `AGUARDANDO` na posição original. A leitura do aviso também revalida o intervalo; a secretaria revalida a disponibilidade ao confirmar a consulta.

A lista devolve `id`, `medicoNome`, `medicoId`, `especialidadeId`, `inicio`, `fim`, `validoAte`, `status`, `motivo` e, quando agendado, `consultaAgendadaId`. Os horários estão em ISO 8601 UTC. A validade termina no início da vaga ou no vencimento da inscrição, o que ocorrer primeiro.

- **`DISPONIVEL`**: a oportunidade pode ser consultada pelo paciente, que deve solicitar o agendamento à secretaria.
- **`AGENDADO`**: o paciente ficou com a vaga. A inscrição passa a `ATENDIDA`.
- **`EXPIRADO`**: a vaga venceu, outra pessoa a ocupou ou a inscrição deixou de corresponder ao aviso. Ao perder a vaga para outro paciente, a inscrição volta a `AGUARDANDO` e mantém a posição.

### 7.1 Motivo da expiração (#58)

Quando `status = EXPIRADO`, o campo `motivo` diz à tela qual mensagem mostrar. Nos outros status ele vem `null`.

| `motivo` | Quando acontece | O que a tela mostra |
|---|---|---|
| `VAGA_OCUPADA` | Outra consulta ativa já ocupa o horário (a secretaria reservou para outro paciente). | Avisa que a vaga já foi ocupada e mantém o acesso à **Fila de espera**. |
| `PRAZO_ENCERRADO` | Chegou o horário da vaga ou venceu a inscrição na fila (`validoAte`). | Avisa que o prazo do aviso terminou. |
| `OPORTUNIDADE_DESATUALIZADA` | A agenda mudou (bloqueio ou jornada), a inscrição foi cancelada ou recebeu outro aviso mais novo. | Avisa que a oferta ficou desatualizada e mantém o acesso à **Fila de espera**. |

A ordem de avaliação é `VAGA_OCUPADA` → `PRAZO_ENCERRADO` → `OPORTUNIDADE_DESATUALIZADA`: se o horário já tem dono, essa é a informação mais útil, mesmo que o prazo também tenha passado.

`medicoId` e `especialidadeId` vêm da consulta de origem. O paciente consulta o aviso; somente a secretaria pode abrir **Nova consulta** e reservar o horário.
