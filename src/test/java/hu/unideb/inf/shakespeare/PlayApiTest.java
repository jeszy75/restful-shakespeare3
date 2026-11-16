package hu.unideb.inf.shakespeare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class PlayApiTest {

    private static final String MACBETH = "/plays/macbeth";

    private static final Map<String, String> PROBLEM = Map.of("p", "urn:ietf:rfc:7807");

    @Autowired
    private MockMvc mvc;

    private static String link(String uri, String rel) {
        return "<" + uri + ">; rel=\"" + rel + "\"";
    }

    // --- Representations ----------------------------------------------------

    @Test
    void plays() throws Exception {
        mvc.perform(get("/plays"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(xpath("/plays/play/@id").string("macbeth"))
                .andExpect(xpath("/plays/play/TITLE").string("The Tragedy of Macbeth"));
    }

    @Test
    void play() throws Exception {
        mvc.perform(get(MACBETH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(xpath("count(/PLAY/ACT)").number(5.0));
    }

    @Test
    void act() throws Exception {
        mvc.perform(get(MACBETH + "/acts/3"))
                .andExpect(status().isOk())
                .andExpect(xpath("/ACT/TITLE").string("ACT III"));
    }

    @Test
    void scene() throws Exception {
        mvc.perform(get(MACBETH + "/acts/1/scenes/1"))
                .andExpect(status().isOk())
                .andExpect(xpath("/SCENE/TITLE").string(containsString("A desert place")));
    }

    @Test
    void speech() throws Exception {
        mvc.perform(get(MACBETH + "/acts/1/scenes/1/speeches/1"))
                .andExpect(status().isOk())
                .andExpect(xpath("/SPEECH/SPEAKER").string("First Witch"))
                .andExpect(xpath("/SPEECH/LINE").string("When shall we three meet again"));
    }

    @Test
    void speechesAreCountedAmongSpeechesOnly() throws Exception {
        // Act III, Scene I starts with a stage direction (STAGEDIR) before the first SPEECH.
        mvc.perform(get(MACBETH + "/acts/3/scenes/1/speeches/1"))
                .andExpect(xpath("/SPEECH/SPEAKER").string("BANQUO"));
    }

    // --- Links --------------------------------------------------------------

    @Test
    void playsLinkToEachPlay() throws Exception {
        mvc.perform(get("/plays"))
                .andExpect(header().stringValues(HttpHeaders.LINK, hasItems(
                        link("/plays", "self"),
                        link(MACBETH, "item"))));
    }

    @Test
    void playLinksToItsActs() throws Exception {
        mvc.perform(get(MACBETH))
                .andExpect(header().stringValues(HttpHeaders.LINK, hasItems(
                        link(MACBETH, "self"),
                        link("/plays", "up"),
                        link(MACBETH + "/acts/1", "item"),
                        link(MACBETH + "/acts/5", "item"))));
    }

    @Test
    void firstAct() throws Exception {
        mvc.perform(get(MACBETH + "/acts/1"))
                .andExpect(header().stringValues(HttpHeaders.LINK, hasItems(
                        link(MACBETH + "/acts/1", "self"),
                        link(MACBETH, "up"),
                        link(MACBETH + "/acts/1", "first"),
                        link(MACBETH + "/acts/2", "next"),
                        link(MACBETH + "/acts/5", "last"),
                        link(MACBETH + "/acts/1/scenes/1", "item"),
                        link(MACBETH + "/acts/1/scenes/7", "item"))))
                .andExpect(header().stringValues(HttpHeaders.LINK,
                        everyItem(not(containsString("rel=\"prev\"")))));
    }

    @Test
    void lastAct() throws Exception {
        mvc.perform(get(MACBETH + "/acts/5"))
                .andExpect(header().stringValues(HttpHeaders.LINK, hasItem(link(MACBETH + "/acts/4", "prev"))))
                .andExpect(header().stringValues(HttpHeaders.LINK,
                        everyItem(not(containsString("rel=\"next\"")))));
    }

    @Test
    void nextDoesNotCrossActBoundaries() throws Exception {
        // Act I has 7 scenes.
        mvc.perform(get(MACBETH + "/acts/1/scenes/7"))
                .andExpect(header().stringValues(HttpHeaders.LINK,
                        everyItem(not(containsString("rel=\"next\"")))));
    }

    @Test
    void lastSpeechOfScene() throws Exception {
        // Act I, Scene I has 10 speeches. Speeches have no item links.
        mvc.perform(get(MACBETH + "/acts/1/scenes/1/speeches/10"))
                .andExpect(header().stringValues(HttpHeaders.LINK, hasItems(
                        link(MACBETH + "/acts/1/scenes/1", "up"),
                        link(MACBETH + "/acts/1/scenes/1/speeches/9", "prev"))))
                .andExpect(header().stringValues(HttpHeaders.LINK,
                        everyItem(not(containsString("rel=\"next\"")))))
                .andExpect(header().stringValues(HttpHeaders.LINK,
                        everyItem(not(containsString("rel=\"item\"")))));
    }

    // --- Methods ------------------------------------------------------------

    @Test
    void headReturnsTheSameHeadersAsGet() throws Exception {
        // Since Servlet 6.0, discarding the body of a HEAD response is the job of
        // the servlet container, so MockMvc still returns the body. That the body
        // is really omitted is checked against a real server in ServerTest.
        MvcResult getResult = mvc.perform(get(MACBETH + "/acts/2")).andReturn();
        MvcResult headResult = mvc.perform(head(MACBETH + "/acts/2")).andExpect(status().isOk()).andReturn();
        assertThat(headResult.getResponse().getContentType())
                .isEqualTo(getResult.getResponse().getContentType());
        assertThat(headResult.getResponse().getHeaders(HttpHeaders.LINK))
                .isNotEmpty()
                .isEqualTo(getResult.getResponse().getHeaders(HttpHeaders.LINK));
    }

    @Test
    void optionsListsTheAllowedMethods() throws Exception {
        mvc.perform(options(MACBETH + "/acts/2"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("HEAD")))
                .andExpect(header().string(HttpHeaders.ALLOW, not(containsString("POST"))));
    }

    @Test
    void postIsNotAllowed() throws Exception {
        // POST requests need authentication (see SecurityConfig): without it,
        // the answer would be 401 (Unauthorized), before the method is checked.
        mvc.perform(post(MACBETH).with(httpBasic("alice", "wonderland")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_XML));
    }

    // --- Errors -------------------------------------------------------------

    @Test
    void nonexistentAct() throws Exception {
        mvc.perform(get(MACBETH + "/acts/6"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_XML))
                .andExpect(xpath("/p:problem/p:title", PROBLEM).string("Not Found"))
                .andExpect(xpath("/p:problem/p:status", PROBLEM).number(404.0))
                .andExpect(xpath("/p:problem/p:instance", PROBLEM).string(MACBETH + "/acts/6"));
    }

    @Test
    void nonexistentSpeech() throws Exception {
        mvc.perform(get(MACBETH + "/acts/1/scenes/1/speeches/11"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonexistentPlay() throws Exception {
        mvc.perform(get("/plays/othello"))
                .andExpect(status().isNotFound())
                .andExpect(xpath("/p:problem/p:detail", PROBLEM).string("There is no play 'othello'."));
    }

    @Test
    void nonNumericActNumber() throws Exception {
        mvc.perform(get(MACBETH + "/acts/three"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_XML));
    }

    @Test
    void unknownPath() throws Exception {
        mvc.perform(get("/sonnets"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_XML));
    }

    @Test
    void jsonIsNotAvailable() throws Exception {
        mvc.perform(get(MACBETH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable());
    }

    // --- CORS ---------------------------------------------------------------

    private static final String ORIGIN = "https://example.org";

    @Test
    void crossOriginRequestsCanReadTheLinks() throws Exception {
        mvc.perform(get(MACBETH + "/acts/1").header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Link")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString("Accept-Query")));
    }

    @Test
    void crossOriginErrorResponses() throws Exception {
        mvc.perform(get("/sonnets").header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"));
    }

    @Test
    void preflightRequest() throws Exception {
        // Browsers send no preflight for simple GET requests, but they do,
        // e.g., when a script adds a custom request header.
        mvc.perform(options(MACBETH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")));
    }

    @Test
    void preflightRejectsOtherMethods() throws Exception {
        mvc.perform(options(MACBETH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isForbidden());
    }

    // --- Security headers ---------------------------------------------------

    @Test
    void securityHeaders() throws Exception {
        for (String uri : new String[] {MACBETH + "/acts/1", "/sonnets"}) {
            mvc.perform(get(uri))
                    .andExpect(header().string("Content-Security-Policy",
                            "default-src 'none'; frame-ancestors 'none'"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
    }

    // --- Caching ------------------------------------------------------------

    @Test
    void responsesCarryCacheControlAndLastModified() throws Exception {
        // shakespeare.cache.max-age is 0 by default, see CacheMaxAgeTest for other values.
        mvc.perform(get(MACBETH + "/acts/1"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(header().exists(HttpHeaders.LAST_MODIFIED));
    }

    @Test
    void conditionalRequestsAreAnsweredWithNotModified() throws Exception {
        String lastModified = mvc.perform(get(MACBETH + "/acts/1"))
                .andReturn().getResponse().getHeader(HttpHeaders.LAST_MODIFIED);
        mvc.perform(get(MACBETH + "/acts/1").header(HttpHeaders.IF_MODIFIED_SINCE, lastModified))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
        mvc.perform(head(MACBETH + "/acts/1").header(HttpHeaders.IF_MODIFIED_SINCE, lastModified))
                .andExpect(status().isNotModified());
    }

    @Test
    void outdatedCopiesAreSentAgain() throws Exception {
        mvc.perform(get(MACBETH + "/acts/1")
                        .header(HttpHeaders.IF_MODIFIED_SINCE, "Thu, 01 Jan 2004 00:00:00 GMT"))
                .andExpect(status().isOk())
                .andExpect(xpath("/ACT/TITLE").string("ACT I"));
    }

}
