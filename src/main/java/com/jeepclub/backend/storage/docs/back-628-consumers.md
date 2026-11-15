# BACK-628 — evidências de consumidores e variantes

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
