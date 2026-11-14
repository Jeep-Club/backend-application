# BACK-682 — checkpoints de implementação

Base confirmada por `git fetch origin develop`: `3b188f0625faca4df05dbe70ab1f0ed8b71f682f`.
Worktree isolado `BACK-682`; branch `feature/back-682-progressive-test-validation`.
Story e BACK-683/684/685/686 lidas integralmente, incluindo critérios, links e comentários (vazios).
Nenhuma issue alterada. Não houve divergência funcional entre prompt e Jira.

## Investigação e plano

Leituras: `docs/architecture/{README,module-organization,feature-development-rules}.md`,
`docs/database/{README,04-flyway-strategy,05-implementation-validation,06-vps-runbook}.md`,
`AGENTS.md`, `pom.xml`, workflow, script V+1, `MySqlMigrationIT` e testes existentes.
Owner técnico: tooling de testes e `platform.database`; este último não possui README próprio.
Não há mudança de domínio/HTTP, portanto não se altera documentação de bounded contexts.

O projeto é um único artefato Maven, sem reactor de módulos. Contextos são pacotes Java.
Surefire não define includes/excludes: defaults `Test*`, `*Test`, `*Tests`, `*TestCase`.
Failsafe só existe nos dois perfis: `*MySqlIT`/`MySqlMigrationIT` e `*PostgresIT`.
Há wrappers IT que herdam testes de classes Surefire, testes nested/parametrizados e helpers.
JaCoCo permanece acoplado aos plugins existentes; seleção não representa cobertura.
Os scripts `deploy/` e `docs/testing/final-hardening/measure.py` oferecem precedentes
de tooling Python e leitura de XML, mas não um seletor reutilizável.

Plano: Python stdlib com wrappers PowerShell/Bash; resolver candidatos por convenções;
falhar diante de drift das configurações; selecionar usando `-Dtest`/`-Dit.test`;
conferir XML novo contra seleção e preservar logs por rodada. Imports fornecem
relações rastreáveis para fechamento conservador F3, com revisão semântica obrigatória.
Reutilizar cenários existentes de schema/startup para M2 e suite histórica para M3;
exigir revisão vinculada ao hash e teste de upgrade para migration nova. M4/F5
conservam os comandos integrais e o archive V+1, somente em HEAD limpo/versionado.

## Checkpoint 1 — BACK-683 / fundação

Arquivos: `scripts/test.ps1`, `scripts/test.sh`, `scripts/testing/progressive.py`,
`scripts/testing/test_progressive.py`, `.gitignore` (somente cache Python).
O commit deste checkpoint é identificado por `git log --grep=BACK-683`.

- `python -m unittest discover -s scripts/testing -p 'test_*.py'`: 9 testes,
  0 falhas/erros, 0 skips; 0,678 s. Log local `target/runner-tests-m1.log`.
- F1 `ToolServiceTest`: 6 casos, 0 falhas/erros/skips, 36,578 s;
  `target/progressive/20261114T221953711382Z/result.json`.
- F2 Tools em inspeção: 7 classes; F4 Tools em inspeção: `ToolsSearchMySqlIT`.
- F3 Tools em inspeção: 260 classes pelo fechamento conservador; isso evidencia
  acoplamento real e limita economia, não é promessa de análise semântica completa.
- M1 passou contra a base; não executa SQL. M2 iniciado separadamente em MySQL real.

Pendências neste checkpoint: ampliar regressões anti-exclusão, provas de migrations,
documentação operacional, gates finais no SHA publicado e aceite Linux.
Os testes Maven desta etapa precedem o commit e comprovam o estado local identificado
nos manifests, não constituem evidência do SHA final.

## Checkpoint 2 — BACK-684 / anti-exclusão

Fundação registrada em `c9197c57`. O commit deste checkpoint é identificado por
`git log --grep=BACK-684`. Alterações restritas ao discovery, regressões e este registro.

- Inventário atual: 292 classes candidatas, 7 auxiliares; pacotes IAM legados
  normalizados, testes raiz atribuídos a `platform`.
- Métodos herdados resolvidos nas fontes; método desconhecido falha antes do Maven.
- Imports novos alteram fechamento F3 automaticamente; referência a contexto sem
  testes descobertos falha como dependência não classificada.
- Cenários A–E automatizados em diretórios temporários; 19 testes, 0 falhas/erros/skips,
  aproximadamente 0,82 s. Sucesso simulado exige XML novo; Maven zero sem XML falha.
- F2 Tools real: 34 casos, 0 falhas/erros/skips, 37,373 s;
  `target/progressive/20261114T222450404874Z/result.json` (Java 17).
- M2 real: 2 casos, 0 falhas/erros/skips, 107,649 s;
  `target/progressive/20261114T222048181354Z/result.json` (JBR 21 disponível naquela
  chamada; compilação alvo 17; referência final de CI continua Java 17).

Limites: análise de fonte não substitui discovery JUnit; XML é o oráculo de execução.
F3 é conservador e exige revisão semântica explícita para eventos/reflexão/consumidores
externos. Módulos sem testes não podem ser silenciosamente considerados cobertos.
M3 e probes SQL isolados, documentação e gates finais ainda pendentes.

## Checkpoint 3 — BACK-685 / execução progressiva de migrations

Discovery registrado em `88817aa4`. O commit de implementação deste checkpoint
é identificado por `git log --grep=BACK-685`; a validação real é consolidada ao final.

M1 protege história/fixtures por comparação Git, hashes e versão append-only.
M2 reutiliza os dois cenários existentes de banco vazio/Boot. M3 reutiliza toda a
classe histórica, incluindo parametrizados, sem lista fechada de upgrades. Migration
nova bloqueia M3 até declaração de risco por hash com regressão MySQL; apenas smoke
é recusado. M4 conserva profile integral e archive V+1. O runner não recebe conexão
externa, não executa repair/clean/baseline e não modifica SQL/fixtures.

`probe_migrations.py` cria archives isolados do HEAD, acrescenta V+1 válida/inválida
somente nessas cópias e exige resultados reais no Flyway/MySQL. Sua execução depende
do commit desta implementação; resultados posteriores não devem ser atribuídos a um
HEAD anterior. Docker tem preflight explícito e erros/skips de IT não viram sucesso.

22 regressões Python aprovadas, 0 falhas/erros/skips, 0,772 s. Log local
`target/runner-tests-final.log`. A seleção longa Surefire agora usa includesFile
nativo para evitar o limite do cmd.exe; mudança coberta por teste de preservação
integral de seletores. Nenhuma mudança em `pom.xml` nem nos SQL existentes.

M3 real em andamento: `target/progressive/20261114T222625747811Z/` (Java 17).
Próximo: probes isolados, seleção F3 longa real, F4 real, gates finais e CI Linux.
