# BACK-628 — evidências de consumidores e variantes

Inventário parcial de código, sem execução ou homologação visual. Backend: base
`f66839f6f6004f7c267d554e5857eca924ccceca`. Frontend: repositório
`Jeep-Club/frontend-application`, `develop` em
`b9f9a5e97a3ced2b5572ec393bfc832670bb952a`, consultado por objeto Git.
Os tamanhos abaixo são medidas de layout ou coordenadas SVG, não os pixels
efetivos do arquivo. O DPR e o tamanho final de exportação precisam de validação
na integração. O cliente ainda usa contratos legados.

| Superfície | Evidência frontend e contrato conhecido | Uso e recomendação técnica | Acesso e pendência |
| --- | --- | --- | --- |
| Navbar, comentários e perfil | `common/avatar/index.tsx`; `PublicationCommentAuthor.tsx`; Identity `CurrentUserResponseDTO.profilePhotoStorageKey` | Avatar padrão 36×36 CSS, `object-cover`; 72×72 serviria 2x, 108×108 3x, sem upscale. `AVATAR_SMALL` atual é 64×64 e requer decisão após inventário completo. | URL legada; ACL depende do endpoint. O payload de comentários tem somente `authorUserId`; nome e foto exigem integração de Publications com Identity por consulta em lote, evitando N+1. |
| Perfil administrativo | `AdminUserResponseDTO.profilePhotoStorageKey` | O mesmo arquivo legado atende a superfícies diferentes; tamanho exibido ainda não medido. | Integração futura deve manter permissão administrativa e não expor chave no novo contrato. |
| Carteirinha | `profile/tabs/personal/MembershipCard.tsx` | Caixa da foto 240×345 no SVG, proporção 16:23; exportação incorpora os bytes. O tamanho em pixels para impressão depende do tamanho físico e DPI. Crop vertical deve ser independente do avatar. | `CARD_PORTRAIT` é opt-in; validar fidelidade e formato na integração de exportação. |
| Dependentes | `profile/tabs/dependents/DependentPhoto.tsx`; `DependentResponseDTO` | Padrão 48×48 CSS, `object-cover`; candidato quadrado 96–144 px para 2x–3x. | Endpoint próprio, `no-store`; manter proteção e ownership, sem usar cache compartilhado. |
| Publicações | `feed/PublicationPhotoCarousel.tsx`; `admin/publications/PublicationGalleryFields.tsx`; `PublicationFeedDTO` | Feed 16:9, `object-contain`, `sizes` até 720 CSS px; preservar enquadramento sem crop implícito. Miniatura administrativa 72×72 CSS usa `object-cover`. | Campos legados e proxy; variantes e integração de ACL são futuras. Tamanhos responsivos e peso requerem medição. |
| Veículos | `VehiclesTabContent.tsx`, `VehiclePhotoCarousel.tsx`, `ViewVehicleModal.tsx`; `DetailResponseDTO.images` | Lista em 16:9, edição exibe 192×128 CSS, inscrição em evento 80×64; `object-cover`. Não há um único crop adequado a todas as caixas. | Até cinco imagens no contrato legado; `photo` é alias da principal. Permissões e integração por contexto permanecem próprias de Vehicles. |
| Ferramentas | `IncludeToolModal.tsx`, `ToolDetailModal.tsx`; `ToolResponseDTO.photoStorageKey` | Preview 16:9, `object-cover`; editor usa crop paisagem. Largura responsiva precisa ser medida. | `/tools` e `/admin/tools` mantêm chave legada; não inferir acesso público. |
| Eventos e participantes | `feed/index.tsx`, `FeedSidebar.tsx`, `EventParticipants.tsx`; `EventResponseDTO`, `EventParticipantResponseDTO` | Capa do feed 16:9 `object-cover`; sidebar 64×56 CSS; avatares da sidebar 28×28 CSS. Detalhe compartilha galeria `object-contain`. | `/events` aplica membritude quando pertinente. Contratos ainda não selecionam variantes; integração e ACL por contexto pendentes. |

O editor `common/input/file/image-crop-dialog.tsx` exporta largura de 1024 px
(quadrado ou 16:9), mas este é um crop cliente que descarta pixels e não é uma
resolução aprovada para a MASTER. O novo pipeline preserva a fonte visual e
aceita crops independentes; não consegue recuperar pixels já descartados.

Os presets atuais são `AVATAR_SMALL` 64×64, `PROFILE_PREVIEW` 320×320,
`THUMBNAIL` 240×180, `GALLERY` 1600×1200 e `CARD_PORTRAIT` até 4096×4096
quando solicitado. São limites de dimensão em pixels, não tamanhos CSS
nem DPI. Nenhuma alteração de preset foi justificada integralmente por esta
 inspeção parcial. Faltam medições de bytes e
qualidade, validação da exportação e testes de ACL/cache por superfície. As
decisões D06–D08 e D13 continuam sendo as fontes de produto; a integração dos
consumidores pertence às Stories posteriores.
