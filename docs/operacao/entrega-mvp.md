# Entrega do ConsultaFácil

Roteiro para demonstrar o MVP e publicar a versão entregue (#45). O escopo e os cenários de aceite estão no [relatório do projeto](../ConsultaFacil_Relatorio_Consolidado.pdf) (Quadro 4); a situação de cada funcionalidade está no [README](../../README.md#situação-atual-19112026).

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

A central consome documentos do canal `SISTEMA` em estado `ENVIADA`, no formato de `database/init/09-esquema-notificacoes.js`. A geração e o envio periódico são da #34. A leitura usa `GET /notificacoes` e as ações usam `POST /notificacoes/lida` ou `POST /notificacoes/confirmar` com o campo `notificacaoId`. O servidor determina o destinatário pela sessão.

## Validação local da integração (#45)

Em 19/11/2026, a revisão testada na branch `integracao/mvp-45` foi `0e1ab172a609505fe99b3e0f0d3a8c5243909943`.

| Verificação | Resultado observado nessa revisão |
|---|---|
| Backend | 523 testes JUnit passaram com MongoDB 7 descartável, incluindo as suítes opcionais. Zero falhas, erros ou testes ignorados. |
| Frontend | 307 testes Vitest passaram em 21 arquivos. ESLint e build de produção concluídos com sucesso. |
| Backup da demonstração | O verificador de coerência aprovou a carga. O backup de 76 KiB restaurou as dez coleções em um banco temporário, incluindo 857 consultas, 1.946 eventos de histórico e 150 pacientes. Contagens, hashes de conteúdo, índices e validadores conferiram nas dez coleções. |
| Concorrência e falhas na restauração | Dois backups do mesmo banco no mesmo segundo produziram arquivos distintos, ambos restauráveis. Um arquivo inválido e um arquivo de outra origem foram recusados com o destino preservado. Após uma falha de escrita parcial, o retorno automático recuperou o registro, o índice único e o validador anteriores. Quando o retorno automático também falhou, a cópia preservada permitiu a recuperação manual. |

As quantidades acima identificam a carga utilizada nessa execução. Para repetir a comparação, use a origem e a cópia restaurada da mesma carga. [Procedimento de backup e recuperação](backup-recuperacao.md)

## Conferências após a integração dos lembretes e do painel

- [ ] **Lembretes (#34).** Agende uma consulta dentro da janela configurada e acompanhe dois ciclos do agendador. Confira que o paciente recebe um único lembrete na central. Confirme a presença por ele e verifique o estado da consulta, a leitura do aviso e um único evento de confirmação no histórico.
- [ ] **Indicadores e painel (#36 e #37).** Escolha um médico e um período da carga de demonstração. Compare a agenda do dia, as próximas consultas, a ocupação e as taxas de falta, cancelamento, confirmação e preenchimento pela fila com os dados desse mesmo recorte. Valide os filtros com a secretaria e o acesso do médico à própria agenda.
- [ ] **Evidência final da #45.** No commit que reunir as entregas, repita as suítes, o lint, o build, os cenários de aceite e a recuperação do backup. Registre o novo SHA, a data, os resultados e o link da CI correspondente quando disponível.

## Versão final (#45)

1. Com todas as funcionalidades integradas no `develop`, confira que o workflow de CI está verde no último commit.
2. Execute a [suíte de testes](../testes/estrategia-de-testes.md), os [cenários de aceite](../testes/estrategia-de-testes.md#cenários-de-aceite-do-relatório-quadro-4) e os roteiros acima na revisão que será entregue: cadastro, agenda, agendamento, cancelamento, fila, lembretes, histórico e painel.
3. Gere o backup do banco de demonstração e confira dados, índices e validadores com o [procedimento de recuperação](backup-recuperacao.md). Guarde o backup fora do Git.
4. Abra o PR do `develop` para a `main`. Depois do merge, crie a tag no commit aprovado e publique a release associada a ela.
5. Nas notas da release, registre o SHA, as funcionalidades demonstradas, o resultado dos testes e da restauração e os links para o README, o relatório e este roteiro.
