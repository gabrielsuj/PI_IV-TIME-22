# API de listagem e detalhe de consultas

> Issue #30 (telas de listagem, detalhes e cancelamento de consultas) · Sprint 3
> Relacionadas: #27 ([cancelamento](cancelamento.md)), #28 (alteração de status), #46 (histórico), #23 (calendário, `GET /consultas`)

## 1. Rotas e permissões

As chamadas exigem `Authorization: Bearer <token>`.

| Método | Caminho | Resultado | Perfis autorizados |
|---|---|---|---|
| GET | `/consultas/lista?periodo=&status=&medicoId=` | 200, lista de consultas | PACIENTE, MEDICO, SECRETARIA_ADMIN |
| GET | `/consultas/{id}` | 200, detalhe de uma consulta | PACIENTE, MEDICO, SECRETARIA_ADMIN |

O que cada perfil enxerga é decidido no servidor, pela classe `AcessoConsulta`. É a mesma regra do cancelamento:

- **Paciente:** só as próprias consultas. O login precisa estar ligado a um cadastro de paciente (`pacientes.usuarioId`).
- **Médico:** só as consultas em que atende, com os dados básicos do paciente (o nome). O login precisa estar ligado a um cadastro de médico (`medicos.usuarioId`).
- **Secretaria/admin:** todas.

O `GET /consultas?medicoId=&de=&ate=` (sem barra) continua sendo o calendário da #23 e não mudou.

## 2. GET /consultas/lista

| Parâmetro | Obrigatório | Valores |
|---|---|---|
| `periodo` | não | `proximas` (padrão): consultas que ainda não terminaram, da mais próxima para a mais distante. `anteriores`: consultas que já terminaram, da mais recente para a mais antiga |
| `status` | não | `AGENDADA`, `CONFIRMADA`, `CANCELADA`, `CONCLUIDA` ou `FALTA` |
| `medicoId` | não | id do médico. Para o perfil médico, o filtro se soma à restrição: filtrar por outro médico devolve lista vazia |

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
| 400 | `periodo`, `status`, `medicoId` ou `id` inválido |
| 401 | Sem login ou sessão expirada |
| 403 | Consulta de outra pessoa, ou login sem cadastro de paciente/médico ligado |
| 404 | Consulta não encontrada; caminho ou método inexistente abaixo de `/consultas/` |

## 5. Telas (front-end)

| Rota | Tela | O que faz |
|---|---|---|
| `/consultas` | `ConsultasPage` | Abas Próximas/Anteriores, filtros de status e de médico (exceto para o perfil médico), tabela com "Ver detalhes". Os filtros ficam no endereço (`?periodo=anteriores&status=…`), então dá para voltar e compartilhar o link |
| `/consultas/:id` | `ConsultaDetalhePage` | Resumo da reserva, status e motivo do cancelamento. O botão "Cancelar consulta" abre uma confirmação com motivo opcional (até 255 caracteres). No 409, o modal fecha e a tela mostra a mensagem do servidor e recarrega a consulta |

Espaços reservados na tela de detalhe, marcados com comentário no código:

- **#28:** botões de confirmar, concluir e registrar falta, ao lado do "Cancelar consulta". Depois da ação, chamar `recarregar()`.
- **#46:** card "Histórico" com a linha do tempo, abaixo do resumo.

## 6. Testes

| Teste | Precisa do Mongo? | O que cobre |
|---|---|---|
| `integration/ConsultaLeituraIntegrationTest` | sim (`consultafacil_test`) | Ordem de próximas e anteriores, nomes, `podeCancelar`, filtros de status e médico, visão do paciente, do médico e da secretaria, 400/403/404 |
| `integration/RotasAutorizacaoTest` | não | Os três perfis chegam às rotas novas; 401 sem login; 404 para caminho ou método errado |
| `frontend/src/__tests__/Consultas.test.jsx` | não | Colunas por perfil, filtros na URL e na API, lista vazia, erro com "Tentar novamente", navegação para o detalhe, cancelamento com e sem motivo, 409, erro genérico, consulta que já começou, 403/404 |
