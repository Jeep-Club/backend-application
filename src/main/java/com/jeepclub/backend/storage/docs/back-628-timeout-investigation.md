# BACK-628 — limites do timeout síncrono

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

## Quota e falhas de gravação

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


## Rastreabilidade da tentativa

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


## Compensação anterior ao armazenamento

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


## Alternativas para F-02

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


## Limite de transações de admissão

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

## Limites adicionais do catálogo — BACK-639

As transações isoladas de reserva e bloqueio de blob e a transação de conclusão
do catálogo também configuram timeout Spring de cinco segundos. Esse limite é
aplicado aos statements JDBC quando suportado pelo gerenciador e driver; não é
deadline para `putVerified`, `force`, movimentação, codec ou commit. O lock do
blob continua durante o I/O para preservar serialização e integridade da
deduplicação; uma transação expirada pode continuar ocupando recursos enquanto
o provider não retorna. A exceção original da reserva é preservada caso a
verificação posterior de colisão também falhe. O teste de contenção MySQL deve
ser executado no SHA entregue antes de atribuir garantia ao caminho de blob.
