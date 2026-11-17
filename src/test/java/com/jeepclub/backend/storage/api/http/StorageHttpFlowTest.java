package com.jeepclub.backend.storage.api.http;

import com.jeepclub.backend.iam.identity.api.module.*;
import com.jeepclub.backend.storage.core.repository.MediaGovernance;
import com.jeepclub.backend.storage.core.domain.model.ModerationDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import com.jayway.jsonpath.JsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"storage.media.enabled=true","storage.legacy-backfill.enabled=true"}) @AutoConfigureMockMvc @ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StorageHttpFlowTest {
    static final Path ROOT;
    static {try {ROOT=Files.createTempDirectory("storage-http-fixture-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {r.add("storage.local.root-directory",() -> ROOT.toString());}
    @Autowired MockMvc mvc; @Autowired UserRegistration users; @Autowired UserQuery query;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean MediaGovernance governance;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.jeepclub.backend.storage.core.repository.BlobCatalog blobs;
    @Autowired com.jeepclub.backend.storage.core.repository.MediaAdministration administration;
    @Autowired com.jeepclub.backend.storage.core.application.MediaReferenceService references;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired com.jeepclub.backend.storage.core.repository.LegacyBackfill backfill;
    @Autowired com.jeepclub.backend.storage.core.repository.MediaCatalogRepository catalog;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.jeepclub.backend.storage.core.repository.LegacyMediaSource legacySource;

    @Test void legacyHttpResponsesAreSanitizedAndDeniedRequestsNeverReadProvider() throws Exception {
        var owner=users.registerAndAuthenticate(new UserRegistrationData("Legacy owner",null,"legacy-owner@example.com","11144477735",null,null,null,Instant.now()),"Password@123");
        var other=users.registerAndAuthenticate(new UserRegistrationData("Legacy other",null,"legacy-other@example.com","93541134780",null,null,null,Instant.now()),"Password@123");
        long actor=query.findByCpf("11144477735").orElseThrow().id();
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(12,9,BufferedImage.TYPE_INT_RGB),"png",out);byte[] bytes=out.toByteArray();
        String key="images/2026/11/12/550e8400-e29b-41d4-a716-446655440002.png";
        Path file=ROOT.resolve(key);Files.createDirectories(file.getParent());Files.write(file,bytes);
        var audit=new com.jeepclub.backend.storage.core.domain.model.MediaAuditContext(com.jeepclub.backend.storage.core.domain.enums.MediaActorKind.SYSTEM,null,Instant.now(),null);
        long media=backfill.apply(key,audit).mediaId();
        var ref=catalog.recordReference(new com.jeepclub.backend.storage.core.domain.model.MediaReference(null,media,
            com.jeepclub.backend.storage.core.domain.enums.MediaContext.IDENTITY_PROFILE,Long.toString(actor),"PHOTO",null,
            com.jeepclub.backend.storage.core.domain.enums.MediaOrigin.LEGACY_IMPORT,audit.occurredAt(),null,null,null,null,null),audit);
        String url="/storage/media/"+media+"/references/"+ref.referenceId()+"/variants/MASTER";
        org.mockito.Mockito.clearInvocations(legacySource);
        assertSanitized(url,owner.accessToken(),404);
        assertSanitized(url,other.accessToken(),404);
        org.mockito.Mockito.verify(legacySource,org.mockito.Mockito.never()).read(org.mockito.ArgumentMatchers.anyString());
    }
    private void assertSanitized(String url,String token,int expected) throws Exception {
        var result=mvc.perform(get(url).header("Authorization","Bearer "+token)).andExpect(status().is(expected))
            .andExpect(header().string("Cache-Control","private, no-store")).andReturn();
        String json=result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(json).doesNotContain("storageKey","sha256","SHA-256","namespace","private-images","fixture","secret path");
    }
    @Test void openApiPublishesAdministrativeDetailAndFilters() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$['paths']['/admin/storage/media/{mediaId}']['get']['responses']['401']").exists())
            .andExpect(jsonPath("$['paths']['/admin/storage/media/{mediaId}']['get']['responses']['403']").exists())
            .andExpect(jsonPath("$['paths']['/admin/storage/media/{mediaId}']['get']['responses']['404']").exists())
            .andExpect(jsonPath("$['paths']['/admin/storage/media/{mediaId}/references/{referenceId}/preview/{code}']['get']['x-required-permissions']")
                .value(org.hamcrest.Matchers.hasItem("STORAGE_MEDIA_PREVIEW")))
            .andExpect(jsonPath("$['paths']['/admin/storage/media']['get']['parameters'][*].name")
                .value(org.hamcrest.Matchers.hasItems("uploader","owner","createdFrom","createdBefore","context","mime","usage","moderation","physicalState","afterId","limit")));
    }
    @Test void authenticatedOwnerReadsMasterWhileOtherUsersAndModerationCannotBypassIt() throws Exception {
        var owner=users.registerAndAuthenticate(new UserRegistrationData("Storage fixture",null,"storage-fixture@example.com","52998224725",null,null,null,Instant.now()),"Password@123");
        var other=users.registerAndAuthenticate(new UserRegistrationData("Other fixture",null,"other-storage-fixture@example.com","16899535009",null,null,null,Instant.now()),"Password@123");
        var image=new BufferedImage(80,60,BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        long rejectedActor=query.findByCpf("52998224725").orElseThrow().id();
        var jpeg=new ByteArrayOutputStream();ImageIO.write(image,"jpeg",jpeg);
        byte[] png=bytes.toByteArray();byte[] jpegBytes=jpeg.toByteArray();
        byte[] validWebp=dev.matrixlab.webp4j.WebPCodec.encodeLosslessImage(image);
        byte[] corruptPng=png.clone();corruptPng[29]^=1;
        byte[] corruptWebp=validWebp.clone();corruptWebp[4]^=1;
        byte[] corruptJpeg=jpegBytes.clone();corruptJpeg[2]=0;
        long assetCount=jdbc.queryForObject("select count(*) from storage_media_assets",Long.class);
        long blobCount=jdbc.queryForObject("select count(*) from storage_blobs",Long.class);
        var invalidFiles=java.util.List.of(
            new MockMultipartFile("file","empty.png","image/png",new byte[0]),
            new MockMultipartFile("file","missing.png",null,png),
            new MockMultipartFile("file","fake.png","image/png",new byte[]{1,2,3}),
            new MockMultipartFile("file","corrupt.png","image/png",corruptPng),
            new MockMultipartFile("file","corrupt.jpg","image/jpeg",corruptJpeg),
            new MockMultipartFile("file","corrupt.webp","image/webp",corruptWebp),
            new MockMultipartFile("file","truncated.png","image/png",java.util.Arrays.copyOf(png,png.length-1)),
            new MockMultipartFile("file","truncated.jpg","image/jpeg",java.util.Arrays.copyOf(jpegBytes,jpegBytes.length-1)),
            new MockMultipartFile("file","truncated.webp","image/webp",java.util.Arrays.copyOf(validWebp,validWebp.length-1)));
        for(var invalidFile:invalidFiles) {
            String error=mvc.perform(multipart("/storage/media/profile").file(invalidFile).header("Authorization","Bearer "+owner.accessToken()))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVALID_MEDIA_REQUEST")).andExpect(jsonPath("$.timestamp").exists())
                .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(error).doesNotContain("storageKey","sha256","Exception","fixture","private-images");
        }
        mvc.perform(multipart("/storage/media/profile").header("Authorization","Bearer "+owner.accessToken()))
            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("INVALID_MEDIA_REQUEST")).andExpect(jsonPath("$.timestamp").exists());
        var unreadable=new MockMultipartFile("file","unreadable.png","image/png",png) {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {throw new java.io.IOException("secret path /private-images/fixture");}
        };
        String readError=mvc.perform(multipart("/storage/media/profile").file(unreadable).header("Authorization","Bearer "+owner.accessToken()))
            .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE")).andExpect(jsonPath("$.timestamp").exists())
            .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(readError).doesNotContain("secret path","private-images","fixture","Exception");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from storage_media_assets",Long.class)).isEqualTo(assetCount);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from storage_blobs",Long.class)).isEqualTo(blobCount);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from storage_upload_permits where actor_user_id=? and completed=false",Long.class,rejectedActor)).isZero();
        byte[] exact=StorageMultipartSocketTest.paddedPng();
        mvc.perform(multipart("/storage/media/profile").file(new MockMultipartFile("file","exact.png","image/png",exact))
            .header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isCreated());
        long acceptedAssets=jdbc.queryForObject("select count(*) from storage_media_assets",Long.class);
        long acceptedBlobs=jdbc.queryForObject("select count(*) from storage_blobs",Long.class);
        long acceptedPermits=jdbc.queryForObject("select count(*) from storage_upload_permits",Long.class);
        mvc.perform(multipart("/storage/media/profile").file(new MockMultipartFile("file","oversize.png","image/png",java.util.Arrays.copyOf(exact,exact.length+1)))
            .header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from storage_media_assets",Long.class)).isEqualTo(acceptedAssets);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from storage_blobs",Long.class)).isEqualTo(acceptedBlobs);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from storage_upload_permits",Long.class)).isEqualTo(acceptedPermits);
        mvc.perform(multipart("/storage/media/profile").file(new MockMultipartFile("file","photo.png","image/jpeg",bytes.toByteArray()))
            .header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        byte[] webp=dev.matrixlab.webp4j.WebPCodec.encodeLosslessImage(image);
        mvc.perform(multipart("/storage/media/profile").file(new MockMultipartFile("file","photo.webp","image/webp",webp))
            .header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isCreated());
        mvc.perform(multipart("/storage/media/profile").file(new MockMultipartFile("file","photo.webp","image/webp",java.util.Arrays.copyOf(webp,webp.length-1)))
            .header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isBadRequest());
        var options=new MockMultipartFile("options","","application/json",
            "{\"crops\":{\"AVATAR_SMALL\":{\"x\":0,\"y\":0,\"width\":0.75,\"height\":1},\"CARD_PORTRAIT\":{\"x\":0,\"y\":0,\"width\":0.375,\"height\":1}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String response=mvc.perform(multipart("/storage/media/profile").file(new MockMultipartFile("file","photo.png","image/png",bytes.toByteArray()))
            .file(options)
            .header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isCreated())
            .andExpect(jsonPath("$.storageKey").doesNotExist()).andExpect(jsonPath("$.sha256").doesNotExist()).andReturn().getResponse().getContentAsString();
        long media=((Number)JsonPath.read(response,"$.mediaId")).longValue();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from storage_upload_permits u join storage_upload_planned_blobs p on p.permit_id=u.id where u.catalog_asset_id=?",Long.class,media)).isGreaterThan(0);
        org.assertj.core.api.Assertions.assertThat((Object)JsonPath.read(response,"$.referenceId")).isNull();
        long actor=query.findByCpf("52998224725").orElseThrow().id();
        mvc.perform(post("/storage/media/"+media+"/references").header("Authorization","Bearer "+owner.accessToken())
            .contentType("application/json").content("{\"context\":\"IDENTITY_PROFILE\",\"consumerId\":\""+actor+"\",\"role\":\"PHOTO\"}"))
            .andExpect(status().isNotFound());
        // Explicit reference fixture: this is resolver coverage, not consumer integration proof.
        long ref=new org.springframework.transaction.support.TransactionTemplate(manager).execute(s -> references.associate(actor,media,
            com.jeepclub.backend.storage.core.domain.enums.MediaContext.IDENTITY_PROFILE,Long.toString(actor),"PHOTO"));
        var avatar=mvc.perform(get("/storage/media/"+media+"/references/"+ref+"/variants/AVATAR_SMALL")
            .header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var decoded=avatar.length>12 && avatar[8]=='W' && avatar[9]=='E' && avatar[10]=='B' && avatar[11]=='P'
            ? dev.matrixlab.webp4j.WebPCodec.decodeImage(avatar) : ImageIO.read(new java.io.ByteArrayInputStream(avatar));
        org.assertj.core.api.Assertions.assertThat(decoded.getWidth()).isEqualTo(decoded.getHeight());
        var card=mvc.perform(get("/storage/media/"+media+"/references/"+ref+"/variants/CARD_PORTRAIT")
            .header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var cardImage=card.length>12 && card[8]=='W' && card[9]=='E' && card[10]=='B' && card[11]=='P'
            ? dev.matrixlab.webp4j.WebPCodec.decodeImage(card) : ImageIO.read(new java.io.ByteArrayInputStream(card));
        org.assertj.core.api.Assertions.assertThat(cardImage.getWidth()).isLessThan(cardImage.getHeight());
        var invalid=new MockMultipartFile("options","","application/json",
            "{\"crops\":{\"AVATAR_SMALL\":{\"x\":0.9,\"y\":0,\"width\":0.5,\"height\":1}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/storage/media/profile").file(new MockMultipartFile("file","photo.png","image/png",bytes.toByteArray()))
            .file(invalid).header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isBadRequest());
        String url="/storage/media/"+media+"/references/"+ref+"/variants/MASTER";
        mvc.perform(get(url).header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get(url).header("Authorization","Bearer "+other.accessToken())).andExpect(status().isNotFound());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        url="/storage/media/"+media+"/references/"+ref+"/variants/AVATAR_SMALL";
        try {
            org.mockito.Mockito.doReturn(new byte[]{1,2,3}).when(blobs).read(org.mockito.ArgumentMatchers.any());
            assertSanitized(url,owner.accessToken(),503);
            org.mockito.Mockito.doThrow(new com.jeepclub.backend.shared.storage.exception.StorageOperationException(
                com.jeepclub.backend.shared.storage.exception.StorageOperationException.Operation.READ,new java.io.IOException("secret path /private-images/fixture")))
                .when(blobs).read(org.mockito.ArgumentMatchers.any());
            assertSanitized(url,owner.accessToken(),503);
        } finally { org.mockito.Mockito.doCallRealMethod().when(blobs).read(org.mockito.ArgumentMatchers.any()); }
        mvc.perform(get("/admin/storage/media").header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/storage/media/"+media)).andExpect(status().isUnauthorized());
        var adminUser=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("catalog-operator")
            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("STORAGE_MEDIA_READ"));
        var adminResponse=mvc.perform(get("/admin/storage/media/"+media).with(adminUser)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","private, no-store"))
            .andExpect(jsonPath("$.mediaId").value(media)).andExpect(jsonPath("$.variants").isArray())
            .andExpect(jsonPath("$.references").isArray()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(adminResponse).doesNotContain("storageKey","checksum","sha256","namespace","internalNote","sourceRecordId","subjectId");
        var refs=mvc.perform(get("/admin/storage/media/"+media+"/references").with(adminUser)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(refs).doesNotContain("consumerId");
        mvc.perform(get("/admin/storage/media/9223372036854775807").with(adminUser)).andExpect(status().isNotFound());
        mvc.perform(get("/admin/storage/media").param("uploader",Long.toString(actor)).param("owner",Long.toString(actor))
            .param("usage","ACTIVE").param("physicalState","PRESENT").with(adminUser)).andExpect(status().isOk());
        var authority=new org.springframework.security.core.authority.SimpleGrantedAuthority("STORAGE_MEDIA_PREVIEW");
        var principal=new com.jeepclub.backend.platform.security.principal.UserPrincipal(actor,1L,"fixture",Instant.now().plusSeconds(300));
        var auth=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal,"N/A",java.util.List.of(authority));
        String preview="/admin/storage/media/"+media+"/references/"+ref+"/preview/AVATAR_SMALL?purpose=MODERATION_REVIEW";
        mvc.perform(get(preview).with(adminUser)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/storage/media/"+media).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
            .andExpect(status().isForbidden());
        mvc.perform(get(preview).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get("/admin/storage/media/"+media+"/references/"+ref+"/preview/MASTER?purpose=MODERATION_REVIEW")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))).andExpect(status().isNotFound());
        mvc.perform(get("/admin/storage/media/"+media+"/references/"+ref+"/preview/AVATAR_SMALL")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth))).andExpect(status().isBadRequest());
        long auditCount=administration.events(media,0,100).stream().filter(e -> e.operation().equals("ADMIN_PREVIEW")).count();
        org.assertj.core.api.Assertions.assertThat(auditCount).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(administration.events(media,0,100).stream().filter(e -> e.operation().equals("ADMIN_PREVIEW")))
            .singleElement().satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.reason()).isEqualTo("MODERATION_REVIEW"));
        long otherId=query.findByCpf("16899535009").orElseThrow().id();
        var otherPrincipal=new com.jeepclub.backend.platform.security.principal.UserPrincipal(otherId,1L,"other fixture",Instant.now().plusSeconds(300));
        var otherAuth=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(otherPrincipal,"N/A",java.util.List.of(authority));
        mvc.perform(get(preview).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(otherAuth)))
            .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(administration.events(media,0,100).stream().filter(e -> e.operation().equals("ADMIN_PREVIEW"))).hasSize(1);
        var reconcileAuthority=new org.springframework.security.core.authority.SimpleGrantedAuthority("STORAGE_MEDIA_RECONCILE");
        var reconcileAuth=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal,"N/A",java.util.List.of(reconcileAuthority));
        var otherReconcile=org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(otherPrincipal,"N/A",java.util.List.of(reconcileAuthority));
        String page=mvc.perform(post("/admin/storage/media/reconciliation").param("limit","1")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(reconcileAuth)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String cursor=JsonPath.read(page,"$.nextBlobCursor");
        org.assertj.core.api.Assertions.assertThat(cursor).matches("[0-9a-f-]{36}");
        org.assertj.core.api.Assertions.assertThat(page).doesNotContain("sha256","digest","storageKey");
        mvc.perform(post("/admin/storage/media/reconciliation").param("limit","1").param("blobCursor",cursor)
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(otherReconcile))).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/storage/media/reconciliation").param("limit","1").param("blobCursor",cursor)
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(reconcileAuth))).andExpect(status().isOk());
        org.mockito.Mockito.doThrow(new IllegalStateException("secret path /private-images/fixture and private note")).when(governance)
            .previewAccess(org.mockito.ArgumentMatchers.eq(media),org.mockito.ArgumentMatchers.eq(ref),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        try {
            String failure=mvc.perform(get(preview).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","private, no-store"))
                .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(failure).doesNotContain("secret path","private-images","fixture","private note");
        } finally {org.mockito.Mockito.doCallRealMethod().when(governance).previewAccess(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());}
        governance.moderate(new ModerationDecision(media,null,ModerationDecision.State.BLOCKED,ModerationDecision.Reason.PRIVACY,"internal fixture",actor,Instant.now()));
        String auditUrl="/admin/storage/media/"+media+"/audit";
        mvc.perform(get(auditUrl).with(adminUser)).andExpect(status().isForbidden());
        var auditor=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("audit-operator")
            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("STORAGE_MEDIA_AUDIT_READ"));
        String events=mvc.perform(get(auditUrl).with(auditor)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","private, no-store"))
            .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(events).contains("BLOCKED","PRIVACY","ADMIN_PREVIEW")
            .doesNotContain("internal fixture","internalNote","storageKey","sha256","consumerId","actorUserId");
        mvc.perform(get(url).header("Authorization","Bearer "+owner.accessToken()).header("If-None-Match","*"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.reasonCode").value("CONTENT_UNAVAILABLE"));
        mvc.perform(head(url).header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isNotFound());
        governance.moderate(new ModerationDecision(media,null,ModerationDecision.State.VISIBLE,ModerationDecision.Reason.RESTORED,"restore fixture",actor,Instant.now()));
        mvc.perform(get(url).header("Authorization","Bearer "+owner.accessToken())).andExpect(status().isOk());
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(s -> references.dissociate(actor,media,ref));
        String denied=mvc.perform(get(url).header("Authorization","Bearer "+owner.accessToken()))
            .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(denied).doesNotContain("storageKey","sha256","private-images");
    }
}
