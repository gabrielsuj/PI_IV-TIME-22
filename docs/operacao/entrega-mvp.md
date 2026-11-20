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

## Validação local da integração (#45)

Em 20/11/2026, a validação local foi executada no commit `866c2fc0566fb3bdf574a90535267deb291e9649` da branch `integracao/prs-mvp`. A revisão reúne, a partir da `develop` em `0b5fb2a`, a correção do Java 21 para o `server/`, a agenda e os avisos da fila (#53), os estados de falha da interface (#41) e o workflow de recuperação (#45). A revisão de entrega será conferida após a integração do painel.

| Verificação | Resultado observado |
|---|---|
| Backend | 861 testes JUnit aprovados com MongoDB 7 descartável, incluindo os testes opcionais e a verificação da API de indicadores sobre a carga de demonstração. Zero falhas, erros ou testes ignorados. O empacotamento Maven com Java 21 gerou o JAR. |
| Frontend | 320 testes Vitest aprovados em 21 arquivos com Node 24. ESLint e build de produção concluídos com sucesso. |
| Módulo `server/` | `mvn clean verify` com Java 21 compilou 13 arquivos e gerou o JAR. O módulo não tem testes próprios. |
| Backup da demonstração | O verificador de coerência aprovou a carga. O backup restaurou dez coleções em um banco temporário, incluindo 834 consultas, 1.945 eventos de histórico e 150 pacientes. Contagens, hashes de conteúdo, índices e validadores conferiram nas dez coleções. |
| Teste do workflow de backup | O comando executado localmente aprovou os backups simultâneos e a rejeição de arquivo inválido ou de outra origem com o destino preservado. Após a falha parcial, o retorno automático recuperou dados, índice único e validador. A cópia preservada também permitiu o retorno manual quando a recuperação automática falhou. |

As contagens correspondem à carga gerada nessa execução. [Procedimento de backup e recuperação](backup-recuperacao.md) · [Workflow de backup](../../.github/workflows/backup-recuperacao.yml)

### Agenda e disponibilidade (#53)

O teste `deveImpedirReservaEmPeriodoBloqueado` e a regressão de mudança de duração com bloqueio parcial passaram. A busca e a confirmação usam a disponibilidade da agenda, e os testes de fila aprovados cobrem a expiração do aviso após bloqueio ou mudança de jornada. Os contratos e a modelagem estão documentados. [API de agenda e bloqueios](../api/alteracoes-agenda.md) · [API de agendamento](../api/agendamento.md) · [Modelo de agendas e bloqueios](../modelagem/medicos-agenda.md)

## Conferências antes da entrega

- [x] **Lembretes (#34).** A geração, o envio sem duplicidade em dois ciclos e o descarte no cancelamento passaram na suíte integrada. A confirmação pela central foi coberta com registro no histórico.
- [x] **API de indicadores (#36 e #48).** Os testes do serviço usado em `GET /indicadores` validaram as contagens e taxas da clínica e de cada médico com a carga de demonstração.
- [ ] **Painel web (#37 e #47).** Integrar a interface ao `GET /indicadores`. Comparar agenda do dia, próximas consultas e taxas com os dados do mesmo médico e período. Validar os filtros da secretaria e a visualização do médico.
- [ ] **Homologação ponta a ponta (#38).** Com o painel disponível, percorrer cadastro, agenda, disputa de vaga, cancelamento, fila, lembrete, histórico e painel pelos perfis previstos.
- [ ] **Evidência final da #45.** Na revisão com todas as entregas, repetir as suítes, lint, build, aceite e recuperação. Registrar o SHA, a data, os resultados e o link da CI correspondente.

## Versão final (#45)

1. Com todas as funcionalidades integradas no `develop`, confira que o workflow de CI está verde no último commit.
2. Execute a [suíte de testes](../testes/estrategia-de-testes.md), os [cenários de aceite](../testes/estrategia-de-testes.md#cenários-de-aceite-do-relatório-quadro-4) e os roteiros acima na revisão que será entregue: cadastro, agenda, agendamento, cancelamento, fila, lembretes, histórico e painel.
3. Gere o backup do banco de demonstração e confira dados, índices e validadores com o [procedimento de recuperação](backup-recuperacao.md). Guarde o backup fora do Git.
4. Abra o PR do `develop` para a `main`. Depois do merge, crie a tag no commit aprovado e publique a release associada a ela.
5. Nas notas da release, registre o SHA, as funcionalidades demonstradas, o resultado dos testes e da restauração e os links para o README, o relatório e este roteiro.
