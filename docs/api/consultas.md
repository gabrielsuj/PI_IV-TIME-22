# API de listagem e detalhe de consultas

> Issue #30 (telas de listagem, detalhes e cancelamento de consultas) · Sprint 3
> Relacionadas: #27 ([cancelamento](cancelamento.md)), #28 (alteração de status), #46 (histórico), #23 (calendário, `GET /consultas`)

## 1. Rotas e permissões

As chamadas exigem `Authorization: Bearer <token>`.

| Método | Caminho | Resultado | Perfis autorizados |
|---|---|---|---|
| GET | `/consultas/lista?periodo=&status=&medicoId=&somenteAtivas=` | 200, lista de consultas | PACIENTE, MEDICO, SECRETARIA_ADMIN |
| GET | `/consultas/{id}` | 200, detalhe de uma consulta | PACIENTE, MEDICO, SECRETARIA_ADMIN |
| GET | `/consultas?medicoId=&de=&ate=&fusoHorario=` | 200, [consultas do calendário](#7-get-consultas-calendário) | MEDICO, SECRETARIA_ADMIN |

Na listagem e no detalhe, o servidor usa `AcessoConsulta` para decidir o que cada perfil enxerga, com a mesma regra do cancelamento.

- **Paciente:** só as próprias consultas. O login precisa estar ligado a um cadastro de paciente (`pacientes.usuarioId`).
- **Médico:** só as consultas em que atende, com os dados básicos do paciente (o nome). O login precisa estar ligado a um cadastro de médico (`medicos.usuarioId`).
- **Secretaria/admin:** todas.

## 2. GET /consultas/lista

| Parâmetro | Obrigatório | Valores |
|---|---|---|
| `periodo` | não | `proximas` (padrão): consultas que ainda não terminaram, da mais próxima para a mais distante. `anteriores`: consultas que já terminaram, da mais recente para a mais antiga |
| `status` | não | `AGENDADA`, `CONFIRMADA`, `CANCELADA`, `CONCLUIDA` ou `FALTA` |
| `medicoId` | não | id do médico. Para o perfil médico, o filtro se soma à restrição: filtrar por outro médico devolve lista vazia |
| `somenteAtivas` | não | `true` seleciona `AGENDADA` e `CONFIRMADA` antes da ordenação e do limite. Omitir ou enviar `false` mantém a listagem usual. O filtro se soma a `status`, `periodo` e `medicoId`. |

A lista traz no máximo **100** consultas (`ConsultaLeituraService.LIMITE`). A tela avisa quando esse limite é atingido.

## 3. Formato da consulta (lista e detalhe)

```json
{
  "id": "7000000000000000000002df",
  "pacienteId": "300000000000000000000001",
  "pacienteNome": "Paulo Paciente — Fictício",
  "medicoId": "100000000000000000000005",
  "medicoNome": "Juliana Ferraz",
  "especialidadeId": "000000000000000000000004",
  "especialidadeNome": "Dermatologia",
  "inicio": "2026-11-20T13:00:00Z",
  "fim": "2026-11-20T13:20:00Z",
  "status": "AGENDADA",
  "podeCancelar": true
}
```

- `inicio` e `fim` vêm em UTC. A tela exibe no fuso da clínica (`America/Sao_Paulo`).
- `motivoCancelamento` só aparece em consulta cancelada com motivo. Campos nulos são omitidos do JSON.
- `podeCancelar`: o servidor já aplicou a regra da #27 (consulta `AGENDADA`/`CONFIRMADA` que ainda não começou). A tela só mostra ou esconde o botão, sem repetir a regra.

## 4. Erros

| Status | Quando |
|---|---|
| 400 | `periodo`, `status`, `medicoId`, `somenteAtivas` ou `id` inválido |
| 401 | Sem login ou sessão expirada |
| 403 | Consulta de outra pessoa, ou login sem cadastro de paciente/médico ligado |
| 404 | Consulta não encontrada; caminho ou método inexistente abaixo de `/consultas/` |

## 5. Telas (front-end)

| Rota | Tela | O que faz |
|---|---|---|
| `/consultas` | `ConsultasPage` | Abas Próximas/Anteriores, filtros de status e de médico (exceto para o perfil médico), tabela com "Ver detalhes". Os filtros ficam no endereço (`?periodo=anteriores&status=…`), então dá para voltar e compartilhar o link |
| `/consultas/:id` | `ConsultaDetalhePage` | Resumo da reserva, status e motivo do cancelamento. O botão "Cancelar consulta" abre uma confirmação com motivo opcional (até 255 caracteres). No 409, o modal fecha e a tela mostra a mensagem do servidor e recarrega a consulta |

O detalhe permite confirmar presença, registrar conclusão ou falta conforme as permissões e o horário da consulta. A tela envia apenas o campo `status` a `POST /consultas/{id}/status`. A linha do tempo usa `GET /consultas/{id}/historico` e aceita o cursor exclusivo `aposSequencia` e um `limite` de 1 a 100 eventos por página.

## 6. Testes

| Teste | Precisa do Mongo? | O que cobre |
|---|---|---|
| `integration/ConsultaLeituraIntegrationTest` | sim (`consultafacil_test`) | Ordem de próximas e anteriores, nomes, `podeCancelar`, filtros de status e médico, consultas ativas filtradas antes do limite, visão do paciente, do médico e da secretaria, 400/403/404 |
| `integration/RotasAutorizacaoTest` | não | Os três perfis chegam às rotas novas; 401 sem login; 404 para caminho ou método errado |
| `frontend/src/__tests__/Consultas.test.jsx` | não | Colunas por perfil, filtros na URL e na API, lista vazia, erro com "Tentar novamente", navegação para o detalhe, cancelamento com e sem motivo, 409, erro genérico, consulta que já começou, 403/404 |

## 7. GET /consultas (calendário)

O calendário operacional lê as consultas de um médico ativo em um período. A secretaria informa o ID de cadastro do médico. O perfil `MEDICO` usa `meu` ou o ID do próprio cadastro.

| Parâmetro | Obrigatório | Uso |
|---|---|---|
| `medicoId` | sim | ID de cadastro do médico ou `meu` no perfil `MEDICO` |
| `de` | sim | Data inicial em `AAAA-MM-DD` |
| `ate` | sim | Data final em `AAAA-MM-DD`, igual ou posterior a `de`. Até 62 dias com ambas as datas incluídas. |
| `fusoHorario` | não | Quando informado, aceita apenas `America/Sao_Paulo`. Ao omitir, a API usa o fuso da agenda ou `America/Sao_Paulo` se o médico ainda não tem agenda. |

O fuso define a janela da busca e o horário local de `inicio` e `fim` na resposta. A janela começa no início de `de` e termina no início do dia seguinte a `ate`, com o fim excluído. A API inclui consultas que se sobrepõem a essa janela, inclusive as que começaram antes dela e terminaram dentro dela.

O painel usa o fuso da clínica para consultar a agenda do dia. Por exemplo, um atendimento que começa em `2026-11-21T00:30:00Z` entra no recorte de 20/11 e aparece às 21h30 em São Paulo, mesmo com a agenda em UTC.

```http
GET /consultas?medicoId=100000000000000000000001&de=2026-11-20&ate=2026-11-20&fusoHorario=America%2FSao_Paulo
Authorization: Bearer <token>
```

A resposta 200 é um array ordenado pelo início da consulta, com todos os estados e sem paginação. Quando nenhuma consulta se sobrepõe ao recorte, a resposta é `[]`. Os horários são datas-horas locais em ISO 8601, sem `Z` ou deslocamento.

```json
[
  {
    "id": "700000000000000000000001",
    "medicoId": "100000000000000000000001",
    "pacienteId": "400000000000000000000001",
    "pacienteNome": "Paulo Paciente — Fictício",
    "especialidadeId": "000000000000000000000002",
    "especialidadeNome": "Clínica Geral",
    "inicio": "2026-11-20T21:30",
    "fim": "2026-11-20T22:00",
    "status": "AGENDADA"
  }
]
```

| Status | Quando |
|---|---|
| 400 | Parâmetro obrigatório ausente ou inválido, período invertido ou superior a 62 dias, ou `fusoHorario` vazio ou diferente de `America/Sao_Paulo` |
| 401 | Token ausente, inválido ou expirado |
| 403 | Perfil sem acesso, médico sem cadastro vinculado ou consulta à agenda de outro médico |
| 404 | Médico inativo ou inexistente |

`CalendarioFusoHttpTest` cobre a janela, a serialização dos horários e as permissões pela rota HTTP. `ConsultaCalendarioIntegrationTest` verifica o recorte com MongoDB. [Estratégia de testes](../testes/estrategia-de-testes.md)
