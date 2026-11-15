# BACK-637 — limites HTTP verificados

O controller do Storage limita a leitura a 10 MiB + 1 byte e rejeita arquivo
declarado maior que 10 MiB. O servlet possui limite global de 10 MiB por arquivo
e 30 MiB por requisição, compartilhado com endpoints legados. O teste
`StorageMultipartSocketTest` usa servidor real, multipart enviado por socket e
PNG válido de exatamente 10.485.760 bytes: recebe 201. Um arquivo com um byte
adicional recebe 413 com `application/problem+json` e não cria asset, blob ou
permit adicional. Payload com assinatura inválida recebe 400 e não deixa asset,
blob ou permit aberto. `StorageMultipartMySqlIT` repete esses cenários em MySQL.

O limite total é exercitado com quatro partes de 7,5 MiB, cada uma abaixo do
teto individual; o overhead multipart ultrapassa 30 MiB. Em execução local com
HttpClient/Tomcat, o servidor registrou 413 mas o cliente recebeu IOException
durante a leitura da resposta (`chunked transfer encoding, state: READING_LENGTH`).
O teste reconhece explicitamente essa limitação de transporte e verifica que
não houve persistência; também valida 413 e ProblemDetail quando uma resposta
completa é recebida. Portanto, a entrega de corpo RFC 9457 ao cliente para todo
request acima de 30 MiB **não está comprovada**. Não se alterou configuração
global do Tomcat nem limites de endpoints legados.

Os testes MockMvc de `StorageHttpFlowTest` exercitam também o limite do
controller: 10 MiB válidos recebem 201 e 10 MiB + 1 recebe 400; o segundo caso
não chega à reserva. Verificam MIME ausente ou incompatível,
assinatura inválida, arquivos truncados e corrompidos, stream com falha, erro
sanitizado e ausência de asset/blob ou permit aberto para as entradas rejeitadas.
MockMvc não substitui o parser multipart do servlet. Faltam cobertura completa
de todos os cenários em MySQL e comprovação de erro estruturado quando o
transporte é encerrado antes de completar a resposta.
