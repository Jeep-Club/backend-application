# BACK-628 — limites do timeout síncrono

## Estado de encerramento da BACK-628 (BACK-753)

Código funcional auditado em `8261358c9426f75cf72ed15ee9e7797deb35f537`, [CI 45519454119](https://github.com/Jeep-Club/backend-application/actions/runs/45519454119): Maven 2.759 casos, MySQL 637 no gate principal e 637 no ensaio V+1, Publications PostgreSQL 213, sem falhas, erros ou skips nessas execuções; auditoria de migrations aprovada. As duas execuções MySQL não representam casos distintos. A validação não comprova capacidade em produção.

| Issue | Resultado técnico e evidência |
| --- | --- |
| BACK-636 | Matriz de superfícies, contratos, presets, crops e decisões em back-628-consumers.md; integração e UI não fazem parte deste aceite. |
| BACK-637 | Limites de bytes, MIME, dimensões, raster e IPC; StorageMultipartMySqlIT, StorageHttpMySqlIT e SynchronousImageProcessorTest; capacidade agregada no ambiente alvo transferida. |
| BACK-638 | MASTER privada, limites 2/5 MiB, crops independentes, no upscale, sanitização, WebP/fallback; testes do processador e resolvedor; qualidade restrita aos casos/corpus verificados. |
| BACK-639 | F-02 resolvido no recorte da espera indefinida do escritor (8261358c); F-03 resolvido com V27. StorageProcessSupervisorTest, timedOutPhysicalWriterReturnsSanitized503AndRetainsCustody (herdado por StorageHttpMySqlIT), testes de fencing, confirmação perdida e lock em StorageBlobMySqlIT. |

A resposta de timeout não aguarda término residual do escritor; a própria thread reverte o catálogo quando aplicável. O supervisor mantém vaga até saída e limpeza, e a publicação imutável por hard link não sobrescreve o canônico nem modifica o catálogo. V25 registra intents, V26 vincula idempotência, V27 impede mutação de execução antiga. Não existe garantia absoluta de prazo de multipart, pool, I/O do SO, rollback, recuperação JDBC ou commit. Uma confirmação de commit pode chegar depois do deadline; resultado incerto não autoriza retry cego. Sem fila assíncrona de negócio.

O benchmark local anterior (Windows/Java 25/MySQL 8.0.46, 10 sucessos, concorrência 2) registrou p50 2.784 ms e p95 5.394 ms; o smoke Linux/Java 17 com 1 GiB e 2 CPUs não é prova integrada de capacidade. Não atribuir esses resultados ao SHA final. A homologação Linux/Java 17 com orçamento agregado e VPS foi transferida por decisão do PO para [BACK-752](https://jeep-club-developers.atlassian.net/browse/BACK-752); não foi executada. A validação antes da ativação continua obrigatória. Sem deploy, cutover ou purge por este encerramento.

## Verificação por etapa e confirmação de commit

Os detalhes abaixo complementam o estado de encerramento. As seções expressamente históricas preservam decisões e evidências do momento da investigação, não pendências atuais.

### Codec isolado

O upload aguarda o codec em um processo Java filho. Há um filho ativo e
até dois chamadores, com espera síncrona sob o prazo da admissão. O canal
binário tem versão, UUID de execução, limites de input e output, e
validação de formato, dimensões e quantidade de representações no pai.
O filho executa o mesmo processador de imagens com heap de 512 MiB e
dois processadores visíveis à JVM. O backend continua a persistir as
variantes obrigatórias antes de emitir 201.

O ambiente herdado é limpo, preservando apenas a variável técnica
`SystemRoot` no Windows. O filho não inicializa Spring, recebe o
payload pelo pipe e usa um diretório temporário exclusivo. O supervisor
solicita encerramento no deadline e só libera capacidade após observar
a saída do processo e de descendentes detectados e concluir a limpeza.
O executável controlado não inicia subprocessos. O processo usa o mesmo
usuário do contêiner, portanto o isolamento é de execução e recursos,
e não constitui uma fronteira de autorização do sistema operacional.
O heap não representa o RSS máximo do processo.

### Gravação física isolada

O provider privado executa escrita, sincronização, publicação atômica e
leitura de verificação no mesmo processo filho. Há um escritor ativo,
heap de 128 MiB e orçamento de quatro segundos para admissão e resposta.
Ao expirar o prazo, o supervisor solicita o encerramento fora da thread
da requisição. O erro encerra a transação do catálogo na própria thread
e segue o fluxo de 503. Um bloqueio que sobreviva ao pedido mantém o slot
do escritor ocupado até comprovar saída e limpar o diretório temporário.
Por supervisor há no máximo um transporte e uma thread de terminação
residuais. Nenhuma conexão ou transação SQL é transferida ao supervisor.

A falha conserva intents e custódia. Arquivo em estágio permanece
privado, e um retry pode verificar o objeto canônico sob o lock do
catálogo. O teste de filesystem bloqueia após escrita e sincronização
reais, antes da publicação. Confirma a saída do filho, a ausência do objeto
publicado e a capacidade de gravar e verificar em outro processo.

O serviço propaga o mesmo prazo monotônico ao codec síncrono. O processador
verifica o prazo após decodificar a fonte, antes de novas tentativas de
compressão da MASTER, antes de decodificar a MASTER e entre as variantes.
A expiração impede o início da próxima transformação e devolve o slot quando
a chamada corrente retorna. Uma chamada nativa em andamento continua sujeita
aos limites de cancelamento descritos abaixo.

`MediaUploadCapacityTest.expiredUploadStopsAtNextBoundaryAndPreservesCustody`
avança o relógio monotônico em leitura, codec, primeiro e segundo `store`.
Verifica que a próxima etapa fica bloqueada, que a compensação depende de
possível I/O e que a capacidade em memória volta após o retorno da chamada.
O teste cobre o prazo cooperativo. Codec e filesystem ainda ocupam o slot
enquanto suas chamadas estão em voo.

`StorageUploadFlowTest.missingCommitAcknowledgementKeepsCustodyWithEitherDatabaseOutcome`
simula perda da confirmação imediatamente após delegar o commit ao banco.
Uma falha sistêmica de transação ou de conexão depois da reserva recebe
`STORAGE_UNAVAILABLE` (503). Se o commit já ocorreu, o permit concluído
preserva o vínculo e a cobrança sem duplicar estorno ou decremento.
O asset permanece sem referência operacional. O ensaio simula a observação
da aplicação após o commit, sem simular o comportamento de rede do driver.
O cliente não deve interpretar o 503 como prova de rollback ou autorização de retry.

### Retry com chave de idempotência

O POST de perfil aceita `Idempotency-Key` opcional, com 1 a 128 caracteres
ASCII alfanuméricos, ponto, sublinhado, til ou hífen. Autorização e limites
continuam aplicáveis. A identidade persistida vincula o ator à operação
versionada, ao contexto, ao consumidor e à chave. A impressão inclui todos
os bytes da imagem e os crops ordenados pelo nome da variante. Campos
multipart que não alteram o comportamento, como o nome do arquivo, não
compõem a identidade. A chave original não é armazenada.

A V26 vincula a identidade ao permit com unicidade SQL. Uma repetição
idêntica só recebe 201 quando o permit tem um asset vinculado pelo commit
do catálogo. Payload divergente recebe 409 RFC 9457. Enquanto a primeira
tentativa está ativa ou o resultado não pode ser confirmado, o mesmo
payload recebe 503 sem iniciar outro codec, store ou cobrança. A resposta
de sucesso é uma confirmação de upload. A leitura continua sujeita à
autorização e moderação da referência.

A V27 separa o UUID de execução do identificador idempotente do permit.
Cada readmissão grava um novo UUID sob os mesmos locks. Se uma readmissão
sofre rollback e outra ocupa o permit antes da compensação, o UUID antigo
impede estorno, cobrança, alteração de intents ou finalização da nova
execução. Permits históricos sem UUID preservam recibos confirmados.
Uma readmissão permitida grava o primeiro UUID antes de qualquer mutação
do pipeline. O teste MySQL simula rollback real seguido de readmissão
antes da compensação tardia.

Quando o banco confirma compensação antes da cobrança e da primeira
gravação, o mesmo payload pode readmitir o permit sob os locks originais
e os limites vigentes. A chave permanece vinculada ao payload. O caminho
com chave lê e identifica o conteúdo antes de consultar o ledger, sob o
semáforo local e o limite de bytes. A comparação da identidade é necessária
mesmo no replay de um 201. A janela SQL contabiliza novas admissões,
incluindo readmissões, sem cobrar de novo uma resposta recuperada.

O vínculo da chave é retido com o permit, sem vencimento ou reuso
automático. A política abrange tentativas indeterminadas, inclusive quando
o banco posteriormente mostra que o catálogo foi revertido. Um 503 não
autoriza iniciar outra tentativa com uma chave nova. Não há reconciliação
agendada nem purge nesta entrega.

A admissão SQL gera o UUID do permit antes de iniciar a reserva. Se a
transação falhar com confirmação indeterminada, o adapter tenta compensar
em uma transação nova. Ela adquire os locks global e do ator na mesma ordem
da reserva antes de ler o permit. Sem permit, a reserva não alterou os
contadores. Com permit confirmado e ainda sem cobrança, a compensação
devolve os bytes reservados e o slot SQL. A tentativa continua contabilizada
na janela de admissão. O upload ainda não recebeu o permit nessa fase, logo
nenhum store físico pode ter começado. Uma falha de compensação preserva a
falha original e exige investigação do estado persistido.

`StorageUploadFlowTest` verifica commit e rollback reais seguidos de perda
simulada da confirmação da reserva. No mesmo teste de fluxo, os dois
resultados do catálogo preservam a custódia e uma cobrança, e apenas o
commit confirmado no banco preserva o vínculo com o asset. Esses ensaios
não simulam a indisponibilidade prolongada do banco ou a falha de rede.

## Histórico — benchmark por socket e MySQL

### Checkpoint de entrega

Os commits `1f987a99`, `1e7d7e3b`, `37f72cad`, `f8ac98b4` e
`a2b81b4d` compõem a implementação desta rodada.

| Issue | Entrega e evidência | Aceite |
| --- | --- | --- |
| BACK-636 | Inventário de consumidores, contratos, variantes, crops, cache e legado. Frontend `develop` em `5e366e79`, consultado em leitura. Três fotos públicas verificadas. | Parcial. A validação visual e de densidade por superfície fica na integração. |
| BACK-637 | Testes de 10 MiB, MIME, corrupção, truncamento, streams inconsistentes e dimensões. Rejeições verificadas via HTTP com MySQL. Erros do Storage respeitam a feature flag. | Parcial. Memória e CPU no domínio completo de entradas seguem em avaliação. |
| BACK-638 | MASTER privada, crops independentes, ausência de upscale e rejeição quando a redução compromete a resolução disponível das derivadas. | Atendido no recorte backend. |
| BACK-639 | Prazo cooperativo com relógio monotônico, testes de leitura, codec e gravações. Simulação de confirmação perdida após commit real preservou um asset completo, uma cobrança e zero referências operacionais. | Parcial. F-02 em aberto para operações bloqueadas e recuperação de resultado indeterminado. |

No SHA de implementação `a2b81b4d66ee54ca29769cc5a3d5deb729866526`,
o [CI](https://github.com/Jeep-Club/backend-application/actions/runs/45298645131)
aprovou 2.742 casos Maven, 625 MySQL e 206 PostgreSQL, zero falhas ou skips.
Também passaram a validação de schemas históricos, o ensaio V+1 e a auditoria
de risco das migrations. F2 local passou com 134 casos e um skip de symlink
no Windows. O benchmark padrão registrou 23 respostas 201 e p95 de 2.295 ms.

Em 512 MiB de heap, o benchmark com servidor e cliente de carga na mesma JVM
teve um HTTP 500 por falta de heap. Com o cliente em uma JVM separada, o mesmo
corpus passou com 23 respostas 201, zero falhas e p95 de 2.669 ms. O ensaio
focal com PNGs transparentes de 20 MP concorrentes segue em execução no
momento deste checkpoint. As alterações experimentais do diagnóstico ficam
locais durante a execução. O F-02 permanece aberto e o PR segue em draft.

O diagnóstico opt-in `StorageSocketBenchmarkMySqlIT` mede o POST de perfil
com dois uploads concorrentes. Em `f8ac98b4ab66e34d0a874519d6faf1a1506994ac`,
Windows 11, Ryzen 5 PRO 5655GE (12 CPUs lógicas), JDK 17.0.20 e MySQL 8.0.46,
foram 2 aquecimentos e 23 amostras medidas. O corpus reuniu PNG, JPEG e WebP
sintéticos de até 20 megapixels e três JPEGs da galeria pública do frontend
`develop` em `5e366e79015be2472a16dfbe5d435528884f637a`. Entradas de
67.721 a 4.616.257 bytes, p50 de 964 ms, p95 de 2.555 ms, máximo de
2.564 ms e 23 respostas 201. O ensaio verificou 3 variantes por asset, bytes,
integridade e contabilidade. A MASTER ficou entre 67.447 e 1.541.239 bytes.
Esse resultado caracteriza o corpus no host local, sem medir latência sob
falha, pico de heap/CPU ou capacidade de produção.

O corpus opcional é lido ao passar
`-Dstorage.benchmark.publicGallery=<diretório public/images do frontend>`
ao [comando diagnóstico](../../../../../../../../docs/testing/progressive-validation.md#diagnósticos-de-storage).

## Histórico — prazo cooperativo após admissão

`MediaUploadService` mede 10 s com relógio monotônico a partir da aquisição
do permit em memória. Verifica o prazo após a reserva SQL, a leitura e o
processamento da imagem, antes de gravar as intenções e cada blob, e no início
e no fim do callback da transação do catálogo. Prazo vencido produz 503
sanitizado. Antes do primeiro `store`, a compensação libera a reserva e a
cobrança. Após iniciar um `store`, as intenções, a custódia e a cobrança
conservadora permanecem. Se o prazo vencer no callback, asset, variantes,
vínculos e conclusão SQL do permit sofrem rollback juntos. A finalização do
permit é tentada separadamente após a falha.

O permit em memória acompanha a execução síncrona até o fim da finalização.
Uma chamada em voo pode ultrapassar o prazo. A checagem final precede o
commit, que ainda pode demorar ou ter resultado desconhecido. Um commit
confirmado pode responder 201 após o prazo. Multipart, limpeza e resposta
HTTP também ficam fora do limite de processamento. F-02 permanece aberto.

Na rodada 2, a cobrança passou a usar os tamanhos das intenções calculadas
antes da chamada SQL. Há checagens entre representações e antes de vincular
cada variante, inicializar governança e concluir o permit. A fase de custódia
começa imediatamente antes de chamar `store`. Mesmo com erro na primeira
chamada, a compensação mantém a cobrança, pois o port não comprova ausência
de escrita. Se a reserva SQL falhar com resultado incerto antes de devolver
o identificador, a aplicação fica sem referência para finalizá-la e depende
de reconciliação. Se o commit do catálogo perder a confirmação, a conclusão
do permit e seu vínculo podem já estar persistidos. A finalização separada
é idempotente e não estorna cobrança nessa fase. O cliente continua sem
chave de idempotência para repetir a operação com segurança.

Na validação posterior, injetar atraso antes do primeiro `store`, durante
uma gravação e dentro do callback SQL, verificando 503, contadores, intents,
custódia, rollback e reutilização do slot. Validar separadamente erro de
conexão no commit em banco real.

## Histórico — diagnóstico anterior à checagem cooperativa

No commit-base `ebb3e7d5b6a46c854cedd703e42cdde7d1a2c9d6`, a recepção
multipart ocorre no servlet antes do controller. O serviço admite até dois
uploads por processo com `tryAcquire` e reserva quota SQL antes de ler o arquivo.
O codec, a gravação física e as transações executam na thread da requisição.
O semáforo não define um prazo para uma operação já admitida.

`SynchronousImageProcessor` chama ImageIO e `WebPCodec.decodeImage` e
`encodeImage` sem um contrato de deadline ou cancelamento. A API pública de
`webp4j-core` 2.5.0 expõe métodos síncronos sem parâmetro de cancelamento;
isso não demonstra que interrupção da thread interrompa o codec nativo.
`LocalPhysicalBlobStore` escreve, executa `FileChannel.force(true)`, move e
verifica o objeto. `BlobCatalogAdapter` mantém lock SQL durante o I/O e confirma
a reserva `ready=false` antes dele. O código não estabelece timeout do lock,
do provider ou do commit; uma interrupção ou deadline HTTP não comprova que o
I/O e a transação terminaram.

Uma verificação do relógio entre etapas pode impedir iniciar a etapa seguinte,
mas não interrompe uma chamada presa e não prova que a confirmação do banco não
ocorreu. Uma thread auxiliar cancelada por `Future.cancel` também não prova que
codec, I/O ou commit pararam; retornar 503 enquanto ela ainda escreve deixaria
o resultado e a custódia incertos. Não há garantia de timeout ponta a ponta no
desenho atual. A limitação é de comprovação e de capacidade de cancelamento dos
contratos atuais, não uma alegação de que toda chamada nativa seja interminável.

Uma alternativa para avaliação é isolar somente o processamento de imagem em
processo descartável, aguardado pela requisição, com prazo, término confirmado e
capacidade limitada; a thread principal só iniciaria escrita após receber o
resultado validado. Isso exige revisar D07 e não resolve sozinho multipart,
provider, locks, commit de resultado desconhecido ou idempotência. Esses trechos
precisariam de limites próprios e protocolo de custódia/resultado demonstrável;
uma queda de conexão durante commit continua exigindo reconciliação. A decisão
de estratégia e seus custos operacionais devem ser revisados antes de alegar
F-02 concluída. Nenhum worker ou fila é introduzido por esta investigação.

## Histórico — Quota e falhas de gravação

`charge` antecede o primeiro `BlobCatalog.store`. Uma falha de leitura ou de
processamento antes de `charge` termina com permit não cobrado e libera a
reserva. O teste que lança ao entrar em `store` usa mock que não grava: ele
caracteriza quota cobrada, mas não prova que uma exceção do port real implique
ausência de escrita. Quando o adapter já reservou `ready=false`, uma falha do
provider pode deixar registro de custódia sem prova de bytes; após um `store`
confirmado, o blob pode permanecer íntegro e sem asset após falha posterior.
O fluxo atual não registra no permit qual dessas situações ocorreu. Portanto,
estornar automaticamente baseado apenas na exceção pode subcontabilizar dados
ou uma gravação de resultado desconhecido; a política existente é conservadora
e pode cobrar sem bytes físicos. Os testes adicionados caracterizam esses casos,
sem afirmar que o custo foi aprovado como regra de produto nem alterar quotas.

O código usa `accountedBytes` como valor reservado ou cobrado por permit e
`chargedUploads` como contador incrementado no `charge`; `finish` devolve a
reserva somente para permit não cobrado. Na inicialização, `usedBytes` global
é calculado pela soma dos blobs e o de usuário pela soma de variantes de seus
assets. Já `charge` soma representações processadas por tentativa, inclusive
quando bytes são compartilhados. Portanto o contador global após cobranças não
equivale necessariamente a bytes físicos únicos. A semântica de cobrança para
falhas após entrar no port e a reconciliação desses contadores permanecem uma
decisão em aberto; não se deve interpretar a exceção do port como prova de
ausência de custódia.


## Histórico — Rastreabilidade da tentativa

Antes do primeiro `store`, a tentativa confirma em transação independente a lista
ordenada de SHA-256 e tamanhos das representações planejadas. O registro é intenção,
não prova de existência ou integridade física; pode incluir representações cuja
escrita ainda não começou. A conclusão associa o permit ao asset na mesma transação
do catálogo. Uma falha posterior mantém a cobrança conservadora e os registros para
reconciliação manual, inclusive com `storage_blobs` e arquivos `.pending`. Permits
históricos não possuem este vínculo. A ausência do vínculo após falha não autoriza
retry cego enquanto a transação possa ainda estar em curso; nenhum identificador
de idempotência é entregue ao cliente. Isto não limita codec, filesystem, lock ou
commit e não resolve a decisão F-02 nem a política de reconciliação de quota.


## Histórico — Compensação anterior ao armazenamento

A falha antes da primeira chamada ao port de armazenamento libera a reserva e,
se confirmada, a cobrança, mesmo que a resposta da transação de cobrança tenha
sido perdida. A compensação é idempotente e preserva as intenções registradas.
Após iniciar `store`, mantém-se a cobrança conservadora: uma exceção não prova
que a escrita falhou. O limite de uploads retidos é revalidado sob lock no
`charge`, pois várias reservas podem ter sido admitidas antes da primeira cobrança.
Uma falha de compensação conserva o permit para investigação; falha ao reservar
pode ter resultado desconhecido antes que o identificador retorne ao chamador.
Os registros de intenção e permits não têm expiração automática: retenção e
reconciliação permanecem manuais, sem purge autorizado.


## Histórico — Alternativas para F-02

O semáforo limita a admissão por processo, mas não termina uma chamada já
ativa. Um prazo entre etapas pode impedir iniciar trabalho posterior, sem
interromper a etapa corrente. Timeout transacional ou JDBC não demonstra a
interrupção de `ImageIO`, codec nativo, `force`, conexão ou commit. A perda
de resposta no commit continua sendo resultado desconhecido.

| Alternativa | Garantia e custo | Limite ou decisão necessária |
| --- | --- | --- |
| Pipeline síncrono atual, com limites SQL verificáveis | Mantém D07 e não adiciona infraestrutura; espera em banco pode ser limitada onde driver e banco comprovem suporte. | Codec, filesystem e commit podem permanecer em andamento; não comprova deadline integral. |
| Processo descartável aguardado pela requisição | Um supervisor poderia confirmar o término do processo de codec antes de iniciar I/O, com capacidade e prazo delimitados. | Exige decidir isolamento, limites de memória, implantação, limpeza e monitoramento. Não resolve filesystem, commit incerto nem transporte; rever compatibilidade com D07 antes de adotar. |
| Serviço ou fila de processamento | Pode prover isolamento e recuperação operacional. | Altera operação e possivelmente contrato de resposta; contraria o MVP síncrono sem decisão revisada. |

Nenhuma opção torna hash do conteúdo chave de idempotência: uploads iguais de
pessoas ou contextos distintos podem representar operações legítimas. Um futuro
protocolo precisa vincular chave a ator, contexto, consumidor e identidade do
payload, definir conflitos e estados em andamento, e devolver resultado já
confirmado sem permitir consulta cruzada. A decisão sobre contrato, retenção e
recuperação de tentativas incertas permanece necessária; não executar retry cego.

A V25 conserva intents sem expiração automática e permite que permits anteriores
tenham `catalog_asset_id` nulo. A associação é gravada na mesma transação da
criação do asset, mas não tem FK física: não é prova de integridade caso dados
sejam alterados fora desse fluxo. Uma FK nova exigiria validar todo o histórico,
existência dos assets e política de retenção antes de uma migration aditiva; não
se altera V25 nem se apagam intents para satisfazer uma constraint.


## Histórico — Limite de transações de admissão

As transações independentes de admissão possuem timeout Spring de cinco
segundos. Em MySQL 8.0.46 e Connector/J, a regressão de contenção observou
a espera real por lock no `performance_schema`, uma `QueryTimeoutException`
e rollback da tentativa sem reserva aberta. O teste estabelece esse caminho,
não um timeout universal de lock para outros adapters ou dialetos.

O MySQL reverte somente o statement após lock timeout; a exceção deve sair
da transação para que o gerenciador faça rollback. O timeout transacional
pode limitar statements JDBC suportados, mas não comprova cancelamento de
codec, I/O físico ou commit, nem um limite total de duração do upload.


Quando um budget está ausente mas já existem permits para seu escopo,
o bootstrap recusa novas reservas e exige reconciliação: não substitui
a contabilidade de tentativas pela soma incompatível de blobs ou assets.
Um permit aberto pode pertencer a requisição ativa ou a processo encerrado;
sem lease e fencing, idade isolada não autoriza estorno ou liberação automática.

## Histórico — Limites adicionais do catálogo — BACK-639

As transações isoladas de reserva e bloqueio de blob e a transação de conclusão
do catálogo também configuram timeout Spring de cinco segundos. Esse limite é
aplicado aos statements JDBC quando suportado pelo gerenciador e driver; não é
deadline para `putVerified`, `force`, movimentação, codec ou commit. O lock do
blob continua durante o I/O para preservar serialização e integridade da
deduplicação; uma transação expirada pode continuar ocupando recursos enquanto
o provider não retorna. A exceção original da reserva é preservada caso a
verificação posterior de colisão também falhe. O teste de contenção MySQL deve
ser executado no SHA entregue antes de atribuir garantia ao caminho de blob.

## Contenção implementada — BACK-639

O POST continua síncrono. O codec opera em JVM filha com heap de 512 MiB,
limite de um processo ativo e de dois chamadores por JVM da API, prazo e IPC
binário limitado. O supervisor confirma a saída antes de devolver a capacidade.
O worker não carrega o contexto Spring e recebe um ambiente reduzido. O limite
de heap não limita toda a memória nativa, e ActiveProcessorCount orienta a JVM
sem impor uma cota de CPU. Os workers executam com a mesma identidade do serviço.

O provider privado supervisiona escrita, force, publicação e verificação em JVM
filha com heap de 128 MiB e prazo de quatro segundos. A publicação usa hard link
no mesmo filesystem e exige suporte a essa operação. Um publicador concorrente
não é sobrescrito. O rollback libera o lock do digest mesmo quando ainda
há I/O físico residual. Esse processo conhece apenas o digest e os bytes da
tentativa. Pode publicar pela primeira vez o mesmo conteúdo imutável e não
pode marcar READY, criar vínculo, alterar cobrança ou finalizar o upload.
O catálogo confirma READY somente após resposta válida do escritor. Uma falha
pode deixar stage privado ou objeto canônico sem READY para investigação.
A leitura normal do asset fica fora desse mecanismo.

No MySQL, as mutações de Storage combinam timeout transacional de cinco segundos
com timeout de rede JDBC de até sete segundos na conexão emprestada. A aquisição
no pool usa a configuração da aplicação. A leitura bloqueada em MySQL real
resulta em exceção de socket e o pool continua utilizável depois da falha.
O driver pode gastar mais de sete segundos no conjunto de leitura e tratamento
de falha. O timeout também não comprova um teto para escrita de socket, rollback
ou commit. Resposta perdida no commit continua sujeita à recuperação de resultado.

A migration V27 adiciona uma identidade aleatória de execução ao permit. Cada
readmissão recebe uma identidade nova e toda mutação verifica o titular atual.
Uma compensação tardia não altera a execução readmitida. Para chave idempotente,
compensação confirmada antes de iniciar o provider pode liberar a cobrança e
readmitir a mesma operação. Intents já registrados só podem ser reaproveitados
se o novo plano reproduzir os mesmos digests e tamanhos na mesma ordem. Depois
de iniciar o provider, a cobrança incerta e a reserva da chave são preservadas.
