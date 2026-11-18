# Validação progressiva (BACK-682)

Use Python 3.10+, Git e o Maven wrapper existentes. Java 17 é a referência do CI.
No Windows, configure `JAVA_HOME` e acrescente seu `bin` ao `PATH` da sessão.
Os testes MySQL 8 exigem Docker disponível e criam containers
descartáveis; o runner não recebe URL, senha ou conexão de banco externo.

```powershell
./scripts/test.ps1 --band F1 --test ToolServiceTest
./scripts/test.ps1 --band F1 --test ToolServiceTest#preservesOwnershipCheck
./scripts/test.ps1 --band F2 --module tools --inspect
./scripts/test.ps1 --band F3 --module billing --dependency iam.identity --impact-review 'Contrato UserQuery; revisados imports, eventos e consumidores externos' --inspect
./scripts/test.ps1 --band F4 --module publications --inspect
./scripts/test.ps1 --migration M1
./scripts/test.ps1 --migration M2
./scripts/test.ps1 --migration M3
./scripts/test.ps1 --migration M4 --inspect
./scripts/test.ps1 --band F5 --inspect
```

Linux: substitua `./scripts/test.ps1` por `bash scripts/test.sh`. Também funciona
`python scripts/testing/progressive.py` (Linux: `python3`). `--module`, `--test`
e `--dependency` são repetíveis. `--inspect` resolve e grava o manifesto, imprime
comandos e **não executa Maven/SQL**. Use nomes de métodos existentes;
seletor obsoleto é erro, não fallback para toda a classe.

## Decisão por risco

| Situação | Começar com | Ampliar conforme evidência |
|---|---|---|
| Pequena regra de domínio | F1: classe/método explícito | F2 ao concluir o módulo |
| Funcionalidade concluída no contexto | F2 | F3 se houver contratos/consumidores |
| Contrato entre módulos, segurança ou ownership | F3 + revisão de impacto | F4 se persistência/concorrência |
| Persistência, SQL, locks ou transações | F4 | M1–M4 conforme risco de migration |
| Nomenclatura/documentação somente | inspeção/diff proporcional | Não executar suites sem hipótese |
| Encerramento de PR | F5 ou CI equivalente no SHA final | Nenhuma faixa parcial substitui os gates |

F3 e F4 são complementares. O manifesto usa `validation_scope=project_full` para F5,
`migration_full` para M4 e `partial` nas demais faixas. M4 também informa
`partial=true` em relação ao projeto. A justificativa deve tratar os contratos afetados,
não quantidade de classes. F1–F4 e M1–M3 sempre são **validações parciais**.

## Descoberta e limites

O projeto tem um artefato Maven; módulo aqui significa contexto/pacote, não reactor.
O inventário percorre `src/test/java` a cada execução, inspeciona a declaração da
classe do arquivo e usa os defaults Surefire (`Test*`, `*Test`, `*Tests`, `*TestCase`)
e os includes Failsafe do perfil `mysql-migrations` (`*MySqlIT`, `MySqlMigrationIT`).
Classes abstratas/interfaces e helpers sem convenção executável ficam separados.
Um arquivo com anotação JUnit conhecida e nome incompatível provoca erro explícito.
Candidates com nome elegível e testes herdados/composed continuam no inventário;
ausência de casos no XML falha a rodada. Nested e parametrizados são contabilizados
pelos casos reais dos XMLs, nunca por número de classes. A quantidade de invocações
dinâmicas só é conhecida após executar JUnit.

Contextos novos são obtidos dos pacotes. `iam.identity`, `iam.authentication` e
`iam.authorization` incluem os respectivos pacotes legados sem prefixo `iam`.
Testes raiz da aplicação pertencem à composição `platform`. Outros pacotes legados,
como `security`, `storage`, `exports` e `lifecycle`, permanecem visíveis no manifesto.
Uma alteração nos includes/excludes/grupos dos plugins exige revisão do contrato
de descoberta; o runner falha em vez de ignorar o novo filtro. Plugins ou engines
novos, fontes geradas, outro source root, meta-anotações desconhecidas em arquivos
sem convenção e sem métodos JUnit conhecidos exigem evolução explícita do mecanismo.
Isso é análise de fonte conservadora, **não implementação do discovery JUnit**.
Os gates Maven integrais são o oráculo independente; confronte seus XMLs com o
inventário e investigue qualquer diferença antes do aceite.

F2 seleciona os testes Surefire do contexto. F4 seleciona seus ITs MySQL.
F1 também aceita IT explícito e escolhe o perfil correto.
As classes completas são resolvidas; são aceitos nome simples inequívoco ou FQCN,
e opcionalmente um método. Não se aceitam wildcards, exclusões ou expressões Maven
arbitrárias na interface: o objetivo é auditar uma seleção concreta.
Classes completas e métodos de classes diferentes podem gerar invocações Maven
separadas: o filtro global de métodos do Surefire pode excluir casos nested de
outra classe completa. Cada gate conserva sua própria seleção e reconciliação.
Quando a classe inteira já está selecionada, um método focal dessa mesma classe
é conferido no XML da execução completa, sem repetir seus casos. Métodos nested
recebem o padrão interno necessário ao Maven; as identidades observadas são
conferidas contra o seletor concreto, sem ampliar métodos silenciosamente.

Seleção Surefire longa usa o `surefire.includesFile` nativo, com um seletor por
linha no diretório da rodada, evitando o limite do `cmd.exe` no Windows. Nos demais
casos são usados `-Dtest`/`-Dit.test`. Não se usa `failsafe.includesFile` para estreitar
ITs: ele se soma aos includes explícitos do POM e ampliaria indevidamente o escopo.
Seleção focal de IT que exceda o limite do Windows falha com diagnóstico; divida a
seleção ou escolha o perfil integral. Referência: [Surefire includesFile](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html#includesFile).

F3 lê imports de produção **e testes**, registra consumidor, fornecedor e path,
e percorre o fechamento transitivo nos dois sentidos. Acrescenta transversais
`platform`, `shared`, `architecture` e `lifecycle` quando existem. É conservador:
imports de infraestrutura podem aproximá-lo da suite completa. Uma dependência
com módulo sem testes descobertos falha como não classificada. Eventos, reflexão,
configuração, consumidores externos e relações sem imports não são inferíveis
com garantia. Por isso `--impact-review` é obrigatório e `--dependency` declara
relações semânticas adicionais com módulos existentes; o manifesto conserva ambos.
Uma justificativa vazia/meramente formal não substitui auditoria independente.

Ao adicionar/modificar testes, módulos ou dependências, **execute inspeção da faixa
apropriada e mostre o elemento novo no manifesto**, além do XML da execução.
Reexecute regressões do runner: `python -m unittest discover -s scripts/testing -p 'test_*.py'`.
Os cenários sintéticos acrescentam classes/contextos/imports e exigem inclusão ou
erro; não há catálogo fixo de centenas de classes.

## Migrations

| Nível | Comprova | Não comprova |
|---|---|---|
| M1 Static | nomes, versões únicas append-only, imutabilidade contra base Git e SHA256SUMS históricos | sintaxe SQL, schema, dados, locks |
| M2 Schema Smoke | dois métodos existentes de MySqlMigrationIT: banco vazio/idempotência/comparação física/JPA e startup Boot Flyway antes de Hibernate validate | upgrade de dados do legado |
| M3 Migration Impact | MySqlMigrationIT inteiro: baselines históricos V1/V2/V3, sentinelas, constraints, upgrades específicos, preflight, drift/checksum e SQL inválido | todos os ITs MySQL ou risco novo não ensaiado |
| M4 Full Migration | profile MySQL integral + `deploy/test-future-migration.sh` | aceite de banco operacional/produção |

M3 escolhe conservadoramente a classe histórica inteira, evitando manter uma lista
frágil de métodos de upgrades. Novos métodos dessa classe entram automaticamente.
M2 mantém somente dois seletores específicos; remoção/renomeação falha na resolução.
M4 preserva o ensaio V+1 em `git archive HEAD`; F5 inclui, nesta ordem, `verify`,
MySQL e V+1. Exige HEAD limpo e versionado para não testar código antigo.

M1 compara scripts/fixtures com uma base Git verificável. Nas faixas parciais F1–F4
e M1–M3, o padrão local é `merge-base(HEAD, origin/develop)`: isso permite branch
recém-criada com HEAD igual à develop e develop avançada em paralelo, mantendo as
alterações locais não commitadas visíveis para discovery. Ref ausente ou sem ancestral
comum falha explicitamente. `--base <SHA>` escolhe uma referência explícita, que precisa
resolver para commit distinto e ancestral de HEAD; prefira SHA para reprodutibilidade,
e uma ref inválida nunca é substituída.
F5/M4 conservam a exigência de baseline histórica distinta, ancestral e compatível
com `origin/develop`, além de HEAD limpo e commitado. A base e seu SHA ficam no manifesto.
Uma base que já contenha
uma alteração indevida não prova integridade anterior; escolha e audite a referência,
nunca altere hashes para passar.
Quebra de linha CRLF/LF é normalizada como nos testes históricos existentes.
Não há cache: toda migration nova é detectada novamente pelo nome e hash.

Uma migration nova deixa M1/M2 explicitamente parciais e bloqueia M3 até fornecer
`--risk-file caminho.json`, com uma entrada por SHA256 normalizado da migration:

```json
{
  "sha256_do_script": {
    "reason": "DDL aditivo; cenário ensaia upgrade da versão anterior, preservação e constraint",
    "tests": ["NovoUpgradeMySqlIT#preservaLegado"]
  }
}
```

F5 e M4 aplicam a mesma exigência. No CI, PR prova que `GITHUB_SHA` é o merge
sintético contendo o head exato do payload. Se o alvo avançar enquanto o PR está
aberto, o pai de base do merge pode ser mais novo que `pull_request.base.sha`; ele
só é aceito se o SHA de base do evento for ancestral comprovado, e esse pai real
é usado como comparação. Push em
`develop`/`master` usa `push.before` até `push.after`, incluindo todos os commits
intermediários; dispatch exige o input obrigatório `migration_base`, um SHA ancestral
explícito. SHA inválido/zero, base igual ao HEAD, commit ausente, relação não ancestral,
force-push, ref divergente ou checkout diferente falha fechado. O selo anterior ao gate
MySQL inclui evento, ref e base resolvida e precisa coincidir na auditoria posterior.
O CI usa checkout completo; histórico indisponível falha diagnosticamente. Nenhum
evento usa `origin/develop` como fallback. O arquivo padrão é
`docs/testing/migration-risk.json` (versione-o junto à migration) ou use
`--risk-file`. Declare métodos MySQL explícitos: classe inteira ou somente smoke
não comprova a regressão. O runner confronta esses métodos com os XMLs recém-gerados
do profile integral no mesmo HEAD limpo, sem repetir M3. O CI registra identidade
do checkout/run antes do comando MySQL original e audita seus XMLs depois do V+1;
relatórios antigos, incompletos, de outro SHA ou run são recusados. A aprovação da
revisão semântica e da suficiência dos cenários de dados/constraints permanece humana.
Para auditorias locais completas, a base explícita deve ser ancestral de HEAD e
compatível com a história comum de `origin/develop`; no CI, os vínculos do evento
validam a referência sem depender de uma branch remota já atualizada.

Os seletores precisam existir e ser MySQL. A declaração deve apontar regressões de
upgrade/dados/constraints proporcionais ao risco; smoke vazio sozinho não é suficiente.
O runner verifica existência e execução, não consegue provar a suficiência semântica
do teste ou da justificativa. Na ausência de cenário adequado, implemente o teste
ou registre bloqueio: não declare compatibilidade histórica completa.
Nunca editar migrations aplicadas/fixtures, regenerar hashes, fazer repair, clean
ou baseline automática para recuperar falhas. O baseline explícito nos cenários
históricos existentes é parte do laboratório, não uma operação do runner.

`python scripts/testing/probe_migrations.py` testa o mecanismo com migration V+1
válida e inválida em archives isolados do **HEAD commitado**. Executa M2 real em
cada cópia e verifica bloqueio de M3 sem revisão; guarda evidência em
`target/progressive-probes/`. Não execute após cada microedição.
O SHA do archive fica em `source.json`. Para reavaliar somente evidências preservadas,
sem repetir MySQL, use `--audit-existing <diretório-da-rodada>`. Logs brutos podem usar
code page Windows; a visão diagnóstica tolera bytes não UTF-8 sem adulterar o arquivo.

## Evidência e falhas

O runner e o auditor comparam métodos Jupiter anotados em fontes existentes com a
base validada. Localmente, faixas parciais usam o ancestral comum de HEAD e
`origin/develop`; `--base` explícita exige SHA/commit ancestral distinto. F5/M4
mantêm a política integral estrita. No CI, auditores usam a base do evento, evitando
perder métodos introduzidos pelo push que atualizou `origin/develop`. História ausente
falha explicitamente. Cada método novo no
escopo é confrontado com os XMLs, distinguindo executado, skipped, não descoberto,
falho, não selecionado e não comprovado. Se um foco omitir outro método novo da
mesma classe, o resultado falha com `not_selected`; inclua-o ou execute a classe.
`@TestFactory`, ou um factory que torne a atribuição de
outro caso ambígua, exige comprovação manual e bloqueia o aceite; hierarquia de
classe/interface alterada, herança ambígua e declarações não reconhecidas exigem revisão. Helpers e
hooks sem anotação de teste não entram nessa comparação. A execução parametrizada
exige ao menos uma invocação reconhecível e nenhum skip/falha correspondente.

Cada seleção válida grava `target/progressive/<UTC>/selection.json`: SHA, branch,
worktree dirty, ambiente/Java, candidatos/helpers, seletores, não selecionados,
comandos, relações e limites. `result.json` preserva cada gate, duração, código
original, inputs solicitados e declarações de risco ficam no manifesto. O resultado
preserva a seleção de cada comando, além do código
original, casos JUnit por estado (`passed`, `failure`, `error`, `skipped`), seletores
sem casos, classes inesperadas, não executados e comandos restantes. O log completo
fica em `gate-N/execution.log` e os XMLs daquela rodada em `gate-N/reports`.
Relatórios antigos são movidos para `previous-*`, não somados nem tratados como atuais.
Antes de F5/M4, classes compiladas e fontes geradas anteriores são preservadas em
`previous-build` para forçar compilação do HEAD; o estado limpo e o SHA são
revalidados após cada gate. O CI parte de checkout novo e usa os comandos originais.
Testes nested cujo `classname` contém DisplayName usam o nome técnico da suite para
reconciliação, preservando também o classname original e o arquivo de origem, conforme
o precedente de `final-hardening/measure.py`. Para auditar XMLs preservados de uma
seleção parcial sem repetir Maven, use `audit_reports.py --kind unit --reports <pasta>
--selection <selection.json> --out <auditoria.json>`; o resultado continua parcial.

Erro de input retorna 2; erro do Maven conserva seu código; inconsistência XML
retorna falha mesmo com Maven zero. Falta de executável retorna 127. Falta de Docker
falha nos testes Testcontainers, sem fallback H2. Skips de IT tornam o gate incompleto
e falho; seletor unitário inteiramente skipped também falha. Skips unitários parciais
ficam explícitos e requerem avaliação. Em falha, leia o
primeiro erro relevante no log antes de repetir; os gates seguintes não executam.
Não há promessa de cobertura JaCoCo pela seleção, nem soma de rodadas como suite única.

Logs são locais e ignorados pelo Git. Os testes existentes usam dados sintéticos;
mesmo assim revise/sanitize antes de publicar. Não copie env, credenciais ou dados
de produção. Resuma comando, escopo, duração, counts e limitações no checkpoint.
O CI executa Maven verify, MySQL migrations e as regressões Python do runner.
As condições e dependências de deploy são definidas no workflow. CI no SHA
exato pode suprir gate local equivalente; uma execução em outro SHA não aprova a entrega.
