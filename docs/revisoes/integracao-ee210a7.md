# Revisão após integração de branches

Revisão de 11/11/2026 na branch `feat/CRUD_Medicos`, HEAD `ee210a7`.
Comparação com a base anterior `f62d37d`, preservando as alterações locais da Parte 1.

## O que entrou e o que foi preservado

Foram revisadas as mudanças dos 21 arquivos integrados desde a base anterior,
as conexões entre frontend, API, repositórios, schemas e seeds, e a cobertura de
testes disponível sem MongoDB. Entraram o menu/acesso por perfil (#72), a porta
de gravação auditada de consultas e os scripts/dados de demonstração (#73).

Nenhum arquivo da implementação da Parte 1 foi sobrescrito pelos commits novos.
O contrato de médicos/especialidades, as validações, a desativação lógica e o
método `MedicoRepository.existeAtivo()` permanecem compatíveis. Os schemas de
médicos e especialidades não mudaram.

O menu restringe as telas de manutenção de médicos/especialidades à secretaria.
Isso é compatível com GET dos catálogos autorizado aos três perfis na API: o
médico precisa de GET /medicos para o seletor da agenda. A leitura da API não foi
restringida à secretaria para acompanhar a restrição da tela de manutenção.

O histórico novo usa IDs ObjectId e preserva referências a médico/especialidade.
Como a Parte 1 apenas desativa registros, não apaga nem troca IDs, não é necessário
alterar os serviços do histórico para integrar os cadastros. O campo
`SECRETARIA_ADMIN` já foi alinhado pelo código que entrou, preservando os valores
de atores antigos. A porta de escrita de consultas continua destinada à integração
dos futuros consumidores, conforme `docs/modelagem/historico-implementacao.md`;
não foi exposta como endpoint novo nem ligada aos cadastros-base.

## Correções feitas nesta revisão

| Arquivo | Problema e correção |
|---|---|
| `frontend/src/pages/RegistrationPage.jsx` | Médico podia abrir pacientes pelo novo menu, mas recebia botões Novo/Editar que geravam 403 na API. A manutenção agora exige SECRETARIA_ADMIN; médico consulta e filtra, sem coluna Ações nem modal de edição. A autorização do servidor permanece. |
| `frontend/src/pages/HomePage.jsx` | Os atalhos continuavam mostrando cadastros e status a perfis bloqueados pelo novo menu. A página inicial agora reutiliza `podeAbrir`; para médico a ação de pacientes se chama Consultar. |
| `frontend/src/__tests__/Auth.test.jsx` | O teste conferia o DOM imediatamente após mudar a rota. Passou a esperar o botão da conta com findByRole. Corrige a falha já reproduzida na base anterior; não altera o login de produção. |
| `frontend/src/__tests__/Registration.test.jsx` | Acrescentado cenário de consulta de pacientes pelo médico, sem ações de escrita. |
| `frontend/src/__tests__/App.test.jsx` | Acrescentados cenários de atalhos da página inicial para médico e paciente. |
| `frontend/src/__tests__/Agenda.test.jsx` | Acrescentado cenário de leitura do catálogo pelo médico na agenda, embora a tela de cadastro esteja bloqueada. |
| `README.md` | Removida orientação obsoleta de que não existia endpoint para listar médicos; documentado uso de GET /medicos e do seletor da agenda. |
| `docs/api/medicos-especialidades.md` | Acrescentada referência a esta revisão; resultados da primeira entrega foram mantidos como registro histórico. |

## Verificações

Comandos executados no módulo correspondente:

```text
backend: Maven 3.10.0 -B -o -Dmaven.repo.local=../../.tmp/maven-repository -Dtest=*Test,!*IntegrationTest test
backend: Maven 3.10.0 -B -Dmaven.repo.local=../../.tmp/maven-repository -Dorg.slf4j.simpleLogger.defaultLogLevel=error -Dtest=*Test,!*IntegrationTest clean test
frontend: npm test
frontend: npm run lint
frontend: npm run build
raiz: node --check para os 14 scripts JS em database/ e scripts/
raiz: parser do PowerShell para scripts/recriar-banco.ps1
raiz: git diff --check
```

O Maven utilizado é o mesmo do cache local descrito na entrega da Parte 1.
Compilação com alvo Java 21 e execução no JDK 26 instalado.
A primeira tentativa de `clean test` offline não encontrou o plugin de limpeza;
o plugin foi baixado e o comando completo passou na execução seguinte.

Resultados: 145 testes backend sem Mongo aprovados; 53 testes frontend aprovados;
lint, build e verificações de sintaxe aprovados. A falha anterior de Auth foi
corrigida no teste. Os 145 incluem as unidades de histórico e os testes HTTP reais
de sessão, autorização, pacientes e catálogos. A limpeza de build remove apenas
artefatos em target, sem alterar dados ou código.

## Limites e pendências existentes

- Integrações com MongoDB não foram exercitadas nesta rodada. Sintaxe dos scripts
  não comprova execução dos validadores/índices nem a transferência de eventos no
  banco real. Continuam pendentes os testes de persistência dos cadastros e do
  histórico nos respectivos bancos exclusivos de teste.
- O backend de agendas/bloqueios já tinha TODOs para limitar MEDICO aos próprios
  registros via `medicos.usuarioId`. O novo menu controla telas, não resolve esse
  escopo no servidor. Isso não foi introduzido pelo merge nem corrigido como parte
  da compatibilização do CRUD #13; exige completar a autorização desses módulos.
- O histórico depende da adoção de `RegistroConsultaService` pelos futuros
  serviços de consulta; `ConsultaRepository.inserir` continua disponível por
  compatibilidade e não produz auditoria sozinho. Não se declara o fluxo futuro
  de agendamento/cancelamento concluído por esta revisão.
- Não houve verificação visual em navegador nem execução no runtime Java 21.

Não foram executados scripts de recriação, drop de banco ou remoção de volumes.
Nenhuma mudança foi publicada no GitHub; não houve push, merge, PR ou alteração de issue.
