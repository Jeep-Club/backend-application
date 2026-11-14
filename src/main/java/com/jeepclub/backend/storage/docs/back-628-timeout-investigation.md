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

`charge` antecede o primeiro `BlobCatalog.store`. Antes de chamar esse port, uma
falha comprovadamente sem escrita pode deixar permit concluído e quota cobrada,
sem registro de blob. Quando o adapter já reservou `ready=false`, uma falha do
provider pode deixar registro de custódia sem prova de bytes; após um `store`
confirmado, o blob pode permanecer íntegro e sem asset após falha posterior.
O fluxo atual não registra no permit qual dessas situações ocorreu. Portanto,
estornar automaticamente baseado apenas na exceção pode subcontabilizar dados
ou uma gravação de resultado desconhecido; a política existente é conservadora
e pode cobrar sem bytes físicos. Os testes adicionados caracterizam esses casos,
sem afirmar que o custo foi aprovado como regra de produto nem alterar quotas.
