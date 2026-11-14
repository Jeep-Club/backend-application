# Storage — catálogo e fronteiras

O [inventário parcial de consumidores da BACK-628](back-628-consumers.md) registra
as evidências de layout e as decisões técnicas ainda necessárias para presets.

Storage é o bounded context proprietário da identidade lógica de mídias, suas representações, referências e auditoria. A organização de camadas e contratos segue a [arquitetura global](../../../../../../../../docs/architecture/README.md). A capacidade física é provida por `shared.storage` e `platform.storage`; veja o [guia do provider](../../platform/storage/docs/README.md). Provider, namespace e key são localização técnica, não autorização nem identidade de negócio.

## Referências e estado

Na `develop` `d33af0a08ccd301d6dd4d686ab484292dc5d8027`, a fundação BACK-549 foi integrada pelo [PR #131](https://github.com/Jeep-Club/backend-application/pull/131), merge `5b1faf2de5ebdede4ee4e392b700354dacef2c17`. A migration `src/main/resources/db/migration/V13__storage_catalog_foundation.sql` é histórica e não deve ser alterada. A branch da épica contém trabalho posterior do [PR #139](https://github.com/Jeep-Club/backend-application/pull/139), que não está integrado em `develop` e não é evidência de produção. Nesta página, as afirmações sobre fundação integrada referem-se ao SHA de `develop` acima; funcionalidades posteriores exigem verificação própria.

As decisões D01–D15 e adendos, especialmente [BACK-547 #10453](https://jeep-club-developers.atlassian.net/browse/BACK-547?focusedCommentId=10453), definem o contrato de produto e autorizam desenvolvimento backend não destrutivo. Elas não significam que todos os fluxos estejam integrados. Leituras antigas de aprovação pendente na documentação da fundação são históricas e não reabrem a decisão de produto. Consulte [ADR 001](../../../../../../../../docs/architecture/adr/001-storage-catalog-foundation.md) e [fundação BACK-549](../../../../../../../../docs/architecture/back-549-storage-foundation.md) para desenho e limitações da entrega original.

## Modelo integrado

* `MediaAsset` possui identidade própria, origem, finalidade e proveniência. Uploader, owner e sujeito são independentes; `UNKNOWN` e IDs nulos indicam ausência de informação, não uma identidade inventada.
* `MediaVariant` descreve uma representação; `MASTER` é conceitualmente privada e pode diferir dos bytes de origem. Localização, hash e metadados internos não são credenciais nem devem compor contratos comuns de usuário.
* `MediaReference` registra contexto, consumidor e intervalo de uso. Um asset pode ter vários vínculos atuais ou históricos. Encerrar um vínculo preserva seu histórico; o identificador do consumidor é independente do uploader, owner e sujeito.
* `MediaAuditEvent` registra alterações junto ao catálogo. As gravações do adapter e seus eventos participam da mesma transação; versão otimista protege alterações de asset e referência.

`core/repository/MediaCatalogRepository` é uma porta interna de persistência, não uma API de autorização. As tabelas V13 não concedem acesso por key, hash, existência de asset ou metadados. A associação a um agregado e o acesso a conteúdo exigem validação do contexto proprietário e direitos aplicáveis. Consumidores não acessam entidades ou repositórios internos de outro módulo; usam contratos próprios em `api.module` ou portas e adapters apropriados. OpenAPI é a fonte da superfície HTTP efetivamente existente.

Na fundação integrada, o catálogo não implementa upload, associação autorizada de consumidores, acesso a bytes nem ACL. Assim, não demonstra que upload sem referência fique privado, que uma reassociação seja autorizada ou que não exista expiração de 24/72 horas em um fluxo de upload. A decisão [BACK-547 #10368](https://jeep-club-developers.atlassian.net/browse/BACK-547?focusedCommentId=10368) revogou essa expiração; sua implementação deve ser comprovada no fluxo pertinente, não inferida da V13.

## Matriz de evidência da fundação

As evidências da fundação referem-se a `d33af0a08ccd301d6dd4d686ab484292dc5d8027`. O teste `rollbackOfReplacementPreservesExistingReferenceAndItsAuditHistory` foi adicionado no commit `a139a1bc` da BACK-627, posterior à fundação. A presença dos testes não afirma execução ou CI verde no commit desta alteração. `MediaCatalogMySqlIT` cobre Flyway/validate em MySQL e `MediaCatalogPostgresIT` usa DDL Hibernate em PostgreSQL; não há cadeia Flyway PostgreSQL equivalente. A verificação de schema vazio e histórico pertence também a `MySqlMigrationIT`. O workflow `.github/workflows/backend-ci.yml` contém `verify`, `mysql-migrations` e `publications-postgresql`; resultados e skips devem ser registrados para o SHA efetivamente validado.

| Critério | Código e evidência (SHA indicado acima ou na linha) | Estado e limite |
| --- | --- | --- |
| BACK-549 / BACK-633: catálogo, V13, integridade e auditoria | `storage/core/domain/model`, `MediaCatalogRepository`, `infra/persistence`, V13; `MediaDomainTest`, `MediaCatalogPersistenceTest`, `MediaCatalogMySqlIT`, `MediaCatalogPostgresIT` | Integrado pelo PR #131; evidência de execução do HEAD final ainda necessária. |
| BACK-634: proveniência independente, UNKNOWN e múltiplos vínculos | `MediaAsset`, `MediaReference`; `unknownProvenanceDoesNotInventUploaderOwnerOrSubject`, `uploaderOwnerAndSubjectAreIndependent`, `roundTripsUnknownAndKnownProvenanceWithoutCrossModuleForeignKeys`, `referencesSupportMultipleCurrentConsumersAndPreserveHistory` | Parte estrutural atendida; não prova autorização em consumidores. |
| BACK-634: rollback e histórico | `MediaCatalogPersistenceTest`: `rollbackIncludesAssetVariantReferencesAndAudit`, `auditInsertionFailureRollsBackTheBusinessRow` e `rollbackOfReplacementPreservesExistingReferenceAndItsAuditHistory` (adicionado na BACK-627); testes de concorrência de metadata e referência | Cobertura presente; execução no HEAD final pendente. IO físico está fora da fundação. |
| BACK-634: upload sem vínculo, não expiração e reassociação autorizada | Não implementado pela V13 integrada; há trabalho posterior no PR #139 | Bloqueado para aceite da fundação isolada; requer evidência do fluxo e integração própria. |
| BACK-635: ownership, arquitetura e fonte documental | Este documento, ADR 001, notas BACK-549 e guia `platform.storage` | Parcial até validação do SHA final e auditoria dos cenários negativos aplicáveis. |

Metadados não demonstram acesso seguro: testes negativos de permissão e estado devem exercitar o caso de uso que efetivamente fornece ou associa conteúdo. A fundação não fornece esse caso de uso. Não inferir ausência de perda por falha de IO de testes JPA sem IO; validar a custódia física na entrega correspondente.

Retenção e deduplicação globais são decisões de produto futuras para esta fundação. A retificação [#10434](https://jeep-club-developers.atlassian.net/browse/BACK-547?focusedCommentId=10434) prevê prevalência de histórico sensível no mesmo asset e respeito a holds; [#10445](https://jeep-club-developers.atlassian.net/browse/BACK-547?focusedCommentId=10445) prevê deduplicação física por bytes idênticos com identidades e direitos independentes. Nenhuma das decisões ativa purge físico, cutover legado ou integração de consumidor.

## Decisão contextual e fronteira pública — BACK-627

Referências encerradas preservam intervalo, auditoria e bytes, mas não autorizam leitura operacional. A decisão verifica o estado antes de consultar a policy ou ler qualquer provider. A transação de leitura não serializa com um encerramento concorrente: uma leitura que já observou a referência vigente pode concluir após o encerramento. A garantia é negativa para o estado observado encerrado; não há garantia de interrupção de leitura em voo ou de invalidação instantânea de snapshots.

A policy em `storage.api.module` recebe apenas contexto, identificador e papel do recurso consumidor e, para associação, o owner informado do asset. Não recebe modelos internos, key ou hash. A Identity verifica o estado administrativo e o direito sobre seu perfil; o Storage conserva a responsabilidade de validar a referência, variante e estado da mídia. Upload, associação e leitura são decisões separadas. O consumidor real deve coordenar associação e gravação do agregado na mesma transação; isso permanece na fase de integração BACK-631/BACK-656.

## Matriz de aceite da rodada BACK-627

A coluna de resultado descreve a evidência local da rodada; o CI e os gates do commit final devem ser conferidos separadamente no PR. A implementação acumulada na épica parte de `045caab9836aca1d20e743cf674248d94641fc9c`; os ajustes desta rodada pertencem aos commits posteriores da BACK-627. Nenhuma das referências a seguir comprova deploy.

| Critério Jira | Estado anterior | Código / referência | Teste | Resultado e limitação |
| --- | --- | --- | --- | --- |
| BACK-633: catálogo, V13, variantes e proveniência | PR #131 integrado | V13 e modelos em `d33af0a08ccd301d6dd4d686ab484292dc5d8027` | `MediaDomainTest`, `MediaCatalogPersistenceTest`, `MediaCatalogMySqlIT`, `MediaCatalogPostgresIT` | Fundação integrada; migrations MySQL não são Flyway PostgreSQL. |
| BACK-634: owner, uploader, sujeito, UNKNOWN | V13 integrado | `MediaAsset`, `MediaReference`, policy de upload na épica | `MediaDomainTest`, `roundTripsUnknownAndKnownProvenanceWithoutCrossModuleForeignKeys` | Evidência de domínio/JPA; não inventar proveniência legada. |
| BACK-634: múltiplos usos e rollback | V13 integrado; teste adicional no commit `a139a1bc` | `MediaCatalogRepositoryAdapter`, `MediaReferenceService` | `referencesSupportMultipleCurrentConsumersAndPreserveHistory`, `rollbackOfReplacementPreservesExistingReferenceAndItsAuditHistory`, `positionedReplacementAndAssociationRollBackTogether` | Histórico e transação interna cobertos; integração real do agregado depende de BACK-631/BACK-656. |
| BACK-634: upload avulso e custódia | Implementação na épica, não em develop | `MediaUploadService`, `MediaResolutionService`, `MediaAdministration` | `unattachedUploadHasNoActiveReferenceAndRetentionStartsAtUpload`, `authenticatedOwnerReadsMasterWhileOtherUsersAndModerationCannotBypassIt` | Upload não cria vínculo; evidência de expiração e inventário deve ser complementada por revisão do fluxo. Nenhum purge autorizado. |
| BACK-634: leitura por vínculo e ACL | Épica permitia referência encerrada | `ContextualMediaAccess`, `MediaResolutionService`, `ProfileStoragePolicy` nesta rodada | `historicalReferenceIsDeniedEvenWhenConsumerAuthorizesIt`, `endedReferenceIsDeniedBeforeReadingAnyProvider`, `StorageHttpFlowTest`, `ProfileStoragePolicyTest` | Testes focais locais; leitura iniciada antes do encerramento pode concluir, conforme limite de concorrência acima. |
| BACK-635: fronteira e rastreabilidade | README anterior misturava SHAs | `StorageConsumerPolicy`, `StoragePolicyViews`, README e ADR 001 nesta rodada | `ProfileStoragePolicyTest`, `MediaResolutionServiceTest`; inspeção de imports | Projeção sem localização ou hash; CI remoto do HEAD final pendente. |

Os gates finais e seus resultados específicos são registrados no PR; não atribuir CI de commit anterior a alterações posteriores.
