# BACK-628 — evidências de consumidores e variantes

## Inventário de referência

Consulta somente de código em `Jeep-Club/front-end-web-application`, branch
`develop`, SHA `5e366e79015be2472a16dfbe5d435528884f637a`. Contratos
backend conferidos a partir de `4ca93e106b637a50e15b619d83949991fad8c176`.
As notas abaixo da matriz preservam o histórico das medições anteriores.

| Superfície e owner | HTTP e legado | Variante, dimensão e enquadramento | ACL/cache e prova |
| --- | --- | --- | --- |
| Navbar e perfil, Identity | `GET /identity/me`, `CurrentUserResponseDTO.profilePhotoStorageKey`. Avatar 36 CSS px em `common/avatar/index.tsx`. | `AVATAR_SMALL` 144² e `PROFILE_PREVIEW` 320². Crop quadrado explícito. | Resolução por referência contextual, respeitando privacidade e moderação. `MediaImageProcessingAdapterTest` cobre tamanho e ausência de upscale. |
| Perfil administrativo, Identity | `GET /identity/admin/users/{userId}`, `AdminUserResponseDTO.profilePhotoStorageKey`. | `PROFILE_PREVIEW` 320² candidata. | Permissão administrativa do owner e revalidação. Medida da superfície e testes de integração pendentes. |
| Carteirinha e exportação, Identity | Foto de perfil legada, `MembershipCard.tsx` e `card-export/exportCard.ts`. | `CARD_PORTRAIT` até 4096² com crop vertical independente. Foto de 240×345 no SVG de 856×540. Exportação a 300 DPI precisa de aproximadamente 283×408 px nessa caixa. | Foto privada por referência, `no-store`. Processador testa crops independentes. Prova visual da exportação fica na integração. |
| Feed e galeria, Publications | `GET /publications/feed`, `PublicationFeedDTO`, galeria legada ordenada por posição. | `GALLERY` até 1600×1200, sem crop implícito. `PublicationPhotoCarousel.tsx` declara 720 CSS px e `object-contain`. `THUMBNAIL` até 240×180 candidata para miniatura administrativa. | Revalidar ACL e moderação da publicação. Testar cache e equivalência do legado na integração. |
| Comentários e autores, Publications e Identity | `GET /publications/{publicationId}/comments`, autoria por `authorUserId`. `PublicationCommentAuthor.tsx` admite nome/foto e tem fallback por ID. | `AVATAR_SMALL` 144², crop quadrado sob autorização contextual. | Resolver o lote de autores sem N+1 e sem ampliar acesso por meio da foto. Testes do contrato de leitura e do lote pendentes. |
| Capas, galerias e participantes, Publications (Events) e Identity | `GET /events/{id}/participants`, `EventParticipantResponseDTO.profilePhotoStorageKey`. `FeedSidebar.tsx` usa capa de 64×56 CSS px. | `THUMBNAIL` 240×180 na lista, `GALLERY` até 1600×1200 no detalhe, `AVATAR_SMALL` 144² para participantes. Preservar proporção do arquivo. | Aplicar membritude no contexto do evento quando exigida e revalidar ACL da referência. Testes de integração pendentes. |
| Galeria e capa, Vehicles | `GET /vehicles/detail/member/{vehicleId}`, `DetailResponseDTO.images`, `photo` como alias da principal. `VehiclePhotoCarousel.tsx` usa até cinco fotos. | `THUMBNAIL` 240×180 e `GALLERY` 1600×1200, sem aplicar um crop comum a caixas diferentes. | Ownership e permissão de Vehicles. Testar ordenação, alias legado e cache na integração. |
| Foto, Tools | `/tools`, `/admin/tools`, `ToolResponseDTO.photoStorageKey`. `ToolDetailModal.tsx` exibe 16:9 com `object-cover`. | `THUMBNAIL` 240×180 e `GALLERY` 1600×1200. Crop de paisagem a confirmar no contrato consumidor. | ACL de Tools com revalidação, sem presumir acesso público. Testes de integração pendentes. |
| Foto de dependente, Dependents | `GET /dependents/{id}/photo`, `DependentResponseDTO`. `DependentPhoto.tsx` declara 48×48 CSS px. | `AVATAR_SMALL` 144² cobre até 3× e `PROFILE_PREVIEW` 320² atende a exibição maior. Crop quadrado explícito. | Titularidade, estado e `no-store`. Prova do novo acesso por referência fica na integração. |
| Históricos, Storage e owners de referência | Histórico de referências e auditoria do catálogo. Legado permanece sob o contrato de origem. | Preservar variante e referência históricas. | Referência encerrada não concede acesso operacional. `MediaResolutionServiceTest` e `StorageUploadFlowTest` exercitam o catálogo e a resolução. |

D06–D08 governam dimensões, processamento e crops. D03, D09 e D13
governam autorização, moderação e cache. D12 e D14 limitam a leitura do legado
e do frontend. A precedência registrada em BACK-626 #10524 aplica também as
retificações BACK-547 #10368, #10434, #10445 e #10521. Billing está fora do MVP.

Quando a MASTER precisa reduzir resolução para caber no teto de bytes, o
processador compara os pixels disponíveis de cada crop com os necessários para
o preset, limitados à resolução disponível antes da redução. Para a carteirinha
usa a caixa de exportação de 283×408 px como piso, mantendo o teto de 4096²
para a derivada. Fontes menores continuam sem upscale. A rejeição é anterior
à persistência. Esse critério mede resolução disponível, não fidelidade
perceptual de fotos nem adequação de todas as superfícies responsivas.

Inventário parcial de código, sem execução ou homologação visual. Backend: base
`f66839f6f6004f7c267d554e5857eca924ccceca`. Frontend: repositório
`Jeep-Club/frontend-application`, `develop` em
`b9f9a5e97a3ced2b5572ec393bfc832670bb952a`, consultado por objeto Git.
Nova inspeção em `main` `0080f504216bdba55a3e14f1f96d8a1c06228d73`:
checkout limpo, repositório `Jeep-Club/frontend-application`. A branch `master`
solicitada não existe nas refs remotas consultadas; `main` e `develop` foram
identificadas sem checkout ou alteração. A inspeção não é homologação visual.
Os tamanhos abaixo são medidas de layout ou coordenadas SVG, não os pixels
efetivos do arquivo. O DPR e o tamanho final de exportação precisam de validação
na integração. O cliente ainda usa contratos legados.

A referência remota do repositório indicado no Jira,
`Jeep-Club/front-end-web-application`, foi reconfirmada nesta rodada.
`master` está ausente. O HEAD remoto aponta para `develop` em
`badbe6bc8d781d92ac484b8942d148f98884aa99`; `main` permanece em
`0080f504216bdba55a3e14f1f96d8a1c06228d73`. As medidas históricas abaixo
continuam vinculadas aos SHAs acima do inventário original.

| Superfície | Evidência frontend e contrato conhecido | Uso e recomendação técnica | Acesso e pendência |
| --- | --- | --- | --- |
| Navbar, comentários e perfil | `common/avatar/index.tsx:10`; `PublicationCommentAuthor.tsx`; Identity `CurrentUserResponseDTO.profilePhotoStorageKey` | Avatar padrão 36×36 CSS, `object-cover`; `AVATAR_SMALL` até 144×144 atende 3x; cartão de perfil 96×96 pode usar `PROFILE_PREVIEW` até 320×320. Sem upscale. | URL legada; ACL depende do endpoint. O payload de comentários tem somente `authorUserId`; nome e foto exigem integração de Publications com Identity por consulta em lote, evitando N+1. |
| Perfil administrativo | `AdminUserResponseDTO.profilePhotoStorageKey` | O mesmo arquivo legado atende a superfícies diferentes; tamanho exibido ainda não medido. | Integração futura deve manter permissão administrativa e não expor chave no novo contrato. |
| Carteirinha | `profile/tabs/personal/MembershipCard.tsx:90`; `utils/card-export/exportCard.ts` | Foto 240×345 no SVG 856×540; exportação 85,6×53,98 mm a 300 DPI, aproximadamente 1011×638 px: foto proporcionalmente cerca de 283×408 px. Crop vertical é independente do avatar. | `CARD_PORTRAIT` é opt-in e preserva até a dimensão da MASTER, sem upscale; validar fidelidade e formato na integração de exportação. |
| Dependentes | `profile/tabs/dependents/DependentPhoto.tsx:5`; `CreateDependentModal.tsx`; `DependentResponseDTO` | Padrão 48×48 CSS, `object-cover`; `AVATAR_SMALL` 144 cobre 3x. Modal de 112 px pode usar `PROFILE_PREVIEW` 320, próximo a 3x. | Endpoint próprio, `no-store`; manter proteção e ownership, sem usar cache compartilhado. |
| Publicações | `feed/PublicationPhotoCarousel.tsx`; `admin/publications/PublicationGalleryFields.tsx`; `PublicationFeedDTO` | Feed 16:9, `object-contain`, `sizes` até 720 CSS px; preservar enquadramento sem crop implícito. Miniatura administrativa 72×72 CSS usa `object-cover`. | Campos legados e proxy; variantes e integração de ACL são futuras. Tamanhos responsivos e peso requerem medição. |
| Veículos | `VehiclesTabContent.tsx`, `VehiclePhotoCarousel.tsx`, `ViewVehicleModal.tsx`; `DetailResponseDTO.images` | Lista em 16:9, edição exibe 192×128 CSS, inscrição em evento 80×64; `object-cover`. Não há um único crop adequado a todas as caixas. | Até cinco imagens no contrato legado; `photo` é alias da principal. Permissões e integração por contexto permanecem próprias de Vehicles. |
| Ferramentas | `IncludeToolModal.tsx`, `ToolDetailModal.tsx`; `ToolResponseDTO.photoStorageKey` | Preview 16:9, `object-cover`; editor usa crop paisagem. Largura responsiva precisa ser medida. | `/tools` e `/admin/tools` mantêm chave legada; não inferir acesso público. |
| Eventos e participantes | `feed/index.tsx`, `FeedSidebar.tsx`, `EventParticipants.tsx`; `EventResponseDTO`, `EventParticipantResponseDTO` | Capa do feed 16:9 `object-cover`; sidebar 64×56 CSS; avatares da sidebar 28×28 CSS. Detalhe compartilha galeria `object-contain`. | `/events` aplica membritude quando pertinente. Contratos ainda não selecionam variantes; integração e ACL por contexto pendentes. |

### Contratos e limites por owner

| Owner | Superfície HTTP legada confirmada | Categoria e integração futura |
| --- | --- | --- |
| Identity | `GET /identity/me` e `GET /identity/admin/users/{userId}` expõem `profilePhotoStorageKey` nos DTOs de usuário. | MASTER de até 2 MiB. Cliente deve enviar fonte e crops independentes para perfil e carteirinha. |
| Publications | `GET /publications/feed` e `GET /publications/{publicationId}/comments`. | MASTER de até 5 MiB para imagens da publicação. Nome e avatar de autor dependem de projeção futura em lote de Identity, autorizada no contexto de leitura do comentário. |
| Publications (Events) | `GET /events/{id}/participants` retorna `EventParticipantResponseDTO` com `name` e `profilePhotoStorageKey`. | MASTER de até 5 MiB para galeria do evento. A foto de participante é de Identity e usa a categoria de perfil. |
| Vehicles | `GET /vehicles/detail/member/{vehicleId}` e `PUT /vehicles/gallery/member/{vehicleId}`. | MASTER de até 5 MiB. Seleção de variante e eventual crop por superfície ficam para a integração. |
| Tools | `/tools` e `/admin/tools` usam `ToolResponseDTO.photoStorageKey`. | MASTER de até 5 MiB. O cliente deve indicar enquadramento específico caso o contrato futuro exija crop paisagem. |
| Dependents | `GET /dependents/{id}/photo` e `PATCH /dependents/{id}/photo`. | MASTER de até 2 MiB. Preservar titularidade, estado e `no-store` ao migrar. |

Os tetos da tabela são do pipeline novo. A entrada recebe JPEG, PNG ou WebP
até 10 MiB. Derivados usam WebP preferencialmente e JPEG/PNG como fallback.
Na integração, ler variantes via referência vigente, policy do contexto e
moderação. Validar cache privado e as regras de privacidade por superfície.
Para históricos, preservar referências encerradas e trilha sem reutilizar
a referência como autorização operacional. Billing permanece fora do MVP.

Para Identity e Dependents, os candidatos existentes são `AVATAR_SMALL` e
`PROFILE_PREVIEW`. Validar crop quadrado explícito, densidade e acesso
contextual nas duas superfícies. Na carteirinha, validar separadamente o
crop vertical de `CARD_PORTRAIT` e a exportação. Para Publications, Events,
Vehicles e Tools, `THUMBNAIL` e `GALLERY` são os presets atuais da categoria
geral. A seleção por superfície, os crops e os testes de cache, autorização
e compatibilidade com os campos legados dependem da integração. No feed,
verificar a preservação do enquadramento completo; nos comentários,
verificar a consulta em lote do autor e a autorização do avatar.

O editor `common/input/file/image-crop-dialog.tsx` exporta largura de 1024 px
(quadrado ou 16:9), mas este é um crop cliente que descarta pixels e não é uma
resolução aprovada para a MASTER. O novo pipeline preserva a fonte visual e
aceita crops independentes; não consegue recuperar pixels já descartados.

Os presets atuais são `AVATAR_SMALL` 144×144 (antes 64×64; versão V2), `PROFILE_PREVIEW` 320×320,
`THUMBNAIL` 240×180, `GALLERY` 1600×1200 e `CARD_PORTRAIT` até 4096×4096
quando solicitado. São limites de dimensão em pixels, não tamanhos CSS
nem DPI. A escolha de 144 apoia-se nas miniaturas de 36 e 48 CSS px; `PROFILE_PREVIEW`
mantém o papel de exibição maior. Os demais presets permanecem provisórios até
medições e integração. Faltam validação visual da exportação e testes de ACL/cache por superfície. As
decisões D06–D08 e D13 continuam sendo as fontes de produto; a integração dos
consumidores pertence às Stories posteriores.

Sem crop explícito o limite de 144×144 preserva a proporção: a fixture
800×400 produz avatar de 144×72. Com crop quadrado explícito na mesma fonte,
produz 144×144. Isso preserva a regra de não recortar automaticamente; a
integração futura deve enviar crop quadrado se precisar dessa densidade nos
dois eixos. Cobertura: `rectangularSourceIsNotImplicitlyCroppedForAvatar`.

## Medição da alteração de avatar

`MediaImageProcessingAdapterTest.avatarPresetMatchesHighDensityAndDoesNotUpscale`
gera fixture sintética com gradiente RGB de 800×800, PNG de 422.864 bytes,
e compara os mesmos pixels com `AVATAR_SMALL` anterior e novo. No Windows 10
19045, Ryzen 7 5700G (8 núcleos/16 threads), 32 GiB RAM e Microsoft OpenJDK
17.0.18, o resultado foi WebP 64×64/270 bytes/PSNR 43,44 dB antes e WebP
144×144/508 bytes/PSNR 44,82 dB depois. PSNR é medido contra o gradiente
analítico na resolução de cada saída; os valores não demonstram qualidade
visual de fotos nem são comparáveis como teste de fidelidade de uma mesma
resolução. O processamento completo da amostra com o novo preset levou 60 ms
na execução observada; não é benchmark de latência da API.

Na mesma fixture, crop independente `CARD_PORTRAIT` produziu WebP de 400×575
e 2.322 bytes, suficiente em pixels para a caixa de aproximadamente 283×408
no PNG exportado atual, quando a fonte e o crop fornecem resolução suficiente.
Fontes menores não sofrem upscale. A integração de exportação e sua qualidade
visual seguem pendentes; não há evidência que justifique reduzir o limite
atual da variante de carteirinha.

## Divergência a resolver no pipeline

`MediaResolutionService.resolve` recusa a representação de papel MASTER antes
de ler qualquer provider. Essa regra cobre a rota comum e a prévia administrativa;
variantes derivadas continuam sujeitas à policy contextual, à moderação e à
validade da referência. Não existe ainda contrato de acesso privado para reedição:
uma operação futura deve definir sua própria autorização explícita e preservar
essas verificações. O endpoint legado de imagens permanece independente.

O semáforo de `SynchronousImageProcessor` limita somente processamento na JVM.
O limite em `MediaUploadService` abrange leitura do payload, reserva SQL,
gravação física, transação e conclusão, com duas operações simultâneas por
processo; a recepção multipart anterior ao controller e outras instâncias não
estão sob esse limite. Não há cancelamento ponta a ponta demonstrado para codec ou I/O já em
execução. Um deadline verificado apenas após essas chamadas não interromperia o
trabalho. A reserva de `BlobCatalogAdapter` é transacionada antes do I/O e
preservada para reconciliação; testes de falhas e concorrência em banco real são
necessários para sustentar as garantias de custódia e retry.
