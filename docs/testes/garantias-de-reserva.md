<!--
Autoria: Gabriel Martins de Almeida
RA: 25006162
-->

# Garantias de reserva (#55)

Como o ConsultaFácil impede reservas duplicadas ou sobrepostas, e como isso é testado.

## Proteção das reservas

| Camada | Onde | O que garante |
|---|---|---|
| 1. Busca de vagas | `CalculadoraDisponibilidade` / `GET /vagas` | Só oferece horários dentro da jornada, na grade da duração, fora de bloqueios, sem consulta ativa e ainda não iniciados |
| 2. Revalidação no agendamento | `AgendamentoService.agendar` (`DisponibilidadeService.exigirVagaLivre`) | No `POST /consultas`, o horário precisa ser **exatamente** uma das vagas livres naquele momento; senão, **409** |
| 3. Intervalos do médico e do paciente | `ConsultaRepository.inserirComEvento` e `atualizarComEvento` | A instância do backend serializa a verificação e a gravação por médico e por paciente, na criação e nas alterações de horário, médico ou paciente. A sobreposição com uma consulta ativa retorna **409**, mesmo entre médicos diferentes e com inícios diferentes. |
| 4. Índices únicos parciais no banco | `database/init/03-esquema-consultas.js` | Entre as consultas **ativas** (`AGENDADA`/`CONFIRMADA`), no máximo uma por **(médico, início)** e uma por **(paciente, início)**. A violação (código 11000) vira **409** em `MongoErros` |

Dois pedidos simultâneos podem encontrar ambas as vagas livres. A seção crítica na gravação verifica os dois intervalos. Quando a segunda requisição encontra uma consulta ativa sobreposta, recebe 409 com o motivo do conflito.

Os índices são **parciais**: consultas canceladas, concluídas ou faltas não ocupam a vaga. Por isso uma vaga cancelada pode ser reservada de novo, e o histórico da consulta cancelada é preservado.

## Como é testado

| Situação | Teste | Resultado esperado |
|---|---|---|
| 10 pedidos **simultâneos** para a mesma vaga, com pacientes diferentes | `ReservasConcorrenciaIntegrationTest.deveConfirmarApenasUmAgendamentoParaMesmaVaga` | 1 × 201 e 9 × 409; 1 consulta ativa; 1 evento de histórico; a vaga sai da busca |
| O mesmo paciente em dois médicos, mesmo horário, ao mesmo tempo | `mesmoPacienteNaoReservaDoisMedicosNoMesmoHorarioAoMesmoTempo` | 201 e 409 |
| 8 pedidos simultâneos para vagas **diferentes** | `pedidosSimultaneosEmVagasDiferentesSaoTodosAceitos` | 8 × 201 (sem conflito falso) |
| Médico já ocupado: mesmo horário ou horário fora da grade | `deveRecusarSobreposicaoDeHorario` | 409; a vaga seguinte continua livre |
| Cancelamento libera a vaga | `cancelamentoLiberaAVagaParaOutroPaciente` | A vaga volta à busca, outro paciente reserva (201), histórico `CRIACAO → CANCELAMENTO` e nova `CRIACAO` |
| Mesmo paciente em horários **sobrepostos com início diferente** em outro médico | `pacienteNaoPodeTerConsultasSobrepostasEmMedicosDiferentes` | 409 para sobreposição e 201 para horários adjacentes ou liberados por cancelamento |
| Consulta movida para cima de outra do mesmo paciente | `alteracaoDeHorarioNaoPodeSobreporOutraConsultaDoPaciente` | 409 sem gravar evento; horário adjacente e sobreposição com o próprio horário antigo são aceitos |
| Dois pedidos simultâneos do mesmo paciente com sobreposição e inícios diferentes | `mesmoPacienteNaoReservaHorariosSobrepostosComIniciosDiferentesAoMesmoTempo` | 201 e 409, com uma consulta ativa e um evento de criação |
| Gravação direta no banco, em paralelo | `ConsultaReservaIntegrationTest.deveConfirmarApenasUmAgendamentoParaMesmaVaga` | Só uma gravação aceita pelo índice |

Os testes de `ReservasConcorrenciaIntegrationTest` usam o MongoDB real e passam pela autenticação, por `POST /consultas` e por `GET /vagas`. O servidor de teste atende com **16 threads**. No cenário da #25, uma barreira faz com que os dois pedidos encontrem as vagas livres antes da disputa pela gravação.

## Reserva durante uma alteração da agenda

Na mesma instância da API, o agendamento, a criação de bloqueios e a alteração da jornada usam o mesmo monitor do médico desde a validação da disponibilidade ou das consultas afetadas até a gravação. A proteção contra sobreposição de paciente entre médicos continua adquirindo a trava do paciente depois da trava do médico.

O teste `AgendamentoIntegrationTest.reservaDisputaHorarioComMudancaDaAgenda` cobre quatro combinações com MongoDB. Quando o bloqueio ou a redução da jornada entra primeiro, a reserva recebe 409. Quando a reserva entra primeiro, a secretaria recebe 409 com a consulta afetada e pode confirmar a mudança explicitamente. A consulta permanece agendada para remarcação.

## Observações

- A proteção de intervalos de médico e paciente cobre requisições simultâneas atendidas pela mesma instância do backend. As gravações diretas no MongoDB têm as garantias dos índices únicos.
- **Cancelamento pela API (#27):** o teste usa `RegistroConsultaService.alterar`, o mesmo serviço que a rota de cancelamento usa.

## Como executar

```bash
bash scripts/recriar-banco.sh consultafacil_test
cd backend && ./mvnw test -Dtest=ReservasConcorrenciaIntegrationTest
```
