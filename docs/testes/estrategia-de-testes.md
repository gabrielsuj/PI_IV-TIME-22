# Estratégia de testes

| Comando | Cobertura |
|---|---|
| `./mvnw test` | Testes unitários e de integração com MongoDB. |
| `./mvnw test -DmongoTests=true` com `HISTORICO_MONGO_URI` (ver [Como executar](#como-executar)) | Inclui também as suítes de cadastro e persistência do histórico, como no workflow de CI. |
| `DEMO_MONGO_DB=consultafacil_demo ./mvnw test` (ver [Como executar](#como-executar)) | Inclui a conferência dos indicadores com a carga de demonstração, como no workflow de CI. |

## Como os testes são organizados

| Camada | Ferramenta | Onde | O que verifica |
|---|---|---|---|
| Regras de negócio isoladas | JUnit 5 | `backend/src/test/.../unit` | Validações, cálculos e segurança sem banco nem servidor |
| API e banco | JUnit 5 + MongoDB real | `backend/src/test/.../integration` | Serviços, repositórios, índices e respostas HTTP com um MongoDB de testes |
| Permissões | JUnit 5 + servidor HTTP real | `RotasAutorizacaoTest` | Matriz de rotas × perfis (200, 401, 403) usando a tabela real de `Rotas.java` |
| Interface | Vitest + Testing Library (jsdom) | `frontend/src/__tests__` | Telas, navegação, sessão, mensagens de erro e chamadas à API (simulada) |
| Dados de demonstração | Script mongosh | `scripts/verificar-carga.js` | Coerência da carga: jornadas, bloqueios, sobreposições, histórico e logins |

Os testes de integração usam bancos exclusivos, que são limpos a cada teste e nunca tocam o banco de desenvolvimento ou de demonstração:

| Banco | Usado por |
|---|---|
| `consultafacil_test` | Todos os testes de integração |
| `consultafacil_historico_test` | `HistoricoPersistenceIntegrationTest` (opcional) |
| `consultafacil_demo` (ou o banco em `DEMO_MONGO_DB`) | `IndicadoresDemonstracaoIntegrationTest` (opcional, só leitura) |

## Cenários de aceite do relatório (Quadro 4)

| Cenário | Teste | Situação |
|---|---|---|
| Dois usuários escolhem a mesma vaga: só um agendamento é confirmado | `ReservasConcorrenciaIntegrationTest.deveConfirmarApenasUmAgendamentoParaMesmaVaga` (10 pedidos simultâneos pela API) e `ConsultaReservaIntegrationTest` (gravações simultâneas no banco) | ✅ |
| Paciente ou médico já ocupado: a sobreposição é recusada | `ReservasConcorrenciaIntegrationTest` cobre o médico ocupado e a sobreposição do paciente entre médicos diferentes, inclusive com pedidos simultâneos e inícios diferentes. | ✅ |
| Horário bloqueado ou fora da agenda não aparece nem pode ser reservado | `AgendamentoIntegrationTest.deveImpedirReservaEmPeriodoBloqueado`, `deveRecusarHorarioForaDaJornada`, `deveRecusarHorarioQueJaPassou`; `CalculadoraDisponibilidadeTest` | ✅ |
| Cancelamento libera a vaga, atualiza o histórico e sinaliza a fila | `SinalizacaoFilaIntegrationTest.deveLiberarVagaRegistrarHistoricoESinalizarFila`; `CancelamentoIntegrationTest.deveLiberarAVagaParaABuscaEParaNovaReserva`; `FilaEsperaIntegrationTest.inscricaoSinalizadaMostraAVagaLiberada`; `ReservasConcorrenciaIntegrationTest.cancelamentoLiberaAVagaParaOutroPaciente`; `HistoricoTest`; sob concorrência, `CancelamentoConcorrenciaIntegrationTest` (#40) | ✅ |
| Acesso a função de outro perfil é bloqueado pelo servidor | `RotasAutorizacaoTest.deveBloquearAcessoDePerfilNaoAutorizado`, `matrizCompletaDePermissoes` (toda rota × os três perfis) e `todaRotaDaMatrizExigeLogin` | ✅ |
| Formulário inválido ou API indisponível: a interface informa sem perder o contexto | `UxPacientes.test.jsx`, `Registration.test.jsx`, `App.test.jsx` (API fora do ar), `AgendaConflitos.test.jsx` | ✅ |

### Verificação dos critérios de aceite (#40, 20/11/2026)

Executada no `develop` (commit `535c06b`, com #34 e #48) mais os testes da #40:

| Suíte | Comando | Resultado |
|---|---|---|
| Back-end, como no CI | `HISTORICO_MONGO_URI=... DEMO_MONGO_DB=... ./mvnw -DmongoTests=true test` | 858 testes, 0 falhas, 0 ignorados |
| Front-end | `npm test`, `npm run lint`, `npm run build` | 307 testes em 21 arquivos; lint e build sem erros |

Ampliações da #40: matriz de permissões completa (toda rota × perfil, incluindo `/medicos` e `/especialidades`, que antes não eram montadas no teste) e cancelamento concorrente com fila e notificações ligadas.

> No Windows, rode o `npm test` a partir de um caminho com a letra do disco em maiúscula (`C:\...`). Com `c:\...`, o Vitest carrega o próprio módulo duas vezes e todos os arquivos falham com "Vitest failed to find the current suite", sem nenhum teste executado.

## Matriz de permissões

Fonte: `api/Rotas.java`. Cada linha é testada com os três perfis e sem login (`RotasAutorizacaoTest.PERMISSOES`). Quando a rota é aceita, o serviço ainda confere se o registro pertence a quem chamou (por exemplo, o paciente só cancela a própria consulta).

| Operação | Paciente | Médico | Secretaria |
|---|:-:|:-:|:-:|
| Agenda e bloqueios (`/agendas`, `/bloqueios`), calendário (`GET /consultas`), indicadores | — | ✅ | ✅ |
| Ler médicos e especialidades | ✅ | ✅ | ✅ |
| Cadastrar ou editar médicos e especialidades | — | — | ✅ |
| Ler pacientes (`/pacientes`, `/pacientes/{id}`) | — | ✅ | ✅ |
| Cadastrar ou editar pacientes | — | — | ✅ |
| Buscar vagas (`GET /vagas`) | ✅ | ✅ | ✅ |
| Agendar (`POST /consultas`) | ✅ | — | ✅ |
| Listar, detalhar, cancelar, mudar status e ver histórico de consulta (`/consultas/...`) | ✅ | ✅ | ✅ |
| Fila de espera (`/fila-espera`) | ✅ | — | ✅ |
| Notificações: ler e marcar como lida | ✅ | ✅ | — |
| Notificações: confirmar presença | ✅ | — | — |
| Avisos de vaga (`/avisos-vaga`) | ✅ | — | — |

## O que cada grupo de testes cobre

### Back-end: unidade (`unit/`)

| Classe | Cobertura |
|---|---|
| `SenhaHasherTest`, `SessoesTest` | Hash PBKDF2 com salt, sessão com expiração e logout |
| `PacienteValidatorTest`, `PacienteServiceTest` | Campos obrigatórios, CPF e e-mail, unicidade |
| `CadastroServiceTest` | Regras de médicos e especialidades (vínculo, desativação) |
| `ValidadorAgendaTest` | Jornada semanal: faixas sobrepostas, dias repetidos, duração e fuso |
| `CalculadoraDisponibilidadeTest` | Vagas a partir da jornada, descontando bloqueios, consultas e horários passados |
| `HistoricoTest` | Transições de status e eventos de histórico por perfil |
| `CancelamentoServiceTest` | Regras do cancelamento: quem pode cancelar o quê, status final, consulta que já começou, motivo e falha na fila sem desfazer o cancelamento |
| `TurnoFilaTest` | Regra de turno da fila no fuso de São Paulo |

### Back-end: integração (`integration/`, requer MongoDB)

| Classe | Cobertura |
|---|---|
| `MongoConnectionIntegrationTest` | Conexão com o banco de testes |
| `AuthHandlerTest` | Login, mensagens de recusa, usuário inativo, token expirado e logout pela API |
| `RotasAutorizacaoTest` | Matriz de permissões completa: cada rota × os três perfis (200/403) e sem login (401), a partir da tabela `PERMISSOES`, além de casos de rota inexistente (404), logout e sessões simultâneas (sem banco) |
| `PacienteHandlerTest`, `CadastroHandlerTest` | CRUD de pacientes, médicos e especialidades pela API |
| `CadastroMongoIntegrationTest` | Validadores e índices reais de médicos e especialidades (opcional) |
| `AgendaBloqueioIntegrationTest` | Agenda semanal e bloqueios: gravação, sobreposição, fuso |
| `ConsultaReservaIntegrationTest` | Índices de reserva: vaga única sob concorrência, mesmo paciente, consulta cancelada |
| `AgendamentoIntegrationTest` | Busca de vagas e agendamento: bloqueio, fora da jornada, passado, perfis |
| `ConsultaCalendarioIntegrationTest` | Leitura das consultas do calendário: período, fuso, nomes, validações |
| `ConflitosAgendaIntegrationTest` | Bloqueio e mudança de jornada sobre consultas marcadas: 409 com lista e confirmação |
| `SinalizacaoFilaIntegrationTest` | Cancelamento com o banco real: vaga liberada, histórico `CANCELAMENTO` + `SINALIZACAO_FILA`, só interessados compatíveis sinalizados (turno, especialidade, médico, validade, janela, sobreposição), um aviso por paciente, vaga no passado sem aviso; vaga sinalizada ocupada (`ATENDIDA` para quem agendou, `AGUARDANDO` para os demais) e sinalização vencida voltando a concorrer |
| `FilaEsperaIntegrationTest` | Fila pela API (#31): inscrição do paciente e pela secretaria, validações, inscrição duplicada (409), saída da fila, expiração e a vaga sinalizada vista pelo paciente |
| `FluxoEstadosHistoricoIndicadoresIntegrationTest` | Um dia de atendimento integrado (#48): agendamento pelo paciente e pela secretaria, confirmação, conclusão e falta, transições recusadas por estado e por perfil (sem evento no histórico), cancelamento sinalizando a fila e a vaga agendada pelo interessado, leitura do histórico com atores e motivo, e os cinco indicadores do dia com valores calculados à mão |
| `IndicadoresDemonstracaoIntegrationTest` | Indicadores conferidos com a carga de demonstração (#48): para a clínica e para cada médico, as contagens e taxas de `GET /indicadores` batem com contagens independentes nas coleções, e nenhuma taxa fica incompleta. Opt-in por `DEMO_MONGO_DB` |
| `CancelamentoIntegrationTest` | Cancelamento com o banco real: vaga liberada para a busca e para nova reserva, histórico com motivo e ator, consulta que já começou |
| `NotificacoesIntegrationTest` | Lembretes e notificações (#34) com o validador real: confirmação entregue ao agendar, lembrete programado 24 h antes e enviado uma vez só, lembrete criado pelo agendador para consulta sem lembrete, consulta em cima da hora, cancelamento avisando as outras partes e descartando o lembrete, lembrete de horário alterado descartado, cópia em e-mail de teste com `FALHA` após 3 tentativas, nenhuma mensagem com CPF, e-mail ou nome do paciente |
| `ConsultaLeituraIntegrationTest` | Listagem e detalhe de consultas por perfil (#30): filtros, nomes e `podeCancelar` |
| `CancelamentoConcorrenciaIntegrationTest` | Cancelamento sob concorrência (#40), com fila e notificações ligadas: três cancelamentos simultâneos da mesma consulta viram um só (um `CANCELAMENTO`, um aviso à fila, uma notificação por destinatário, lembrete descartado); confirmar e cancelar ao mesmo tempo termina num estado válido com histórico em sequência. 15 rodadas por cenário |
| `ReservasConcorrenciaIntegrationTest` | Pedidos simultâneos pela API (servidor com 16 threads): vaga única, mesmo paciente, vagas diferentes, sobreposição e cancelamento. Ver [garantias de reserva](garantias-de-reserva.md) |
| `HistoricoPersistenceIntegrationTest` | Gravação do histórico com o validador real (opcional) |

### Front-end (`frontend/src/__tests__`)

| Arquivos | Cobertura |
|---|---|
| `api.test.js` | Cliente HTTP: URL base, token, erros, timeout e sessão expirada |
| `Auth.test.jsx`, `App.test.jsx` | Rotas protegidas, login e retorno à página pedida, logout, menu por perfil, status da API |
| `Registration.test.jsx`, `UxPacientes.test.jsx`, `rascunhoPacientes.test.js` | Cadastros, validação por campo, rascunho preservado após erro |
| `Agenda.test.jsx`, `AgendaConflitos.test.jsx` | Jornada semanal, bloqueios e aviso de consultas afetadas |
| `Calendario.test.jsx` | Regras de exibição (livre, ocupado, remarcar, bloqueado, indisponível), navegação e visão do dia |
| `Consultas.test.jsx` | Listagem, detalhe e cancelamento de consultas por perfil |
| `NovaConsulta.test.jsx`, `FluxoAgendamento.test.jsx`, `FiltrosAgendamento.test.jsx`, `HorariosDisponiveis.test.jsx`, `ResumoAgendamento.test.jsx` e serviços de agendamento | Fluxo de agendamento: filtros, paciente, horários, resumo, confirmação e resultado incerto |

## Como executar

```bash
docker compose up -d mongo
bash scripts/recriar-banco.sh consultafacil_test     # validadores e índices, sem dados de exemplo

(cd backend && ./mvnw test)                         # unidade + integração
(cd frontend && npm ci && npm test && npm run lint && npm run build)
```

Os dois testes opcionais exigem a ativação explícita, porque usam o validador real do banco:

```bash
# banco exclusivo do teste de histórico (o recriar-banco.sh não aceita esse nome)
for f in database/init/*.js; do docker exec -i consultafacil-mongo mongosh consultafacil_historico_test --quiet --file /dev/stdin < "$f"; done

cd backend
HISTORICO_MONGO_URI="mongodb://localhost:27017/?serverSelectionTimeoutMS=3000" \
  ./mvnw test -DmongoTests=true
```

Conferência da carga de demonstração e dos indicadores calculados sobre ela (#48):

```bash
bash scripts/recriar-banco.sh consultafacil_demo
docker exec -i consultafacil-mongo mongosh consultafacil_demo --quiet --file /dev/stdin < scripts/verificar-carga.js
(cd backend && DEMO_MONGO_DB=consultafacil_demo ./mvnw test -Dtest=IndicadoresDemonstracaoIntegrationTest)
```

O verificador também exige que o histórico da carga use só transições aceitas pela API (por exemplo, toda consulta concluída passa por `CONFIRMACAO`). Sem isso, a taxa de confirmação do painel ficaria sem percentual.

## Acesso à agenda

A suíte HTTP de `ConflitosAgendaIntegrationTest` usa dois médicos vinculados a logins distintos e uma secretaria. Verifica leitura e gravação de jornada, calendário, criação e remoção de bloqueios e recusa de médico sem vínculo (#42).
