package com.jeepclub.backend.publications.api.http;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicationSocialFeedOpenApiIntegrationTest {
    @Autowired private MockMvc mvc;
    @Test void socialAndFeedContractsExposePermissionsAndSchemas() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes']['post']['x-required-permissions']").doesNotExist())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes']['post']['x-requires-membership']").value(true))
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes']['post']['security'][0]['bearerAuth']").isArray())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes']['post']['responses']['402']").exists())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes']['post']['responses']['503']").exists())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes']['post']['responses']['403']['description']").value("Frontend access denied when frontend access protection is enabled"))
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes/me']['delete']['x-required-permissions']").doesNotExist())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes/me']['delete']['x-requires-membership']").value(true))
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes/me']['delete']['security'][0]['bearerAuth']").isArray())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes/me']['delete']['responses']['402']").exists())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes/me']['delete']['responses']['503']").exists())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/likes/me']['delete']['responses']['403']['description']").value("Frontend access denied when frontend access protection is enabled"))
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/comments']['post']['x-required-permissions']").doesNotExist())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/comments']['post']['x-requires-membership']").value(true))
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/comments']['post']['security'][0]['bearerAuth']").isArray())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/comments']['post']['responses']['402']").exists())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/comments']['post']['responses']['503']").exists())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/comments']['post']['responses']['403']['description']").value("Frontend access denied when frontend access protection is enabled"))
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/comments']['get']['x-required-permissions']").doesNotExist())
                .andExpect(jsonPath("$['paths']['/publications/feed']['get']['x-required-permissions']").doesNotExist())
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}']['get']['x-required-permissions']").doesNotExist())
                .andExpect(jsonPath("$['paths']['/events/{id}']['get']['x-required-permissions']").doesNotExist())
                .andExpect(jsonPath("$['paths']['/services/{id}']['get']['x-required-permissions']").doesNotExist())
                .andExpect(jsonPath("$['paths']['/publications/feed']['get']['x-requires-membership']").value(true))
                .andExpect(jsonPath("$['paths']['/publications/feed']['get']['parameters'][*].name")
                        .value(hasItems("type", "types", "authorUserId", "q", "publishedFrom", "publishedTo", "page", "size", "sort")))
                .andExpect(jsonPath("$['paths']['/publications/feed']['get']['responses']['200']").exists())
                .andExpect(jsonPath("$['components']['schemas']['Item']['properties']['authorName']['type']").value(hasItems("string", "null")))
                .andExpect(jsonPath("$['components']['schemas']['Item']['properties']['authorProfilePhotoStorageKey']['type']").value(hasItems("string", "null")))
                .andExpect(jsonPath("$['components']['schemas']['Item']['properties']['author']").exists())
                .andExpect(jsonPath("$['components']['schemas']['Detail']['properties']['authorName']['type']").value(hasItems("string", "null")))
                .andExpect(jsonPath("$['components']['schemas']['EventDetails']['properties']['participantsPreview']").exists())
                .andExpect(jsonPath("$['components']['schemas']['EventDetails']['properties']['participants']").exists())
                .andExpect(jsonPath("$['components']['schemas']['ParticipantPreview']['properties']['userId']").exists())
                .andExpect(jsonPath("$['components']['schemas']['ParticipantPreview']['properties']['name']").exists())
                .andExpect(jsonPath("$['components']['schemas']['ParticipantPreview']['properties']['profilePhotoStorageKey']['type']").value(hasItems("string", "null")))
                .andExpect(jsonPath("$['components']['schemas']['CommentRequest']['properties']['text']['maxLength']").value(10000))
                .andExpect(jsonPath("$['components']['schemas']['CommentRequest']['properties']['images']['maxItems']").value(10))
                .andExpect(jsonPath("$['paths']['/publications/{publicationId}/comments']['post']['responses']['201']").exists());
    }
}
