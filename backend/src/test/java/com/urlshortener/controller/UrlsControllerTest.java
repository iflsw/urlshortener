package com.urlshortener.controller;

import com.urlshortener.exception.AliasGenerationException;
import com.urlshortener.model.ShortenUrlRequest;
import com.urlshortener.model.ShortenUrlResponse;
import com.urlshortener.model.UrlListItem;
import com.urlshortener.model.UrlPage;
import com.urlshortener.service.UrlShortenerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for POST /shorten. The service is mocked, so these tests
 * cover only the HTTP contract: status, headers, body and error mapping.
 */
@WebMvcTest(UrlsController.class)
class UrlsControllerTest {

    // MockMvc defaults to http://localhost:80, so the controller omits the port.
    private static final String BASE_URL = "http://localhost";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UrlShortenerService service;

    @Test
    void shorten_ValidRequest_Returns201WithBodyAndLocation() throws Exception {
        given(service.shorten(any(ShortenUrlRequest.class), eq(BASE_URL)))
                .willReturn(new ShortenUrlResponse(BASE_URL + "/abc1234", "abc1234", "https://example.com/"));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullUrl": "https://example.com"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", BASE_URL + "/abc1234"))
                .andExpect(jsonPath("$.alias").value("abc1234"))
                .andExpect(jsonPath("$.fullUrl").value("https://example.com/"))
                .andExpect(jsonPath("$.shortUrl").value(BASE_URL + "/abc1234"));
    }

    @Test
    void shorten_PassesRequestFieldsAndBaseUrlToService() throws Exception {
        given(service.shorten(any(ShortenUrlRequest.class), eq(BASE_URL)))
                .willReturn(new ShortenUrlResponse(BASE_URL + "/my-alias", "my-alias", "https://example.com/"));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullUrl": "https://example.com", "customAlias": "my-alias"}
                                """))
                .andExpect(status().isCreated());

        var captor = ArgumentCaptor.forClass(ShortenUrlRequest.class);
        verify(service).shorten(captor.capture(), eq(BASE_URL));
        assertThat(captor.getValue().getFullUrl()).isEqualTo("https://example.com");
        assertThat(captor.getValue().getCustomAlias()).isEqualTo("my-alias");
    }

    @Test
    void shorten_BlankFullUrl_Returns400WithErrorAndSkipsService() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullUrl": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("fullUrl is required."));

        verifyNoInteractions(service);
    }

    @Test
    void shorten_InvalidInputRejectedByService_Returns400WithError() throws Exception {
        given(service.shorten(any(ShortenUrlRequest.class), any()))
                .willThrow(new IllegalArgumentException("fullUrl must be a valid URL."));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullUrl": "not a url"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("fullUrl must be a valid URL."));
    }

    @Test
    void shorten_AliasTaken_Returns400WithError() throws Exception {
        // 400 per the current README contract (open question: 409 Conflict instead?)
        given(service.shorten(any(ShortenUrlRequest.class), any()))
                .willThrow(new IllegalStateException("The alias 'taken' is already taken."));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullUrl": "https://example.com", "customAlias": "taken"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The alias 'taken' is already taken."));
    }

    @Test
    void delete_ExistingAlias_Returns204() throws Exception {
        given(service.delete("gone")).willReturn(true);

        mockMvc.perform(delete("/gone"))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_UnknownAlias_Returns404() throws Exception {
        given(service.delete("missing")).willReturn(false);

        mockMvc.perform(delete("/missing"))
                .andExpect(status().isNotFound());
    }

    // ---- GET /urls (cursor paging) ----

    @Test
    void list_NoParameters_UsesDefaultSizeAndReturnsEnvelope() throws Exception {
        given(service.getPage(BASE_URL, null, 20)).willReturn(new UrlPage(
                List.of(new UrlListItem("abc1234", "https://example.com/", BASE_URL + "/abc1234")),
                "next-cursor"));

        mockMvc.perform(get("/urls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].alias").value("abc1234"))
                .andExpect(jsonPath("$.items[0].fullUrl").value("https://example.com/"))
                .andExpect(jsonPath("$.items[0].shortUrl").value(BASE_URL + "/abc1234"))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"));
    }

    @Test
    void list_PassesCursorAndSizeToService() throws Exception {
        given(service.getPage(BASE_URL, "abc", 5)).willReturn(new UrlPage(List.of(), null));

        mockMvc.perform(get("/urls").param("cursor", "abc").param("size", "5"))
                .andExpect(status().isOk());

        verify(service).getPage(BASE_URL, "abc", 5);
    }

    @Test
    void list_LastPage_NextCursorIsNull() throws Exception {
        given(service.getPage(BASE_URL, null, 20)).willReturn(new UrlPage(List.of(), null));

        mockMvc.perform(get("/urls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void list_NonNumericSize_Returns400WithErrorAndSkipsService() throws Exception {
        mockMvc.perform(get("/urls").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for parameter 'size'."));

        verifyNoInteractions(service);
    }

    @Test
    void list_ServiceRejectsInput_Returns400WithError() throws Exception {
        given(service.getPage(BASE_URL, "bad", 20)).willThrow(new IllegalArgumentException("Invalid cursor."));

        mockMvc.perform(get("/urls").param("cursor", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid cursor."));
    }

    @Test
    void shorten_AliasGenerationExhausted_Returns503WithError() throws Exception {
        given(service.shorten(any(ShortenUrlRequest.class), any())).willThrow(new AliasGenerationException(5));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullUrl": "https://example.com"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Could not generate a unique alias. Please try again."));
    }

    @Test
    void shorten_MalformedJson_Returns400WithErrorAndSkipsService() throws Exception {
        String malformedJson = "{";
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed JSON request."));

        verifyNoInteractions(service);
    }

    @Test
    void shorten_UnexpectedException_Returns500WithGenericErrorAndNoInternals() throws Exception {
        given(service.shorten(any(ShortenUrlRequest.class), any()))
                .willThrow(new RuntimeException("connection to jdbc:sqlite:/data/secret.db failed"));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullUrl": "https://example.com"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error."))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void list_UnsupportedMethod_StillHandledBySpring() throws Exception {
        // Guard: the catch-all handler must not swallow Spring's own MVC errors (405 here).
        mockMvc.perform(post("/urls"))
                .andExpect(status().isMethodNotAllowed());
    }
}
