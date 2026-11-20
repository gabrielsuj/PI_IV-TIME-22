# Entrega do ConsultaFácil

Roteiro para demonstrar o MVP e publicar a versão entregue (#45). O escopo e os cenários de aceite estão no [relatório do projeto](../ConsultaFacil_Relatorio_Consolidado.pdf) (Quadro 4). A situação de cada funcionalidade está no [README](../../README.md#situação-atual-20112026).

## Cadastro e agendamento por perfil

Use a carga e os logins de demonstração do [README](../../README.md#demonstração-com-dados-prontos).

1. Na secretaria, busque Carla em **Pacientes**, abra o detalhe pelo nome e volte à lista. Confira a busca preservada e o acesso à edição. Repita com o login da médica Ana e confira a tela de leitura.
2. Abra duas sessões independentes, uma de Paulo e outra da secretaria. Em **Nova consulta**, escolha Carla na sessão da secretaria. Nas duas sessões, escolha a mesma especialidade, médico, data futura e horário. Deixe os dois resumos abertos.
3. Confirme com Paulo e depois com a secretaria. Na secretaria, confira o aviso de conflito e os dados preenchidos, escolha outra vaga e confirme pelo novo resumo. Escolha um dia com pelo menos dois horários livres.

## Demonstração do cancelamento e da fila

A carga de demonstração (`database/seed/08-fila-notificacoes.js`) inscreve Carla e Diego na fila de uma consulta futura de outro paciente. As datas são relativas ao dia da carga.

1. Recrie o banco `consultafacil_demo` conforme o README e rode o verificador da carga. Copie o ID da linha "Consulta para a demonstração da fila".
2. Entre com a secretaria, abra `/consultas/<id>` e cancele essa consulta. No histórico da consulta aparecem o cancelamento e a sinalização da fila.
3. Abra sessões separadas de Carla e Diego (logins no README). Os dois veem a vaga em **Fila de espera** e em **Avisos de vaga**. Deixe a confirmação aberta nas duas sessões.
4. Confirme nas duas. Quem confirmar primeiro fica com a consulta. O outro recebe a mensagem de que a vaga foi ocupada; depois da atualização, o aviso dele aparece como **Expirado** e a inscrição volta para **Aguardando**, na mesma posição da fila.

O cenário cobre os aceites "dois usuários escolhem a mesma vaga" e "cancelamento libera a vaga, atualiza o histórico e sinaliza a fila".

## Demonstração da central de notificações (#35)

Ao recriar a carga, o seed 09 simula a entrega de um lembrete para uma consulta agendada de Paulo. Entre com o login de paciente de Paulo e abra **Notificações**. Marcar como lida reduz o contador. **Confirmar presença** também marca o lembrete como lido e registra a confirmação no histórico da consulta.

A central consome documentos do canal `SISTEMA` em estado `ENVIADA`, no formato de `database/init/09-esquema-notificacoes.js`. A geração e o envio periódico são da #34 (seção seguinte). A leitura usa `GET /notificacoes` e as ações usam `POST /notificacoes/lida` ou `POST /notificacoes/confirmar` com o campo `notificacaoId`. O servidor determina o destinatário pela sessão.

## Demonstração dos lembretes e notificações (#34)

1. Com a API no ar, entre como Paulo e agende uma consulta em **Nova consulta**. Em **Notificações** aparece "Consulta agendada" na hora.
2. Para ver o lembrete sem esperar 24 horas, agende uma consulta para as próximas 24 horas: o lembrete é entregue no próximo ciclo do agendador (até `NOTIFICACOES_INTERVALO_SEGUNDOS`, padrão 60 s). Use **Confirmar presença** nele.
3. Entre com a secretaria e cancele uma consulta de Paulo. Paulo e o médico recebem "Consulta cancelada"; o lembrete pendente dessa consulta é descartado.
4. Opcional: com `NOTIFICACOES_EMAIL_TESTE=true` no `.env`, reinicie a API e repita o passo 1. O terminal da API mostra `[e-mail de teste] para p***@...` com a mesma mensagem, sem CPF ou dado de saúde.

## Demonstração do painel operacional (#37)

1. Entre como secretaria e abra o painel com a carga de demonstração. Selecione um médico e confira a agenda do dia, as próximas consultas e os indicadores com os dados das consultas e de `GET /indicadores` para o mesmo recorte. Troque de médico e confira a atualização das três seções.
2. Entre com o login de um médico. Confira se o painel mostra seus próprios atendimentos e indicadores. Abra o detalhe de uma consulta pelo painel e volte para conferir se os filtros foram preservados.
3. No aceite da #38, confira também a mensagem da agenda ao selecionar um médico inativo, o efeito de consultas canceladas nas próximas consultas e o dia que o atalho abre no calendário quando o fuso da agenda difere do fuso do painel.

## Resultado do aceite manual (#38)

Em 20/11/2026, o aceite rodou no navegador e foi repetido após as três correções do painel, sobre a `develop` em `89f8fb0` com as alterações locais. O ambiente usou a API local, MongoDB 7 descartável e a carga fictícia de demonstração. Secretaria, médica e pacientes usaram sessões separadas.

| Cenário | Resultado observado |
|---|---|
| Cadastro e acesso por perfil | A secretaria criou e editou um paciente. O e-mail atualizado apareceu no detalhe e na lista, com a busca preservada ao voltar. A médica consultou o mesmo cadastro em modo de leitura. |
| Jornada, bloqueio e calendário | A terça-feira sem atendimento passou a oferecer horários após salvar a jornada. O bloqueio de 08h a 08h30 retirou só esse intervalo da busca e apareceu como **Bloqueado** no calendário. Um segundo bloqueio sobre a consulta das 09h exigiu confirmação da secretaria e deixou a consulta como **Remarcar**. |
| Disputa por horário | Paulo e a secretaria abriram o resumo das 08h30 antes da confirmação. Paulo reservou primeiro. A secretaria manteve paciente, médico e data, escolheu 09h e concluiu a segunda reserva. |
| Cancelamento, histórico e fila | A secretaria cancelou a consulta indicada pelo verificador da carga. O histórico mostrou o motivo e a sinalização. Carla e Diego receberam a mesma vaga. Carla agendou e sua inscrição ficou **Atendido**. Diego viu o aviso **Expirado** e voltou a **Aguardando**. |
| Lembretes e notificação de cancelamento | Paulo confirmou a presença na central e viu **Consulta confirmada** no histórico. Para uma reserva nova nas próximas 24 horas, chegou um único lembrete mesmo após novos ciclos do agendador. O cancelamento de outra reserva gerou aviso para Paulo e para a médica. |
| Painel por perfil | No recorte de Ana em 23/11, a agenda mostrou 11 consultas, o resumo mostrou cinco próximas e os indicadores conferiram com a API. A médica viu o mesmo recorte de seus atendimentos e voltou do detalhe com as datas preservadas. O paciente viu **Minha área**. A API recusou a consulta a outro médico pela sessão médica e a leitura de indicadores pelo paciente. |

### Revalidação dos três pontos do painel

- **Médica inativa.** Ao selecionar Helena Castro, o painel orientou a escolha de um médico ativo para ver a agenda do dia e desabilitou o atalho do calendário. Indicadores e próximas consultas mantiveram o filtro selecionado.
- **Canceladas nas próximas consultas.** A consulta cancelada permaneceu no histórico e na agenda do dia. O resumo exibiu cinco consultas ativas nos dois perfis, com o filtro aplicado na API antes da ordenação e do limite.
- **Atalho para o calendário com outro fuso.** Uma consulta fictícia preparada pela API apareceu no painel em 20/11 às 21h30 em São Paulo. O atalho abriu os dois dias locais que abrangem essa data e mostrou a mesma consulta em 21/11 às 00h30 UTC, sem precisar avançar o dia. O retorno preservou o médico e a data do painel.

A repetição local do aceite da #38 passou. A revisão final ainda precisa reunir as alterações de entrega e registrar a validação no commit escolhido.

## Validação local da integração (#45)

Em 20/11/2026, a validação local rodou sobre a `develop` em `89f8fb0ecf85c8071e4b0c21b681590bd7463f21`, com as correções locais da #38. O commit-base já inclui o painel do PR #117 e a resolução do PR #115. A revisão `866c2fc` registra a execução anterior à integração do painel.

| Verificação | Resultado observado |
|---|---|
| Backend | `./mvnw -DmongoTests=true clean verify` com Java 21 e MongoDB 7 descartável. O JAR foi gerado e 874 testes JUnit passaram, incluindo a regressão em que cem consultas canceladas precedem as consultas ativas. Zero falhas, erros ou testes ignorados. |
| Frontend | 481 testes Vitest passaram em 31 arquivos com Node 24, incluindo as regressões para perfil inativo, filtro de próximas consultas e navegação entre fusos. ESLint e build de produção passaram. |
| Módulo `server/` | `mvn clean verify` com Java 21 compilou 13 arquivos, aprovou o `ClienteTest` e gerou o JAR. |
| Backup da demonstração | O verificador de coerência aprovou a carga. O backup restaurou dez coleções em um banco temporário, incluindo 835 consultas, 1.949 eventos de histórico e 150 pacientes. Contagens, hashes de conteúdo, índices e validadores conferiram nas dez coleções. |
| Teste do workflow de backup | O comando executado localmente aprovou os backups simultâneos e a rejeição de arquivo inválido ou de outra origem com o destino preservado. Após a falha parcial, o retorno automático recuperou dados, índice único e validador. A cópia preservada também permitiu o retorno manual quando a recuperação automática falhou. |

As contagens correspondem à carga gerada nessa execução. Os links da CI são das revisões anteriores às correções locais da #38. [CI do PR #115](https://github.com/gabrielsuj/PI_IV-TIME-22/actions/runs/44795697693) · [Workflow de backup no PR #116](https://github.com/gabrielsuj/PI_IV-TIME-22/actions/runs/44788728570) · [Procedimento de backup e recuperação](backup-recuperacao.md)

### Agenda e disponibilidade (#53)

O teste `deveImpedirReservaEmPeriodoBloqueado` e a regressão de mudança de duração com bloqueio parcial passaram. A busca e a confirmação usam a disponibilidade da agenda, e os testes de fila aprovados cobrem a expiração do aviso após bloqueio ou mudança de jornada. Os contratos e a modelagem estão documentados. [API de agenda e bloqueios](../api/alteracoes-agenda.md) · [API de agendamento](../api/agendamento.md) · [Modelo de agendas e bloqueios](../modelagem/medicos-agenda.md)

## Conferências antes da entrega

- [x] **Lembretes (#34).** A geração, o envio sem duplicidade em dois ciclos e o descarte no cancelamento passaram na suíte integrada. A confirmação pela central foi coberta com registro no histórico.
- [x] **API de indicadores (#36 e #48).** Os testes do serviço usado em `GET /indicadores` validaram as contagens e taxas da clínica e de cada médico com a carga de demonstração.
- [x] **Painel operacional (#37).** O painel já está integrado ao `GET /indicadores` e aos serviços de agenda e consultas. Os testes automatizados cobrem filtros, navegação e estados da tela.
- [ ] **Refinamento do painel (#47).** Conferir a hierarquia visual, os filtros e a consistência com o protótipo e com as demais telas.
- [x] **Aceite manual local (#38).** Os fluxos operacionais e os três casos do painel passaram na repetição com as correções locais aplicadas sobre `89f8fb0`.
- [ ] **Evidência final da #45.** Na revisão com todas as entregas, repetir as suítes, lint, build, aceite e recuperação. Registrar o SHA, a data, os resultados e o link da CI correspondente.

## Versão final (#45)

1. Com todas as funcionalidades integradas no `develop`, confira que o workflow de CI está verde no último commit.
2. Execute a [suíte de testes](../testes/estrategia-de-testes.md), os [cenários de aceite](../testes/estrategia-de-testes.md#cenários-de-aceite-do-relatório-quadro-4) e os roteiros acima na revisão que será entregue: cadastro, agenda, agendamento, cancelamento, fila, lembretes, histórico e painel.
3. Gere o backup do banco de demonstração e confira dados, índices e validadores com o [procedimento de recuperação](backup-recuperacao.md). Guarde o backup fora do Git.
4. Abra o PR do `develop` para a `main`. Depois do merge, crie a tag no commit aprovado e publique a release associada a ela.
5. Nas notas da release, registre o SHA, as funcionalidades demonstradas, o resultado dos testes e da restauração e os links para o README, o relatório e este roteiro.
