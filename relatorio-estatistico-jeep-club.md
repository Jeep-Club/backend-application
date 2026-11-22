# Relatório estatístico do projeto Jeep Club

**Data do levantamento:** 21 de novembro de 2026

O Jeep Club reúne um portal web para o público, associados e administradores e uma API que concentra as regras de negócio, a autorização e a persistência. O recorte de desenvolvimento integrado do backend e a branch principal do frontend somam **2.867 arquivos versionados**, **94.820 linhas não vazias de código de aplicação** e **1.632 commits alcançáveis** nos dois históricos. O portal tem **47 arquivos de página**, incluindo **23 administrativos**. A API tem **320 declarações de operações HTTP**, **80 classes REST** e **16 migrations Flyway** em `develop`.

## 1. Recorte e referências

| Repositório | Referência local analisada | Commit | Data do commit |
|---|---|---|---|
| Backend, branch principal | `origin/master` | `aa5e328f5a2b7316d4426b379e2299f4c88f2f8d` | 17/11/2026 |
| Backend, desenvolvimento integrado | `origin/develop` | `b9ba50aa56d5043892e41255c97fa630d8816222` | 20/11/2026 |
| Backend, trabalho na branch 461 | `HEAD` / `origin/461` | `a69453fb6ae400d5db4f9c4be82a3374c4fa4302` | 21/11/2026 |
| Frontend, branch principal | `upstream/main` | `0080f504216bdba55a3e14f1f96d8a1c06228d73` | 13/11/2026 |
| Frontend, desenvolvimento integrado | `upstream/develop` | `b9f9a5e97a3ced2b5572ec393bfc832670bb952a` | 13/11/2026 |

O frontend tem a mesma árvore de arquivos em `main` e `develop`, embora os históricos sejam diferentes. O clone local do backend rastreia um fork em `origin`, com merges de PRs da organização no histórico. A consulta aos remotos da organização no GitHub falhou por falta de conexão neste ambiente. As contagens descrevem os commits acima, com situação do remoto atual e de produção sem confirmação. O recorte exclui outras branches laterais, incluindo trabalhos ainda em andamento que possam existir fora dessas referências.

## 2. Escala e histórico

| Indicador | Backend `develop` | Frontend `main` | Total dos repositórios |
|---|---:|---:|---:|
| Arquivos versionados | 1.843 | 1.024 | 2.867 |
| Arquivos Java ou TypeScript de aplicação | 1.328 | 682 | 2.010 |
| Linhas não vazias nesses arquivos | 54.491 | 40.329 | 94.820 |
| Arquivos Markdown versionados | 102 | 125 | 227 |
| Commits alcançáveis, incluindo merges | 934 | 698 | 1.632 |
| Commits sem merge | 747 | 484 | 1.231 |
| Identidades distintas de autoria por e-mail Git | 16 | 17 | — |
| Data do primeiro commit no histórico | 23/03/2026 | 07/04/2026 | — |

Usando as duas branches principais disponíveis, o total é de **2.865 arquivos versionados**, **94.785 linhas não vazias de aplicação** e **1.629 commits**. Os e-mails de autoria são contados por repositório. Uma pessoa pode ter contribuído nos dois ou ter usado mais de uma identidade.

### Commits por mês

| Mês de 2026 | Backend `develop` | Frontend `main` | Total |
|---|---:|---:|---:|
| Março | 4 | 0 | 4 |
| Abril | 58 | 29 | 87 |
| Maio | 201 | 46 | 247 |
| Junho | 85 | 30 | 115 |
| Julho | 4 | 27 | 31 |
| Agosto | 63 | 165 | 228 |
| Setembro | 207 | 20 | 227 |
| Outubro | 181 | 248 | 429 |
| Novembro, até os commits de referência | 131 | 133 | 264 |
| **Total** | **934** | **698** | **1.632** |

Outubro teve o maior volume combinado, com **429 commits**, cerca de **26%** do histórico somado. O pico mensal do backend foi em setembro, com **207 commits**. O do frontend foi em outubro, com **248**. Os valores usam a data do committer e incluem merges.

## 3. Frontend

| Indicador | Quantidade |
|---|---:|
| Arquivos de página do Next.js | 47 |
| Páginas na área autenticada do dashboard | 33 |
| Páginas administrativas, dentro do dashboard | 23 |
| Páginas fora do dashboard | 14 |
| Arquivos de handlers HTTP do Next.js | 8 |
| Arquivos TSX sob `src/components` | 265 |
| Arquivos de stories no Storybook | 30 |

Os 47 arquivos de página incluem uma rota interceptada de modal de usuário. A contagem mede arquivos `page.tsx`, pois uma tela pode aparecer por mais de uma forma de navegação. Os oito handlers do Next.js incluem três rotas de mock de autenticação. Entre os demais estão logout, acesso a imagens, fotos de dependentes e comprovantes de pagamento.

| Área | O que está representado no código |
|---|---|
| Público e primeiro acesso | Página inicial, equipe, contato, documentos legais, cadastro, adesão, login, ativação e recuperação de senha. |
| Portal do associado | Feed, agenda, eventos, avisos, serviços, perfil, financeiro, notificações, configurações e tutoriais. |
| Administração | Usuários e permissões, dependentes, dados médicos, veículos, ferramentas, gestão de publicações, cobranças, pagamentos e movimentações financeiras. |
| Interface e integração | Formulários com validação, tabelas e filtros, galerias, exportações, recibos e catálogo de componentes no Storybook. A integração com a API passa por funções e ações de servidor. |

**Stack declarada no commit:** Next.js 16.4.3 com App Router, React 19.3.1, TypeScript, Tailwind CSS, TanStack Query/Table, React Hook Form e Zod.

## 4. Backend

Os módulos de negócio cobrem dez áreas quando os três submódulos de IAM são contados individualmente. A plataforma fornece capacidades transversais, incluindo armazenamento, mídia, documentação da API e observabilidade. A distribuição abaixo conta arquivos com `@RestController` e declarações dos cinco verbos HTTP mapeados no projeto.

| Módulo ou submódulo | Classes REST | Operações declaradas | Capacidades principais |
|---|---:|---:|---|
| Publicações | 13 | 77 | Eventos e participantes, avisos, serviços, feed, interações e relatórios. |
| Financeiro | 12 | 52 | Definições e ciclos de cobrança, atribuições, pagamentos, estornos, comprovantes e exportações. |
| IAM · Autenticação | 11 | 30 | Sessões, renovação de credenciais, ativação e recuperação de senha. |
| Associação | 10 | 28 | Solicitações, aprovação, rejeição, bloqueios, cadastro de associados, importação e configuração financeira. |
| Dependentes | 6 | 28 | Cadastro, consulta administrativa, fotos privadas, histórico e exportação. |
| IAM · Autorização | 9 | 24 | Papéis, permissões, vínculos de usuário e exportações de acesso. |
| Ferramentas | 4 | 22 | Gestão de ferramentas, consultas, histórico e exportações. |
| Veículos | 4 | 21 | Cadastro, galeria de fotos, gestão administrativa, histórico e exportações. |
| IAM · Identidade | 7 | 20 | Cadastro, perfil, gestão e busca de usuários e aniversariantes. |
| Saúde | 3 | 15 | Perfis médicos, consulta administrativa e exportações autorizadas. |
| Plataforma | 1 | 3 | Operações REST transversais de mídia e documentação da API. |
| **Total** | **80** | **320** | |

Por verbo HTTP, são **145 GET**, **73 POST**, **58 PATCH**, **24 PUT** e **20 DELETE**. Publicações e Financeiro concentram **129 das 320 declarações**, cerca de **40%**. As três declarações da plataforma incluem a interface de documentação. A classe que a serve usa o estereótipo `@Controller` e fica fora da coluna de classes REST.

**Stack e infraestrutura:** Java 17, Spring Boot 4.0.4, MySQL 8, Flyway, OpenAPI, Docker e GitHub Actions. A organização separa interfaces HTTP, contratos entre módulos, domínio, aplicação e infraestrutura. O workflow versionado define os jobs `verify`, `mysql-migrations` e `deploy`.

### Diferenças entre branches do backend

| Indicador | `origin/master` | `origin/develop` | `461` |
|---|---:|---:|---:|
| Arquivos versionados | 1.841 | 1.843 | 1.847 |
| Arquivos Java de aplicação | 1.328 | 1.328 | 1.329 |
| Linhas não vazias de aplicação Java | 54.456 | 54.491 | 54.627 |
| Operações HTTP declaradas | 320 | 320 | 320 |
| Migrations Flyway versionadas | 15 | 16 | 17 |
| Arquivos Java de teste e apoio | 296 | 296 | 297 |
| Anotações JUnit de teste | 1.809 | 1.812 | 1.824 |

- **`develop` em relação a `master`.** Há quatro commits exclusivos de `develop` e um exclusivo de `master`. A diferença entre as árvores abrange 27 arquivos. Inclui a configuração de local e limite de participantes em eventos (BACK-723, migration V16) e a atualização da documentação e dos exemplos de configuração de desenvolvimento (BACK-705).
- **`461` em relação a `develop`.** Há três commits exclusivos da branch, incluindo um merge que a atualiza com `develop`. A diferença abrange 30 arquivos e inclui reforços na integridade operacional de eventos e histórico de capacidade de veículos associados, com a migration V17 (BACK-461).

## 5. Testes e validação

| Indicador observado no código | Quantidade | Recorte |
|---|---:|---|
| Arquivos Java de teste e apoio | 296 | Backend `develop`, em `src/test/java` |
| Arquivos com sufixo `Test.java` fora do grupo `IntegrationTest.java` | 195 | Backend `develop` |
| Arquivos com sufixo `IntegrationTest.java` | 68 | Backend `develop` |
| Arquivos com sufixo `IT.java` | 25 | Backend `develop` |
| Outros arquivos Java de apoio | 8 | Backend `develop` |
| Anotações JUnit de teste | 1.812 | Backend `develop`, em 39.476 linhas não vazias de Java de teste e apoio |
| Arquivos de teste automatizado | 110 | Frontend `main`, incluindo os três grupos abaixo |
| Arquivos de teste do Jest | 77 | Frontend, fora de `tests/browser` |
| Arquivos de teste de navegador do Playwright | 31 | Frontend, incluindo 10 de acessibilidade |
| Arquivos E2E de contrato em JavaScript | 2 | Frontend, em `tests/e2e` |

O backend versiona testes de regra de negócio, HTTP, contratos OpenAPI e cenários de persistência e migration com MySQL. O frontend versiona testes de regras e componentes no Jest, testes de navegador no Playwright e auditorias de acessibilidade com axe. Os números acima descrevem o inventário estático. Esta rodada foi documental e não executou builds, testes, migrations ou verificações de cobertura. Também não aferiu status de CI, implantações, quantidade de usuários ou volume de uso.

## 6. Metodologia e fontes

- **Arquivos e tamanho.** Inventário com `git ls-tree -r --name-only <ref>`. Linhas não vazias com `git grep -I -c -E '[^[:space:]]' <ref>`, restrito a Java sob `src/main/java` e a TS/TSX sob `src` no frontend. A contagem inclui comentários. SQL, CSS, documentação, arquivos de dependências e builds ficam fora da métrica de linhas de aplicação.
- **Histórico.** `git rev-list --count` e `git rev-list --count --no-merges` por referência. Autoria por `git log --format=%aE` com deduplicação dentro de cada repositório. Série mensal por `git log --format=%cs`. Commits medem atividade registrada, enquanto as diferenças entre branches foram medidas nas árvores de arquivos e em seus históricos exclusivos.
- **Contratos e persistência.** Busca em Java por `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping` e `@RestController`. Cada declaração de verbo vale uma operação na contagem, inclusive quando uma declaração puder apontar para mais de uma URL. Migrations pela presença de arquivos SQL versionados com prefixo `V` em `src/main/resources/db/migration`.
- **Páginas e testes.** Frontend por arquivos `page.tsx`, `route.ts`, `*.stories.*` e specs nos diretórios indicados por `jest.config.ts`, `playwright.config.ts` e `package.json`. Backend por arquivos Java sob `src/test/java` e ocorrências de `@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory` e `@TestTemplate`. Os números medem arquivos e declarações. A quantidade de casos em execução depende da descoberta do runner e das parametrizações.
- **Funcionalidades.** Frontend verificado por rotas em `src/app`, código associado e documentação em `docs/`. Backend verificado pelos controllers, documentação dos módulos, `pom.xml`, migrations e `.github/workflows/backend-ci.yml` nos commits indicados. As categorias descrevem funcionalidades representadas no código e não equivalem a um inventário de tickets aceitos.

## 7. Inventário funcional detalhado

### Frontend

| Área | Implementação encontrada no commit analisado |
|---|---|
| Site público | Página inicial, equipe, contato, política de privacidade, termos de uso, imagem e risco. O cadastro inclui aceite dos termos. |
| Cadastro, adesão e acesso | Cadastro de conta, solicitação de adesão, login, logout, ativação por convite, primeiro acesso com troca de senha, recuperação e redefinição por token. A camada de servidor trata sessão, atualização de token, identidade atual e contexto de permissões. |
| Feed de publicações | Listagem paginada com carregamento incremental, filtros por tipo, data e estado, cards e detalhe, conteúdo formatado, mídia e galeria. Os atalhos Avisos e Eventos abrem o feed com o tipo pré-selecionado. |
| Agenda, eventos e serviços | Calendário com eventos e aniversariantes, abertura do detalhe e interface de participação. Há integração com inscrição e cancelamento em eventos. Serviços têm uma página própria com listagem e acompanhamento de solicitações pendentes. |
| Perfil do associado | Consulta e atualização de dados de perfil, e-mail e foto, gestão de veículos com galeria, dependentes com fotos e formulários de perfil médico. A atualização dos dados e do e-mail usa chamadas separadas à API. |
| Financeiro do associado | Listagem de cobranças próprias com filtros, ordenação e paginação. Os componentes e actions tratam acesso condicionado à situação associativa e download de comprovantes conforme permissão. |
| Administração de identidade e acesso | Listagem, pesquisa, detalhe, criação e edição de usuário, alteração de status, convite e primeiro acesso, atribuição de papel e exportação. Gestão de papéis e permissões com ativação, inativação, exclusão e alteração de vínculos. |
| Administração de associação | Actions para solicitações de adesão, cadastro de associados, relatório de carteirinha e configuração da cobrança de referência. Essas actions integram a camada de administração e compartilham rotas e componentes. |
| Administração de dependentes e saúde | Listagem, detalhe, edição, ciclo de vida e exportação de dependentes, incluindo foto privada. Consulta e edição administrativa de dados médicos com permissões distintas para leitura e escrita. |
| Administração de veículos e ferramentas | Listagem, detalhe, cadastro, edição, exclusão e exportação de veículos. Gestão de ferramentas com foto e alteração de estado. As URLs administrativas `/admin/ferramentas` e `/admin/tools` usam o mesmo componente. |
| Administração de publicações | Visão geral com filtros e exportação, listas por categoria e telas de detalhe para eventos, avisos e serviços. Inclui criação, rascunho, ações sobre publicações publicadas e estados operacionais específicos de cada tipo. |
| Administração financeira | Visão geral, gestão de definições, ciclos e atribuições de cobrança, movimentações, listagem e detalhe de pagamentos, comprovantes e exportação. A interface trata ações e consultas conforme as permissões. |
| Navegação e interface | Layouts públicos e autenticados, tema claro/escuro, configuração de preferências, tutoriais guiados, breadcrumbs, tabelas, seletores, filtros e calendários reutilizados. A rota de notificações exibe um estado vazio fixo no commit analisado. |

O texto dos Termos de Uso versionado contém descrições que divergem do código analisado. Por exemplo, há actions para enviar atualizações cadastrais à API e para inscrição em eventos. A presença dessas integrações caracteriza o que foi implementado no código e deixa em aberto a disponibilidade dos fluxos em um ambiente implantado.

### Backend

| Área | Implementação encontrada em `develop` |
|---|---|
| Autenticação | Criação de sessão e autenticação por token, emissão e renovação de credenciais, revogação, convite de ativação e recuperação de senha por solicitação pública ou fluxo administrativo. Inclui senha temporária, expiração manual e administração e exportação de contas, sessões, tokens e recuperações. |
| Autorização | RBAC com vínculo usuário–papel e papel–permissão, sincronização de permissões, bootstrap de papel raiz, consulta de permissões atuais, manutenção e arquivamento de papéis, histórico e exportações. As operações usam permissões e, quando aplicável, proprietário e vínculo associativo. |
| Identidade | Cadastro básico, perfil próprio, atualização de e-mail, consulta de aniversariantes, consulta e busca administrativa, criação e correção cadastral, status, histórico de exclusão e exportação. O módulo fornece consultas a outros módulos por contratos internos. |
| Associação | Solicitação, avaliação, aprovação, rejeição, bloqueio, notificações de rejeição e ativação por token. Cadastro de associados com matrícula e importação, onboarding reversível, configuração de cobrança de referência e conclusão do fluxo de associação paga. |
| Dependentes | Operações próprias e administrativas, busca paginada, validação de vínculo e controle de foto privada, histórico e exportações. Regras de exclusão preservam referências exigidas pelo ciclo de vida do titular e do dependente. |
| Saúde | Cadastro e manutenção de perfil médico para usuário ou dependente, acesso condicionado à titularidade e às permissões, consultas e exportações administrativas. O acesso a dados sensíveis integra a trilha de auditoria. |
| Veículos | Operações do proprietário e administrativas, busca, estados, fotos e galeria, exportação e histórico. As validações de ciclo de vida integram referências de outros módulos. |
| Ferramentas | Cadastro, consulta própria e administrativa, fotos, atualização, ativação, inativação, exclusão e exportação do catálogo e do histórico. |
| Publicações e interação | Feed, avisos, serviços e eventos, com operações distintas para associados e administradores. Inclui comentários e curtidas, exportação, solicitação de publicação de serviço e solicitação de alteração com tratamento administrativo. |
| Operação de eventos | Inscrição, alocação de veículo, convidados, ofertas e respostas de carona, cancelamento, consulta de participantes e relatórios. O módulo integra cobrança e acesso controlado a dados de saúde em cenários autorizados. A V16 adiciona local editorial e limite de participantes. |
| Financeiro | Definições, ciclos e atribuições de cobrança, geração de cobranças, consultas do associado e da administração, registro e gestão de pagamentos, anexos e comprovantes, solicitação e tratamento de estorno, cancelamento e exportação. Inclui regras de cobranças para associação e eventos. |
| Mídia e armazenamento | Upload e leitura de imagens, tratamento e orquestração de arquivos, integração com fotos privadas e galeria. O catálogo versiona ativos, variantes, referências, classificação, estados físicos e eventos de auditoria. |
| Infraestrutura transversal | Documentação OpenAPI, erros Problem Details, logging HTTP com correlação de requisições, auditoria transacional, configuração de e-mail, persistência MySQL controlada por Flyway e automação de verificação, migrations e implantação. |

Na branch `461`, a operação de eventos também registra snapshots da capacidade do veículo na inscrição ou alocação e mudanças posteriores, rejeita reduções incompatíveis com a ocupação e mantém a capacidade inicial como desconhecida para inscrições legadas sem snapshot. A branch acrescenta a V17 e altera o tratamento de exclusão de dados operacionais, preservando a trilha editorial e referências financeiras nos cenários testados.

## 8. Cenários de teste e resultados registrados

### Backend em `develop`

Cada quantidade abaixo conta anotações JUnit em um diretório de testes. Testes parametrizados podem executar várias vezes por anotação. Os nomes das classes permitem localizar a evidência em `src/test/java/com/jeepclub/backend`.

| Área dos testes | Anotações | Cenários e classes de referência |
|---|---:|---|
| Autenticação | 205 | Segurança HTTP, login concorrente, expiração de senha manual, transação de refresh e recuperação de senha. `ConcurrentLoginIntegrationTest`, `RefreshTokenTransactionIntegrationTest` e `ManualPasswordExpirationMySqlIT`. |
| Autorização | 63 | Administração de papéis/permissões, atualização de vínculos, estados e contrato OpenAPI. `AuthorizationAdministrationIntegrationTest`, `AuthorizationAdministrationMySqlIT` e `AuthorizationOpenApiIntegrationTest`. |
| Identidade | 171 | Criação e edição administrativa, perfil próprio, aniversariantes, concorrência em alterações, busca MySQL e comportamento diante de falha de auditoria. `AdminUserUpdateIntegrationTest`, `AdminUserAuditFailureIntegrationTest` e `AdminUserSearchMySqlIT`. |
| Associação | 260 | Ativação, adesão paga, permissões administrativas, cadastro e importação desabilitada, onboarding numérico e entrega de notificações de rejeição. `PaidMembershipFlowIntegrationTest`, `MembershipRegistryOnboardingMySqlIT` e `RejectionNotificationMySqlIT`. |
| Dependentes | 102 | Busca administrativa, acesso a fotos, segurança de titularidade e contrato HTTP. `DependentSecurityIntegrationTest`, `DependentPhotoMySqlIT` e `AdminDependentSearchMySqlIT`. |
| Saúde | 103 | Perfis por titular, segurança HTTP e OpenAPI, persistência e concorrência de perfis. `HealthHttpMySqlIT`, `MedicalProfileConcurrencyIntegrationTest` e `MedicalProfileJpaIntegrationTest`. |
| Veículos | 183 | Busca, autorização, exclusão e ciclo de vida, upload multipart, galeria e consistência da orquestração de mídia. `VehicleMediaMultipartHttpIntegrationTest`, `VehicleGalleryMySqlIT` e `VehicleMediaOrchestrationMySqlIT`. |
| Ferramentas | 29 | Operações HTTP, OpenAPI e busca persistida. `ToolsHttpIntegrationTest`, `ToolsOpenApiIntegrationTest` e `ToolsSearchMySqlIT`. |
| Publicações | 227 | HTTP e persistência de avisos, serviços, feed e interação. Para eventos, leitura, inscrição, alocação, cancelamento, convidados e respostas de carona, incluindo falhas que devem preservar o estado. `EventHttpIntegrationTest`, `EventOperationsMySqlIT` e `ServiceChangePersistenceMySqlIT`. |
| Financeiro | 176 | Fluxo HTTP de cobrança e pagamento, unicidade de definição, concorrência de estorno, autorização e armazenamento de comprovantes, transações e ciclo de vida de papéis. `BillingHttpWorkflowIntegrationTest`, `BillingRefundConcurrencyMySqlIT` e `PaymentReceiptSecurityIntegrationTest`. |
| Plataforma, exportações, ciclo de vida e suporte | 293 | Migrations históricas com dados legados, preflight de dados incompatíveis, bloqueios reais, proteção de referências, auditoria transacional, CORS, exportações, catálogo de mídia e regras compartilhadas. `MySqlMigrationIT`, `PrincipalLifecycleMySqlIT`, `ExportHttpIntegrationTest` e `MediaCatalogMySqlIT`. |
| **Total** | **1.812** | **Inventário estático no SHA de `develop`.** |

O perfil Maven de MySQL usa Failsafe e seleciona **25 classes** pelas convenções `*MySqlIT` e `MySqlMigrationIT`. Ele reúne testes sobre esquema e upgrades, integridade referencial, consistência de transações e concorrência. Exemplos concretos incluem uma criação de dependente concorrendo com exclusão do usuário, alterações de perfil médico concorrendo com exclusão do titular e dados legados inválidos que precisam ser rejeitados antes de alterar registros operacionais.

Na branch `461`, há casos específicos para gravação de histórico sem duplicar mudanças nulas, ausência de snapshot inicial em registros legados, rollback, redução de capacidade com ocupação concorrente no MySQL, preservação de eventos legados no upgrade V17, privacidade na auditoria de saúde e omissão de dados operacionais de relatórios após exclusão física.

### Frontend em `main`

| Camada | Como os testes são organizados | Cenários implementados |
|---|---|---|
| Jest | 77 arquivos TypeScript. Testes de utilitários, schemas, camadas de cliente/servidor e regressões de operação. | CPF, CEP, telefone, e-mail, senha e datas; aceite no cadastro; login e refresh; permissões e sessão; contratos de perfil, cobrança e pagamento; fotos de dependentes e veículos; filtros e paginação do feed; gestão de publicações; exportações, recibos, tema e tutoriais. Exemplos em `passwordContractActions.spec.ts`, `paymentPermissions.spec.ts`, `eventAdministration.spec.ts` e `adminDependents.spec.ts`. |
| Playwright funcional e responsivo | 21 specs funcionais. Nove projetos combinam Chromium, Firefox e WebKit com larguras de 375, 768 e 1.440 px. A configuração usa app local, Storybook e API isolada. | Layout da página inicial, login e validação, estados de carregamento, navegação e detalhes do feed, administração de publicações, categorias, período, filtros, breadcrumbs e interações de tabelas, campos e seletores. |
| Playwright e axe | 10 specs de acessibilidade em dois projetos Chromium, desktop e mobile. Há cenários nos temas claro e escuro. | Rótulos, foco, mensagens de erro, contraste e estados de inputs, calendários, selects, tabelas, login, feed, listas e edição de publicações. |
| E2E de contratos | Dois arquivos `*.spec.mjs` com dados semeados em API local e cenários de navegação e chamadas ao frontend. | Schemas do JSON real, acesso pelo titular e bloqueio de outro usuário, acesso de leitura sem permissão de escrita, primeiro acesso de uso único, reutilização de token, inscrição confirmada/cancelada, respostas 201/204 sem corpo e exportações CSV/PDF e comprovantes. Há casos controlados de falha de refresh, resposta HTML indevida e indisponibilidade. |
| Smoke de produção | Script avulso `tests/productionServerActionErrors.mjs`, fora da contagem de 110 arquivos de specs. | Erros de Server Actions com build de produção e verificação de como aparecem no navegador. |

A configuração padrão do Playwright aponta `testDir` para `tests/browser`. Os dois specs de contrato ficam em `tests/e2e`, fora desse diretório, embora o `package.json` tenha um script dedicado que informa os caminhos. A descoberta desse script no SHA atual fica sem confirmação, pois não foi executado. A configuração padrão de cobertura do Jest mede um recorte de utilitários de máscaras, validações e funções auxiliares.

### Evidências históricas com execução registrada

- **Frontend, auditoria de 29/10/2026.** O registro em `deploy/contracts-audit.md` informa **69 testes Playwright aprovados**, **398 testes Jest aprovados em 54 suítes** e **três cenários de erros de Server Actions em produção aprovados**, além de build e TypeScript aprovados. Entre os testes, a auditoria registra **49 contratos JSON reais**. As simulações de falha do cliente HTTP usaram proxy controlado. A auditoria antecede o snapshot de novembro.
- **Backend, auditoria do PR #136, registrada em 08/11/2026.** Os arquivos `docs/testing/pr-136-final-audit.md` e `docs/testing/back-562-event-http-coverage.md` registram **2.590 casos reportados** tanto em `test` quanto em `verify`, com **zero falhas e erros e um skip de link simbólico no Windows por execução**. O perfil MySQL reportou **530 casos**, incluindo 46 de migration e 91 de operações de eventos, com **zero falhas, erros ou skips**. A execução antecede o snapshot de `develop` e pertence a um recorte histórico.
- **Backend, auditoria JaCoCo no commit `56cce5b41fe5682a714fd96e39a8124bf9dd7475`.** A medição combinou execuções Surefire e MySQL sobre o mesmo bytecode. Naquele recorte, Identidade teve **94,31% de linhas e 91,97% de branches**. Autorização teve **92,37% e 77,48%**. Publicações teve **93,84% e 82,72%**. Plataforma teve **94,48% e 81,63%**. Os números são por módulo e por commit, registrados em `docs/testing/final-hardening/coverage-summary.md` e `execution-evidence.json`.

Os resultados de aprovação e cobertura acima vêm dos registros de auditoria versionados. O levantamento atual inspecionou o código e esses registros. O resultado de execução nos SHAs atuais de frontend, backend e branch `461` permanece sem aferição nesta rodada.
