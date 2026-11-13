# Garantias de reserva (#55)

Como o ConsultaFácil impede reservas duplicadas ou sobrepostas, e como isso é testado.

## Três camadas de proteção

| Camada | Onde | O que garante |
|---|---|---|
| 1. Busca de vagas | `CalculadoraDisponibilidade` / `GET /vagas` | Só oferece horários dentro da jornada, na grade da duração, fora de bloqueios, sem consulta ativa e ainda não iniciados |
| 2. Revalidação no agendamento | `AgendamentoService.agendar` (`DisponibilidadeService.estaLivre`) | No `POST /consultas`, o horário precisa ser **exatamente** uma das vagas livres naquele momento; senão, **409** |
| 3. Índices únicos parciais no banco | `database/init/03-esquema-consultas.js` | Entre as consultas **ativas** (`AGENDADA`/`CONFIRMADA`), no máximo uma por **(médico, início)** e uma por **(paciente, início)**. A violação (código 11000) vira **409** em `MongoErros` |

A camada 2 sozinha não basta: dois pedidos simultâneos podem passar pela revalidação ao mesmo tempo, porque os dois veem a vaga livre. Quem decide é a camada 3. O MongoDB aceita só a primeira gravação, a segunda é recusada pelo índice, e o usuário recebe "horário não está mais disponível".

Os índices são **parciais**: consultas canceladas, concluídas ou faltas não ocupam a vaga. Por isso uma vaga cancelada pode ser reservada de novo, e o histórico da consulta cancelada é preservado.

## Como é testado

| Situação | Teste | Resultado esperado |
|---|---|---|
| 10 pedidos **simultâneos** para a mesma vaga, com pacientes diferentes | `ReservasConcorrenciaIntegrationTest.deveConfirmarApenasUmAgendamentoParaMesmaVaga` | 1 × 201 e 9 × 409; 1 consulta ativa; 1 evento de histórico; a vaga sai da busca |
| O mesmo paciente em dois médicos, mesmo horário, ao mesmo tempo | `mesmoPacienteNaoReservaDoisMedicosNoMesmoHorarioAoMesmoTempo` | 201 e 409 |
| 8 pedidos simultâneos para vagas **diferentes** | `pedidosSimultaneosEmVagasDiferentesSaoTodosAceitos` | 8 × 201 (sem conflito falso) |
| Médico já ocupado: mesmo horário ou horário fora da grade | `deveRecusarSobreposicaoDeHorario` | 409; a vaga seguinte continua livre |
| Cancelamento libera a vaga | `cancelamentoLiberaAVagaParaOutroPaciente` | A vaga volta à busca, outro paciente reserva (201), histórico `CRIACAO → CANCELAMENTO` e nova `CRIACAO` |
| Mesmo paciente em horários **sobrepostos com início diferente** em outro médico | `pacienteNaoPodeTerConsultasSobrepostasEmMedicosDiferentes` | **Desativado: pendente #25** (hoje a API aceita) |
| Gravação direta no banco, em paralelo | `ConsultaReservaIntegrationTest.deveConfirmarApenasUmAgendamentoParaMesmaVaga` | Só uma gravação aceita pelo índice |

Os testes de `ReservasConcorrenciaIntegrationTest` passam pela **API de verdade**: autenticação, permissões de `Rotas`, `POST /consultas` e `GET /vagas`, com o MongoDB real. O servidor de teste atende com **16 threads**, e uma largada única (`CountDownLatch`) libera todos os pedidos juntos, para que eles disputem a vaga de fato. A suíte rodou 8 vezes seguidas sem nenhuma falha.

## Observações

- **O servidor da aplicação atende um pedido por vez.** O `App.java` não define executor no `HttpServer`, que então processa as requisições numa única thread. Hoje a corrida nem acontece em produção, mas o teste mostra que a garantia continua valendo se o servidor passar a usar várias threads (`server.setExecutor(...)`), porque ela vem do banco e não da ordem de chegada.
- **Lacuna conhecida (#25):** o índice do paciente só compara o **início**. Com médicos de durações diferentes, o mesmo paciente consegue 09:00–09:30 com um e 09:20–09:40 com outro. A correção prevista é verificar, no agendamento, as consultas ativas do paciente que se sobrepõem ao horário pedido. Quando isso for feito, basta remover o `@Disabled` do teste correspondente.
- **Cancelamento pela API (#27):** o teste usa `RegistroConsultaService.alterar`, o mesmo serviço que a rota de cancelamento vai usar.

## Como executar

```bash
bash scripts/recriar-banco.sh consultafacil_test
cd backend && ./mvnw test -Dtest=ReservasConcorrenciaIntegrationTest
```
