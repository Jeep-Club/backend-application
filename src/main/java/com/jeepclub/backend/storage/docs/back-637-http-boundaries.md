# BACK-637 — limites HTTP verificados

O controller do Storage rejeita parte vazia ou maior que 10 MiB e lê até o
tamanho informado pelo multipart mais um byte. Leitura curta ou excedente
recebe 400 antes de validar MIME ou decodificar; exceção de I/O recebe 503
sanitizado. O servlet possui limite global de 10 MiB por arquivo
e 30 MiB por requisição, compartilhado com endpoints legados. O teste
`StorageMultipartSocketTest` usa servidor real, multipart enviado por socket e
PNG válido de exatamente 10.485.760 bytes: recebe 201. Um arquivo com um byte
adicional recebe 413 com `application/problem+json` e não cria asset, blob ou
permit adicional. Payload com assinatura inválida recebe 400 e não deixa asset,
blob ou permit aberto. `StorageMultipartMySqlIT` repete esses cenários em MySQL.

No pipeline, JPEG e PNG também são rejeitados se as dimensões retornadas pelo
decoder divergirem das dimensões verificadas antes da decodificação. Essa
checagem já existia para WebP. Validar a regressão com imagens válidas,
corrompidas e no limite de pixels antes da homologação.

O limite total é exercitado com quatro partes de 7,5 MiB, cada uma abaixo do
teto individual; o overhead multipart ultrapassa 30 MiB. Em execução local com
HttpClient/Tomcat, o servidor registrou 413 mas o cliente recebeu IOException
durante a leitura da resposta (`chunked transfer encoding, state: READING_LENGTH`).
O teste reconhece explicitamente essa limitação de transporte e verifica que
não houve persistência; também valida 413 e ProblemDetail quando uma resposta
completa é recebida. Portanto, a entrega de corpo RFC 9457 ao cliente para todo
request acima de 30 MiB **não está comprovada**. Não se alterou configuração
global do Tomcat nem limites de endpoints legados.

Em outro ensaio por socket, o cliente escreve integralmente o multipart com
arquivo de 10 MiB + 1 antes de ler: recebe 413 com `application/problem+json`,
`code` e `timestamp`. O cenário de limite total é distinto e pode encerrar a
conexão durante a leitura. Os negativos por socket também comparam variantes
e arquivos físicos, além de assets, blobs catalogados e permits.

Para `/storage/media/profile`, erros de arquivo inválido, parte ausente e
limite multipart recebido pelo Spring usam `ApiProblemFactory`, com `code` e
`timestamp` no envelope transversal. O handler global conserva o comportamento
anterior para os demais endpoints. Essa padronização não garante entrega do
corpo caso o cliente ou o container encerrem o transporte durante o envio.

Os testes MockMvc de `StorageHttpFlowTest` exercitam também o limite do
controller: 10 MiB válidos recebem 201 e 10 MiB + 1 recebe 400; o segundo caso
não chega à reserva. Verificam MIME ausente ou incompatível,
assinatura inválida, arquivos truncados e corrompidos, stream com falha, erro
sanitizado e ausência de asset/blob ou permit aberto para as entradas rejeitadas.
MockMvc não substitui o parser multipart do servlet. Faltam cobertura completa
de todos os cenários em MySQL e comprovação de erro estruturado quando o
transporte é encerrado antes de completar a resposta.
