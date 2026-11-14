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
