# Parte 1: UX de acesso e pacientes (#49)

Implementação de 11/11/2026 sobre `develop`, commit `4153e4d`, conforme a Parte 1
do PDF atualizado após os PRs #72, #73 e #74. Nesta versão, Parte 1 é a #49;
o CRUD #13 já está integrado e não foi reimplementado. A issue #49 foi consultada
somente para leitura. A árvore estava limpa antes desta implementação.

## Comportamento entregue

- Validação de pacientes por campo, com as mesmas regras de formato, dígitos do
  CPF e e-mail de `PacienteValidator`. Nome obrigatório; CPF aceita 11 dígitos ou
  pontuação completa, rejeita repetidos/dígitos verificadores inválidos; e-mail
  respeita os limites de 64 caracteres na parte local e 254 no total e recusa
  pontos inválidos na parte local. CPF segue como texto, inclusive com zero à
  esquerda. O envio normaliza espaços nas extremidades, pontuação do CPF e caixa
  do e-mail como o backend.
- Mensagens específicas ligadas aos campos por `Field`, preservando label,
  aria-invalid e aria-describedby. O foco vai ao primeiro erro na ordem visual.
  Erros globais da API permanecem no alerta do formulário, que recebe foco, sem
  supor que um erro genérico pertence a um campo.
- Foco inicial no primeiro campo do modal e retorno ao botão de abertura ao
  fechar. No modal recuperado após login, o botão Novo paciente serve de retorno
  caso o elemento anteriormente focado não exista mais. Escape continua usando o
  evento cancel do dialog nativo. Cancelamento permanece bloqueado durante envio.
- Trava síncrona de envio em login e cadastro, além do estado loading e botões
  desabilitados, evitando duas requisições antes da próxima renderização.
- Valores e modal preservados em 400, 409, 403, 500 e falha de rede. A mensagem
  de sucesso existente fecha o modal e atualiza a lista. Login inválido mantém o
  e-mail preenchido e mostra o erro da API; não há persistência de senha.
- Ajustes de CSS para quebra de mensagens, campos que podem encolher e botões
  empilhados com altura mínima de 44px em telas até 480px, usando os tokens atuais.

## Rascunho após expiração

`rascunhoPacientes.js` guarda apenas nome, CPF, e-mail e o ID do paciente em edição,
quando houver. O conteúdo fica em memória do módulo nesta aba, associado ao ID
do usuário e à rota com query string. Não usa localStorage/sessionStorage, não
recebe credenciais e não registra o conteúdo em logs. Recarregar/fechar a aba
descarta o rascunho.

Enquanto a sessão é válida, alterações atualizam o rascunho sem restaurá-lo
automaticamente. O evento de expiração já existente suspende o acesso e libera
a recuperação somente após login bem-sucedido do mesmo usuário. Vários 401
simultâneos não descartam o rascunho. A recuperação reabre o modal de criação ou
edição com aviso explícito para revisar e selecionar Salvar. Não envia nada
automaticamente.

Login bem-sucedido de outro usuário elimina o rascunho anterior, mesmo se o
usuário original entrar depois. Logout limpa imediatamente e ao concluir; dados
de callbacks atrasados não são aceitos após logout. Cancelar/fechar ou salvar com
sucesso limpa o rascunho daquela rota.

O fluxo de sessão, o cliente HTTP e seus eventos/redirecionamentos foram
reaproveitados. As permissões do menu e canEdit foram preservadas: médico segue
consultando pacientes sem Novo/Editar; secretaria mantém os formulários.

## Arquivos alterados

| Arquivo em frontend/src | Finalidade |
|---|---|
| `utils/paciente.js` (novo) | Validação e normalização compatíveis com o backend |
| `services/rascunhoPacientes.js` (novo) | Rascunho em memória, isolamento e ciclo de vida |
| `contexts/AuthProvider.jsx` | Integrar suspensão/recuperação/limpeza ao ciclo de sessão existente |
| `pages/RegistrationPage.jsx` | Reabrir rascunho após reautenticação, limpar ao cancelar/salvar e foco de retorno |
| `components/RegistrationForm.jsx` | Validação, captura do rascunho, aviso de recuperação, foco no erro e proteção contra envio duplo |
| `pages/LoginPage.jsx` | Foco em erro de API e proteção contra envio duplo |
| `components/ui/Alert.jsx` | Aceitar ref/tabIndex para foco acessível nos alertas de formulário |
| `components/ui/Modal.jsx` | Foco inicial e retorno seguro de foco |
| `data/registrationSchemas.js` | Hints, limites e teclado numérico no CPF dos pacientes |
| `styles/global.css` | Quebra de mensagens, campos flexíveis e botões para telas pequenas |
| `__tests__/UxPacientes.test.jsx` (novo) | Erros, teclado, normalização, submissão, expiração, edição e troca de conta |
| `__tests__/rascunhoPacientes.test.js` (novo) | Isolamento de usuário/rota, 401 simultâneo, logout, campos permitidos e cópias |
| `__tests__/Registration.test.jsx` | Atualizar a expectativa da mensagem específica de nome obrigatório |
| `__tests__/setup.js` | Limpar rascunhos entre testes |

Este documento registra o escopo e os resultados. Não houve alteração de backend,
schemas, infraestrutura, navegação por perfil ou funcionalidades das outras partes.

## Verificações executadas

Na pasta frontend:

```text
npm.cmd test
npm.cmd run lint
npm.cmd run build
```

Resultado final: **72 testes aprovados em 7 arquivos**, lint sem erros/avisos e
build aprovado. `git diff --check` também passou. Não houve nova execução de
testes Java nesta etapa, pois o backend não foi modificado.

Os cenários novos incluem erros 400/409/403/500/rede sem perda de preenchimento,
erro global sem marcação indevida de campo, CPF inválido e com zero inicial,
normalização de e-mail, foco inicial e do primeiro erro, Tab/Shift+Tab, envio por
Enter, submissões repetidas, cancel durante loading, retorno de foco e limpeza,
recuperação de criação/edição após 401 com o mesmo usuário, ausência de envio
automático, descarte ao trocar de conta, logout e 401 simultâneos. Os testes
existentes continuam cobrindo as permissões de médico/secretaria e o retorno após
login. As primeiras falhas nos testes novos foram consultas de CPF ambíguas entre
a busca e o modal; os seletores foram limitados ao dialog e a suíte final passou.

## Pendências e situação

**Implementação concluída; verificação visual pendente.** A ferramenta de
navegação retornou inventário vazio e `Browser is not available: iab`. Portanto
não foram verificadas visualmente as larguras 320, 768 e 1280px, a rolagem do modal
nem a contenção nativa do foco em navegador real. Testes jsdom comprovam os fluxos
e os eventos/foco explicitamente cobertos, mas não comprovam layout CSS nem o
comportamento nativo completo de Escape/Tab.

Também falta exercitar o fluxo com a API real. Os testes de interface usam
respostas HTTP simuladas e a infraestrutura real de sessão/rotas do React; não
alegam persistência em MongoDB.

Não há contrato externo faltando para implementar a #49. A conclusão
administrativa da #14 e a integração #50 não foram assumidas como bloqueios ou
implementadas nesta etapa. As pendências acima são de verificação do ambiente.

Sem push, merge, publicação de PR, comentários ou alterações em issues.
