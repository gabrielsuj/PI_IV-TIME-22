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

A central consome documentos do canal `SISTEMA` em estado `ENVIADA`, no formato de `database/init/09-esquema-notificacoes.js`. A geração e o envio periódico são da #34 (seção seguinte). A leitura usa `GET /notificacoes` e as ações usam `POST /notificacoes/lida` ou `POST /notificacoes/confirmar` com o campo `notificacaoId`. O servidor determina o destinatário pela sessão.

## Demonstração dos lembretes e notificações (#34)

1. Com a API no ar, entre como Paulo e agende uma consulta em **Nova consulta**. Em **Notificações** aparece "Consulta agendada" na hora.
2. Para ver o lembrete sem esperar 24 horas, agende uma consulta para as próximas 24 horas: o lembrete é entregue no próximo ciclo do agendador (até `NOTIFICACOES_INTERVALO_SEGUNDOS`, padrão 60 s). Use **Confirmar presença** nele.
3. Entre com a secretaria e cancele uma consulta de Paulo. Paulo e o médico recebem "Consulta cancelada"; o lembrete pendente dessa consulta é descartado.
4. Opcional: com `NOTIFICACOES_EMAIL_TESTE=true` no `.env`, reinicie a API e repita o passo 1. O terminal da API mostra `[e-mail de teste] para p***@...` com a mesma mensagem, sem CPF ou dado de saúde.

## Versão final (#45)

1. Com todas as funcionalidades integradas no `develop`, confira que o workflow de CI está verde no último commit.
2. Execute a [suíte de testes](../testes/estrategia-de-testes.md), os [cenários de aceite](../testes/estrategia-de-testes.md#cenários-de-aceite-do-relatório-quadro-4) e os roteiros acima na revisão que será entregue: cadastro, agenda, agendamento, cancelamento, fila, lembretes, histórico e painel.
3. Gere o backup do banco de demonstração e confira dados, índices e validadores com o [procedimento de recuperação](backup-recuperacao.md). Guarde o backup fora do Git.
4. Abra o PR do `develop` para a `main`. Depois do merge, crie a tag no commit aprovado e publique a release associada a ela.
5. Nas notas da release, registre o SHA, as funcionalidades demonstradas, o resultado dos testes e da restauração e os links para o README, o relatório e este roteiro.
