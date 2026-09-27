package org.voxrox.mailbackend.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;

/**
 * Requests Spring MVC turns away before a controller method runs. Each has a
 * status of its own, and without a handler for it the catch-all answered 500
 * and logged a CRITICAL stack trace for what is the caller's mistake. A
 * controller of the test's own keeps the cases independent of the API's.
 */
@WebMvcTest(controllers = FrameworkRequestRejectionTest.ProbeController.class, excludeAutoConfiguration = {
        SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
// Component scanning skips a test class's nested classes
// (TestTypeExcludeFilter).
@Import(FrameworkRequestRejectionTest.ProbeController.class)
class FrameworkRequestRejectionTest {

    @Autowired
    private MockMvc mockMvc;

    // ApiKeyFilter is a @Component the slice wants to build even with filters off.
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/items/{id}")
        String item(@PathVariable Long id) {
            return "item";
        }

        @PostMapping(value = "/items", consumes = MediaType.APPLICATION_JSON_VALUE)
        String create(@RequestBody String body) {
            return "created";
        }

        @GetMapping(value = "/export", produces = "text/vcard")
        String export() {
            return "BEGIN:VCARD";
        }

        @GetMapping("/header")
        String header(@RequestHeader("X-Probe") String probe) {
            return probe;
        }

        // The mapping has no {other}: a mistake on our side, not the caller's.
        @GetMapping("/mismatch/{id}")
        String mismatch(@PathVariable("other") String other) {
            return other;
        }
    }

    @Test
    @DisplayName("A path variable of the wrong type is a 400 naming the parameter")
    void typeMismatchIs400() throws Exception {
        mockMvc.perform(get("/probe/items/abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("id"))
                .andExpect(jsonPath("$.messageKey").value("error.validation.typeMismatch"));
    }

    @Test
    @DisplayName("A method the path does not map is a 405 with an Allow header")
    void unmappedMethodIs405() throws Exception {
        mockMvc.perform(post("/probe/items/1")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.messageKey").value("error.request.methodNotAllowed"));
    }

    @Test
    @DisplayName("A body of a type the endpoint does not consume is a 415 with an Accept header")
    void unsupportedMediaTypeIs415() throws Exception {
        mockMvc.perform(post("/probe/items").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.messageKey").value("error.request.unsupportedMediaType"));
    }

    @Test
    @DisplayName("An Accept the endpoint cannot produce is a 406")
    void notAcceptableIs406() throws Exception {
        mockMvc.perform(get("/probe/export").accept(MediaType.APPLICATION_JSON)).andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.errorCode").value("NOT_ACCEPTABLE"));
    }

    @Test
    @DisplayName("A path variable the mapping does not declare stays a 500 — the server's mistake")
    void missingPathVariableStaysInternal() throws Exception {
        mockMvc.perform(get("/probe/mismatch/1")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("A missing required header is a 400")
    void missingHeaderIs400() throws Exception {
        mockMvc.perform(get("/probe/header")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.messageKey").value("error.badRequest.binding"));
    }
}
