# API de cancelamento de consulta

> Issue #27 (cancelamento de consulta) · Sprint 3
> Relacionadas: #30 (telas de listagem, detalhes e cancelamento), #54 (UX do cancelamento), #28 (demais transições de status), #32 (fila de espera)

## 1. Rota e permissões

A chamada exige `Authorization: Bearer <token>`.

| Método | Caminho | Resultado | Perfis autorizados |
|---|---|---|---|
| POST | `/consultas/{id}/cancelamento` | 200, consulta cancelada | PACIENTE (só as próprias), MEDICO (só as que atende), SECRETARIA_ADMIN (todas) |

O perfil é verificado em `api/Rotas.java` (`Rotas.consultaPorId`). Quem é dono da consulta é verificado no `CancelamentoService`:

- **Paciente:** o login precisa estar ligado a um cadastro (`pacientes.usuarioId`), e a consulta precisa ser dele.
- **Médico:** o login precisa estar ligado a um cadastro (`medicos.usuarioId`), e a consulta precisa ser com ele.
- **Secretaria/admin:** pode cancelar qualquer consulta.

## 2. Requisição

Corpo **opcional**:

```json
{ "motivo": "Imprevisto no trabalho" }
```

- `motivo`: texto de até 255 caracteres. Espaços nas pontas são removidos, e motivo em branco conta como ausente. **Não escreva dados clínicos** (ver [historico.md](../modelagem/historico.md)).
- Sem corpo, ou com `{}`, a consulta é cancelada sem motivo.

Exemplo:

```
POST /consultas/6730f1a2b3c4d5e6f7a8b9c0/cancelamento
Authorization: Bearer <token>
Content-Type: application/json

{ "motivo": "Viagem" }
```

## 3. Resposta (200)

Mesmo formato do `POST /consultas`:

```json
{
  "id": "6730f1a2b3c4d5e6f7a8b9c0",
  "pacienteId": "300000000000000000000001",
  "medicoId": "100000000000000000000001",
  "especialidadeId": "000000000000000000000002",
  "inicio": "2026-11-16T11:00:00Z",
  "fim": "2026-11-16T11:30:00Z",
  "status": "CANCELADA"
}
```

## 4. O que acontece ao cancelar

1. O status passa a `CANCELADA` e o motivo é gravado em `consultas.motivoCancelamento`.
2. O histórico recebe um evento `CANCELAMENTO`, com status anterior e novo, motivo, quem cancelou (perfil e id) e a data. A gravação passa pelo `RegistroConsultaService` (ver [historico-implementacao.md](../modelagem/historico-implementacao.md)).
3. **O horário é liberado sem passo extra.** O cálculo de vagas e os índices únicos de `consultas` só consideram consultas `AGENDADA`/`CONFIRMADA` (índices parciais, ver [consultas.md](../modelagem/consultas.md)). Por isso o horário volta a aparecer em `GET /vagas` e aceita uma nova reserva.
4. A fila de espera é acionada pelo gancho `AposCancelamento.vagaLiberada(consulta)`. Hoje o App usa `AposCancelamento.NENHUM`, que não faz nada; a #32 troca pela implementação real. Uma falha na fila é registrada no log e **não** desfaz o cancelamento.

## 5. Regra de prazo

**Só é possível cancelar consulta que ainda não começou** (`inicio` posterior ao horário do servidor), para qualquer perfil.

- Cancelar uma consulta passada não liberaria nenhuma vaga, porque `GET /vagas` já esconde horários passados.
- Para uma consulta que já passou, o status correto é `CONCLUIDA` ou `FALTA` (#28).

## 6. Erros

Formato: `{ "status": 409, "erro": "mensagem" }`.

| Status | Quando |
|---|---|
| 400 | `id` inválido, JSON inválido ou motivo com mais de 255 caracteres |
| 401 | Sem login ou sessão expirada |
| 403 | Paciente ou médico tentando cancelar consulta de outra pessoa, ou login sem cadastro de paciente/médico ligado |
| 404 | Consulta não encontrada |
| 409 | Consulta já cancelada, concluída ou com falta registrada; consulta que já começou; alteração simultânea (outra pessoa mexeu na consulta ao mesmo tempo) |

## 7. Testes

| Teste | Precisa do Mongo? | O que cobre |
|---|---|---|
| `unit/CancelamentoServiceTest` | não | Acesso por perfil (dono, outro paciente, outro médico, secretaria, login sem cadastro), status finais, cancelar duas vezes, prazo (no início e 1 minuto antes), motivo (espaços, vazio, 256 caracteres), evento no histórico e gancho da fila (chamado uma vez, não chamado em recusa, falha não desfaz) |
| `integration/CancelamentoIntegrationTest` | sim (`consultafacil_test`) | A vaga volta para a busca e aceita nova reserva, histórico `CRIACAO` → `CANCELAMENTO` com motivo e ator, aviso à fila, recusa de consulta que já começou |
| `integration/RotasAutorizacaoTest` | não | Os três perfis chegam à rota, 401 sem login, 404 para método ou caminho errado, e `/consultas` sem barra continua no Router |

## 8. Para quem vai usar

- **#30 (telas):** chamar esta rota pelo botão "Cancelar" na tela de detalhes. Mostrar o botão só para consultas `AGENDADA`/`CONFIRMADA` que ainda não começaram, e tratar o 409 recarregando a consulta.
- **#28 (status):** o cancelamento pelo médico já está aqui. A #28 cuida de confirmar, concluir e registrar falta.
- **#32 (fila):** implementar `AposCancelamento` e passar a implementação no `App.java`, no lugar de `AposCancelamento.NENHUM`. A consulta recebida já vem com status `CANCELADA`.
