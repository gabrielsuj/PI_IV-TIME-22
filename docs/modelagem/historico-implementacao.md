# Registro de histórico no backend Java

## Escopo e integração

A infraestrutura e os serviços existentes foram preservados. Não há alterações em App.java,
Docker, conexão, portas, rotas, autenticação ou dependências. Nenhum processador é iniciado
em segundo plano. Os schemas precisam ser aplicados explicitamente ao banco de destino;
compilar Java não atualiza o banco.

A nova porta de escrita é RegistroConsultaService. Ela grava a consulta e um evento pendente
no mesmo documento, tenta transferir o evento para historico e tenta recuperar um lote de
outras consultas. HistoricoProcessor também pode ser chamado explicitamente para recuperação.

Exemplo de composição no futuro módulo de consultas (não executado automaticamente):

~~~java
var consultas = new ConsultaRepository(db);
var historico = new HistoricoRepository.Mongo(db);
var processor = new HistoricoProcessor(consultas, historico);
var registro = new RegistroConsultaService(consultas, processor, Clock.systemUTC());

// No futuro handler protegido por Autorizacao:
var ator = Autorizacao.usuario(exchange);
// Validar referências, disponibilidade e acesso ao paciente/médico antes desta chamada.
var consulta = registro.criar(dadosValidados, ator);
registro.alterar(consulta.getObjectId("_id"), new Document("status", "CONFIRMADA"), ator);
~~~

Reutilizar as mesmas instâncias permite avançar o cursor dos lotes entre chamadas.
O usuário deve vir da sessão, nunca do corpo JSON. RegistroConsultaService valida identidade
não nula, perfil, transições, tipos, intervalo e campos permitidos. Não consulta a coleção
usuarios para substituir a autenticação e não implementa regras de disponibilidade,
sobreposição, existência de referências ou propriedade do cadastro: são responsabilidades
dos futuros serviços/handlers. Ele não é um endpoint pronto para exposição.

O método legado ConsultaRepository.inserir(Document) foi preservado para os testes e
consumidores existentes. Ele NÃO gera histórico porque não recebe um responsável. Os
futuros fluxos de aplicação devem usar a nova porta; gravações diretas no banco ou nesse
método não são capturadas. Não há promessa de auditoria completa da aplicação antes dessa
integração. Não se cria usuário fictício nem se atribui uma operação humana a SISTEMA.

## Documento e atomicidade

- consultas.versao: Int32, opcional para documentos antigos; a nova criação inicia em 1.
- consultas.eventosPendentes: array opcional, máximo 50 itens.
- Cada evento tem ObjectId próprio, consultaId, sequência, tipo, status anterior/novo,
  ator {perfil, id}, ocorridoEm, pacienteId, medicoId, especialidadeId, dataHoraConsulta
  e fimConsulta. ALTERACAO inclui os campos alterados; CANCELAMENTO pode incluir motivo.
- ator.id referencia usuarios._id; ocorridoEm vem de Clock no servidor, em UTC.
- _id, consultaId e sequência são definidos antes da persistência, não durante o retry.

A inserção inicial já contém o evento. Nas alterações, o mesmo updateOne usa $set para os
dados e a versão e $push para o evento, filtrando por _id, versão esperada e espaço no array.
Nenhuma alteração de negócio é aplicada se o filtro não casar ou o banco recusar o documento.
Conflitos são retornados ao chamador; não se reaplica uma mudança sobre dados novos sem revisão.
Metadados internos não são aceitos nos parâmetros de criação/alteração e não são retornados
pelo serviço. Alterações de dados e de status devem ser operações separadas.

Eventos: CRIACAO, CONFIRMACAO, ALTERACAO, CANCELAMENTO, CONCLUSAO, FALTA.
Estados finais não são reabertos. Sem mudança efetiva não há evento novo.
SINALIZACAO_FILA não é produzida por este módulo, e sim pelo SinalizacaoFilaService (#32),
chamado pelo RegistroConsultaService logo após gravar um CANCELAMENTO. O evento usa a mesma
caixa de pendências (sequência seguinte à do cancelamento), com ator SISTEMA (id null) e
totalSinalizados. Uma falha nessa etapa fica em log e não desfaz o cancelamento.

## Recuperação e prevenção de duplicações

A transferência preserva o _id do evento. Uma chave duplicada somente é reconhecida como
sucesso se o documento já salvo for idêntico. Colisão de sequência com outro _id, ou conteúdo
divergente, mantém a pendência e gera log; não sobrescreve o passado. O índice único existente
{consultaId, sequencia} continua necessário, além do índice padrão de _id.

Após confirmar a inserção, $pull remove apenas a pendência com aquele identificador. Se o
processo cair antes disso, a repetição reconhece o histórico existente. A versão da consulta
não muda na remoção da pendência. Esse mecanismo evita duplicação de eventos na transferência;
não implementa uma chave de idempotência para repetir requisições de criação de consultas.

Uma falha ao transferir não transforma uma consulta já persistida em operação fracassada.
A alteração retorna normalmente e o evento fica durável na consulta. Isso é consistência
eventual: leitores que consultam apenas historico podem ver resultados incompletos enquanto
existem pendências. Não usar a caixa de pendências como resposta pública da API.

- Tentativa imediata para a consulta alterada.
- Um lote de até 5 consultas por chamada, com cursor por _id e reinício ao fim da varredura.
- Método recuperarLote(cursor, limite): de 1 a 100 consultas; retorna próximo cursor,
  consultas lidas, eventos reconhecidos e número de consultas com falha.
- Eventos de cada consulta são processados em ordem; a primeira falha interrompe somente
  aquela consulta. A paginação continua para as demais, evitando monopolizar o primeiro lote.
- Logs contêm identificadores e categoria da exceção, sem dados pessoais ou documento completo.

Recuperação manual pelo chamador Java: iniciar cursor null; chamar recuperarLote, passar
proximoCursor para o próximo lote e encerrar a passagem quando ele for null. Uma passagem
pode deixar falhas pendentes: inspecionar falhas/logs e tentar novamente após corrigir a causa.
Não há laço infinito de tentativas nem recuperação autônoma durante inatividade. Depois de um
reinício, novas chamadas retomam as pendências persistidas. Pendências de consultas finalizadas
também entram na varredura. O processamento síncrono acrescenta latência às operações;
o número de consultas por chamada é limitado, mas depende do tempo de resposta do banco.

## Limite de 50 pendências

É uma proteção inicial do MVP, não um valor medido em produção. Uma consulta costuma ter
criação, confirmação e um encerramento; 50 permite várias remarcações antes do bloqueio.
Como remarcações não têm limite natural, uma proteção explícita é necessária.

Cada evento tem no máximo 16 KiB BSON, verificados ANTES da gravação. Portanto, os eventos
pendentes ocupam até aproximadamente 800 KiB de conteúdo, além do overhead do array e dos
demais campos. Esse limite não limita o tamanho de campos legados arbitrários nem o total
acumulado de pendências de todas as consultas do banco.

Ao atingir 50, o serviço tenta transferir as pendências. Se permanecer cheio, devolve 503
antes de modificar a consulta. Se uma disputa concorrente impedir o update, devolve 409.
Nenhum evento é truncado, expirado por TTL ou descartado. Novas consultas podem continuar
sendo criadas: o teto é por consulta. Há uma troca explícita entre disponibilidade e
preservação da auditoria; durante falha prolongada, a consulta cheia fica temporariamente
sem aceitar novas alterações. Esse comportamento também se aplica a cancelamentos e faltas.

O schema e a constante LIMITE_PENDENCIAS devem ser atualizados juntos se o time revisar o
limite. Lotes limitados e recuperação recorrente ajudam a reduzir o acúmulo; não substituem
acompanhar erros persistentes e pendências antigas.

## Compatibilidade de schemas e dados antigos

- Campos de controle novos são opcionais no schema. Ausência de versão equivale a zero.
- Em documentos sem versão, consulta-se a última sequência já existente em historico antes
  de gerar o novo evento. A atualização condicionada inicializa a versão sem inventar uma
  CRIACAO antiga. O primeiro evento pode ser CONFIRMACAO ou outra alteração real.
- especialidadeId e fimConsulta são opcionais no histórico para preservar documentos antigos;
  novos eventos Java sempre os preenchem.
- SECRETARIA_ADMIN foi adicionado aos atores e transições, preservando SECRETARIA e
  ADMINISTRADOR para documentos históricos. Não se altera o enum Perfil da autenticação.
- Valores de alteracoes aceitam null para representar ausência anterior de campo.
- Os índices existentes de reservas e histórico permanecem. Não há remoção de coleções ou
  atualização massiva de registros. Dados legados com tipos inválidos não são corrigidos
  silenciosamente.

O schema não é um gatilho: ele não obriga gravações manuais a gerar auditoria. A garantia
se aplica aos caminhos que usam RegistroConsultaService com todas as operações integradas.

## Indicadores e dependências

O histórico fornece eventos por ocorridoEm e consultas por dataHoraConsulta, com intervalo
e especialidade preservados. Não confundir quantidade de eventos de confirmação com quantidade
de consultas atualmente confirmadas. Ocupação depende também de jornadas e bloqueios;
ocupação histórica exata exige preservar a capacidade da agenda de cada período.
Fila ativa e vagas preenchidas dependem de filaEspera e seu futuro serviço. Não foram criados
endpoints, painel, fórmulas de taxas ou dados de fila artificiais nesta implementação.

## Testes

HistoricoTest: unidade, com armazenamento simulado, sem MongoDB. Verifica identidade do ator,
fluxos, no-op, documentos antigos, falhas antes/depois da gravação, limite e recuperação.

~~~bash
cd backend
./mvnw test -Dtest=HistoricoTest
~~~

HistoricoPersistenceIntegrationTest: opt-in via HISTORICO_MONGO_URI. Usa SOMENTE o banco
consultafacil_historico_test. Antes de executar, aplicar todos os scripts database/init em
ordem nesse banco isolado (especialmente 03 e 10). O teste não inicializa servidor nem altera
Docker; remove apenas as consultas criadas por ele e seus eventos. A recuperação pode
processar pendências anteriores desse banco de teste, por isso ele deve ser dedicado.

~~~powershell
$env:HISTORICO_MONGO_URI = 'mongodb://localhost:27017/?serverSelectionTimeoutMS=3000'
cd backend
.\mvnw.cmd test -Dtest=HistoricoPersistenceIntegrationTest
~~~

Sem a variável, os testes de integração são ignorados. Com a variável e banco indisponível
ou schemas ausentes/incorretos, devem falhar, não apresentar aprovação falsa.
