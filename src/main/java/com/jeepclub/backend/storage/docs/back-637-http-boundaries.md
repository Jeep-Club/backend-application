# BACK-637 — limites HTTP verificados

## Estado final e evidência de aceite

Código funcional auditado em `8261358c9426f75cf72ed15ee9e7797deb35f537`, [CI 45519454119](https://github.com/Jeep-Club/backend-application/actions/runs/45519454119): Maven 2.759 casos, MySQL 637 no gate principal e 637 no ensaio V+1, Publications PostgreSQL 213, sem falhas, erros ou skips nessas execuções; auditoria de migrations aprovada. As duas execuções MySQL não representam casos distintos. A validação não comprova capacidade em produção.

O pipeline usa até dois uploads admitidos por instância e um codec isolado ativo (512 MiB de heap) e um escritor ativo (128 MiB de heap) por supervisor. IPC limitado, validação pré/pós-decode (20 MP, 10.000 px por eixo), orçamento ponderado de raster e verificação de representações limitam o trabalho admitido. O supervisor mantém capacidade até saída e limpeza; o timeout do escritor segue 503 sem aguardar indefinidamente o filho. A escrita residual imutável preserva custódia e não conclui catálogo. V25–V27 preservam intents, idempotência e fencing. O limite de heap não limita RSS total e ActiveProcessorCount não constitui quota de CPU.

`StorageHttpMySqlIT` (5 casos) e `StorageMultipartMySqlIT` (1 caso parametrizado pelo fluxo herdado do socket), `StorageBlobMySqlIT` (66 casos) e `StorageProcessSupervisorTest` executaram sem falhas/skips no CI indicado. O 503 com escritor vivo, preservação de custódia, reposição de capacidade, rollback e fencing possuem regressões. Não confundir cobertura de classes e cenários exercitados com prova universal de todos os modos de falha.

A prova agregada Linux/Java 17 sob limites representativos e homologação da VPS ficam em [BACK-752](https://jeep-club-developers.atlassian.net/browse/BACK-752), pendente e obrigatória antes da ativação. O teste do limite global multipart continua com ressalva de transporte: não há prova de entrega completa do corpo RFC 9457 quando a conexão é encerrada; o arquivo não é aceito e nenhuma persistência indevida ocorre.

## Histórico — contenção do raster antes do decode

O codec reserva unidades de raster depois de inspecionar as dimensões e
antes da decodificação. O teto compartilhado equivale a duas imagens de
20 MP de profundidade comum. Amostras de 16 bits e tipos desconhecidos
pesam o dobro. As unidades ficam reservadas até o processamento retornar,
inclusive em erro. O limite é uma estimativa de custo de entrada, pois
orientação, resize, MASTER e codecs também alocam memória. A rejeição por
capacidade usa 503 antes de qualquer gravação física.

No ensaio por socket com cliente em JVM separada, servidor com heap de
512 MiB, MySQL 8.0.46 e concorrência 2, PNGs RGBA de 16 bits de 5000×4000
e aproximadamente 2 MB provocaram OOM durante resize antes do ajuste.
Com o limite ponderado, dois aquecimentos receberam 201. Entre quatro
requisições medidas houve um 201 e três 503, sem falha de transporte ou
500. P95 de 3.317 ms nessa amostra, incluindo rejeições. O diagnóstico
verificou custódia, cobrança só nos casos bem-sucedidos e liberação dos
slots. O ensaio caracteriza um caso adverso local, sem determinar o
consumo máximo de todo input permitido ou do ambiente alvo.

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

`StorageHttpFlowTest` também exercita leituras menores e maiores que o tamanho
declarado pela parte multipart. O controller rejeita ambas com 400 antes de
processar ou gravar bytes. `SynchronousImageProcessorTest` cobre a validação
comum usada para comparar largura e altura pré-decodificação com o resultado
dos decoders JPEG, PNG e WebP, além de cabeçalhos PNG acima dos limites.

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
MockMvc não substitui o parser multipart do servlet. A execução MySQL dos fluxos HTTP e socket foi confirmada no CI de encerramento indicado acima; permanece a limitação de entrega do erro estruturado quando o transporte é encerrado antes de completar a resposta.
