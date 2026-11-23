<!--
Autoria: Gabriel Martins de Almeida
RA: 25006162
-->

# Documentação do ConsultaFácil

Índice de toda a documentação do projeto. Para instalar e executar, veja o [README principal](../README.md).

## Projeto (ideação, requisitos, planejamento)

| Documento | Conteúdo |
|---|---|
| [Apresentação (PDF)](ConsultaFacil_apresentação.pdf) | Slides do projeto: problema, perfis, tecnologias, comunicação entre os módulos e validação de CPF |
| [Relatório consolidado (PDF)](ConsultaFacil_Relatorio_Ideação.pdf) | Problema, ideação e classificação das ideias, SWOT, requisitos, escopo do MVP, cronograma por sprints (5W2H), matriz de responsabilidades e cenários de aceite (Quadro 4) |

O relatório consolidado registra o planejamento feito na ideação (outubro de 2026). A implementação evoluiu a partir dele: o banco tem 10 coleções (inclusive `usuarios`, `bloqueios` e `notificacoes`), dois índices únicos parciais protegem as reservas e a validação de CPF é feita por um servidor Java separado, via TCP. A modelagem atual está nos documentos abaixo.

## Modelagem do banco (MongoDB)

Os validadores (`$jsonSchema`) e índices ficam em [`database/init/`](../database/init); os documentos abaixo explicam cada coleção.

| Documento | Coleções |
|---|---|
| [usuarios-pacientes.md](dicionario/usuarios-pacientes.md) | `usuarios` (login, senha PBKDF2, perfil) e `pacientes` |
| [medicos-agenda.md](dicionario/medicos-agenda.md) | `especialidades`, `medicos`, `agendas` (jornada semanal e duração) e `bloqueios` |
| [consultas.md](dicionario/consultas.md) | `consultas`, status e índices únicos parciais que protegem as reservas |
| [historico.md](dicionario/historico.md) | `historico`: dicionário de dados, eventos e transições de status |
| [historico-implementacao.md](dicionario/historico-implementacao.md) | Como o back-end grava o histórico (eventos pendentes, versão, recuperação) |
| [filaEspera.md](dicionario/filaEspera.md) | `filaEspera` (fila de espera: inscrição pela API na #31, sinalização após cancelamento na #32) |
| [notificacoes.md](dicionario/notificacoes.md) | `notificacoes` (avisos de vaga, lembretes simulados e dados da central) |
| [carga-demonstracao.md](dicionario/carga-demonstracao.md) | Dados fictícios de demonstração (`database/seed/`) e o verificador de coerência |

## API REST

As rotas e os perfis autorizados estão em [Rotas.java](../backend/src/main/java/br/edu/puccampinas/consultafacil/api/Rotas.java) e são verificados em [RotasAutorizacaoTest.java](../backend/src/test/java/br/edu/puccampinas/consultafacil/integration/RotasAutorizacaoTest.java).

| Documento | Rotas |
|---|---|
| [autenticacao.md](api/autenticacao.md) | `GET /health`, `POST /auth/login`, `GET /auth/me` e `POST /auth/logout` |
| [pacientes.md](api/pacientes.md) | `GET/POST /pacientes` e `GET/PUT /pacientes/{id}`, com busca e permissões |
| [medicos-especialidades.md](api/medicos-especialidades.md) | `/medicos`, `/especialidades` (cadastro, edição, desativação) |
| [agendamento.md](api/agendamento.md) | `GET /vagas` (disponibilidade) e `POST /consultas` (agendamento) |
| [consultas.md](api/consultas.md) | `GET /consultas/lista` e `GET /consultas/{id}` (listagem e detalhe por perfil) e as rotas de alteração de status e histórico |
| [Calendário de consultas](api/consultas.md#7-get-consultas-calendário) | `GET /consultas` com período, escopo por médico e `fusoHorario` opcional |
| [cancelamento.md](api/cancelamento.md) | `POST /consultas/{id}/cancelamento` (cancelamento por paciente, médico ou secretaria) |
| [fila-espera.md](api/fila-espera.md) | `POST/GET /fila-espera` e `DELETE /fila-espera/{id}` e `GET /avisos-vaga` (leitura pelo paciente) |
| [Indicadores](api/indicadores.md) | `GET /indicadores` com período, escopo por perfil, contagens e taxas |
| [Central de notificações](api/notificacoes.md) | `GET /notificacoes`, `POST /notificacoes/lida` e `POST /notificacoes/confirmar` |
| [alteracoes-agenda.md](api/alteracoes-agenda.md) | `GET/PUT /agendas` e `GET/POST/DELETE /bloqueios`, incluindo confirmação quando a mudança atinge consultas marcadas |

Consulte a [estratégia de testes](testes/estrategia-de-testes.md) para localizar os testes de integração correspondentes a cada contrato.

## Operação

| Documento | Conteúdo |
|---|---|
| [configuracao.md](operacao/configuracao.md) | Processos e portas, todas as variáveis de ambiente (com o padrão do código), configuração dos testes, acesso e permissões, problemas comuns (#50) |
| [Entrega do MVP](operacao/entrega-mvp.md) | Demonstração por perfil, fila e lembretes, pendências de homologação e preparação da versão final |
| [backup-recuperacao.md](operacao/backup-recuperacao.md) | Backup, restauração e teste de recuperação dos bancos, com um comando cada |

## Testes

| Documento | Conteúdo |
|---|---|
| [estrategia-de-testes.md](testes/estrategia-de-testes.md) | Como os testes foram feitos, cobertura dos cenários de aceite (Quadro 4), bancos usados e comandos |
| [Relatório de teste OO do servidor de CPF (PDF)](testes/Relatorio_Teste_OO.pdf) | Teste intraclasse e interclasse em JUnit do servidor de CPF (PI IV), sobre o commit `8231632`. Documento histórico preservado; a requisição nula e o fim da entrada do console já foram tratados na branch atual |
| [garantias-de-reserva.md](testes/garantias-de-reserva.md) | Como as reservas são protegidas (API + índices únicos parciais) e os testes de concorrência pela API |
