package hu.unideb.inf.shakespeare;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import hu.unideb.inf.shakespeare.web.BookmarkController;

/**
 * Tests of the bookmarks. Every Spring application context of the tests has
 * its own, new database (see src/test/resources/config/application.yaml), but
 * the tests of this class share it, so each test creates its own bookmarks.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookmarkApiTest {

    private static final String SCENE = "/plays/macbeth/acts/1/scenes/3";

    private static final String ALICE_BOOKMARKS = "/users/alice/bookmarks";

    private static final Map<String, String> PROBLEM = Map.of("p", "urn:ietf:rfc:7807");

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor alice() {
        return httpBasic("alice", "wonderland");
    }

    private static RequestPostProcessor bob() {
        return httpBasic("bob", "builder");
    }

    private static String link(String uri, String rel) {
        return "<" + uri + ">; rel=\"" + rel + "\"";
    }

    /** Bookmarks a resource as alice, and returns the URI of the new bookmark. */
    private String bookmark(String target, String note) throws Exception {
        return mvc.perform(post(target + "/bookmark").with(alice())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(note))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
    }

    private static String id(String bookmarkUri) {
        return bookmarkUri.substring(bookmarkUri.lastIndexOf('/') + 1);
    }

    // --- Creating bookmarks --------------------------------------------------

    @Test
    void createBookmark() throws Exception {
        mvc.perform(post(SCENE + "/bookmark").with(alice())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("The prophecy of the witches"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern(ALICE_BOOKMARKS + "/\\d+")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(xpath("/bookmark/target").string(SCENE))
                .andExpect(xpath("/bookmark/note").string("The prophecy of the witches"));
    }

    @Test
    void theNoteMayBeEmpty() throws Exception {
        mvc.perform(post("/plays/macbeth/bookmark").with(alice()).contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isCreated())
                .andExpect(xpath("/bookmark/note").string(""));
    }

    @Test
    void resourcesLinkToWhereTheyCanBeBookmarked() throws Exception {
        mvc.perform(get(SCENE))
                .andExpect(header().stringValues(HttpHeaders.LINK,
                        hasItem(link(SCENE + "/bookmark", BookmarkController.BOOKMARK_REL))));
        mvc.perform(get("/plays"))
                .andExpect(header().stringValues(HttpHeaders.LINK,
                        not(hasItem(containsString(BookmarkController.BOOKMARK_REL)))));
    }

    @Test
    void bookmarkingANonexistentResource() throws Exception {
        mvc.perform(post("/plays/macbeth/acts/1/scenes/9/bookmark").with(alice())
                        .contentType(MediaType.TEXT_PLAIN).content("?"))
                .andExpect(status().isNotFound())
                .andExpect(xpath("/p:problem/p:detail", PROBLEM).string("There is no /plays/macbeth/acts/1/scenes/9."));
    }

    @Test
    void theNoteMustBePlainText() throws Exception {
        mvc.perform(post(SCENE + "/bookmark").with(alice())
                        .contentType(MediaType.APPLICATION_XML).content("<note/>"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void bookmarkUrisSupportPostOnly() throws Exception {
        mvc.perform(get(SCENE + "/bookmark"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")));
    }

    // --- Authentication and authorization ------------------------------------

    @Test
    void bookmarkingRequiresAuthentication() throws Exception {
        mvc.perform(post(SCENE + "/bookmark").contentType(MediaType.TEXT_PLAIN).content("?"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Basic ")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_XML))
                .andExpect(xpath("/p:problem/p:status", PROBLEM).number(401.0));
    }

    @Test
    void wrongPassword() throws Exception {
        mvc.perform(get(ALICE_BOOKMARKS).with(httpBasic("alice", "builder")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bookmarksArePrivate() throws Exception {
        String uri = bookmark(SCENE, "Alice's");
        mvc.perform(get(ALICE_BOOKMARKS))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ALICE_BOOKMARKS).with(bob()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_XML));
        mvc.perform(get(uri).with(bob()))
                .andExpect(status().isForbidden());
        mvc.perform(put(uri).with(bob()).contentType(MediaType.TEXT_PLAIN).content("Bob's"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(uri).with(bob()))
                .andExpect(status().isForbidden());
        mvc.perform(get(uri).with(alice()))
                .andExpect(xpath("/bookmark/note").string("Alice's"));
    }

    // --- Reading bookmarks ---------------------------------------------------

    @Test
    void listOfBookmarks() throws Exception {
        String uri = bookmark(SCENE, "In the list");
        mvc.perform(get(ALICE_BOOKMARKS).with(alice()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(xpath("/bookmarks/bookmark[@id = '%s']/note", id(uri)).string("In the list"))
                .andExpect(header().stringValues(HttpHeaders.LINK, hasItems(
                        link(ALICE_BOOKMARKS, "self"),
                        link(uri, "item"))));
    }

    @Test
    void bookmark() throws Exception {
        String uri = bookmark(SCENE, "A bookmark");
        mvc.perform(get(uri).with(alice()))
                .andExpect(status().isOk())
                .andExpect(xpath("/bookmark/@id").string(id(uri)))
                .andExpect(xpath("/bookmark/target").string(SCENE))
                .andExpect(xpath("/bookmark/note").string("A bookmark"))
                .andExpect(header().stringValues(HttpHeaders.LINK, hasItems(
                        link(uri, "self"),
                        link(ALICE_BOOKMARKS, "collection"))))
                // Added by Spring Security, since the controller sets no Cache-Control:
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void nonexistentBookmark() throws Exception {
        mvc.perform(get(ALICE_BOOKMARKS + "/999999").with(alice()))
                .andExpect(status().isNotFound())
                .andExpect(xpath("/p:problem/p:detail", PROBLEM).string("User 'alice' has no bookmark 999999."));
        mvc.perform(put(ALICE_BOOKMARKS + "/999999").with(alice()).contentType(MediaType.TEXT_PLAIN).content("?"))
                .andExpect(status().isNotFound());
    }

    // --- Changing bookmarks --------------------------------------------------

    @Test
    void replaceTheNote() throws Exception {
        String uri = bookmark(SCENE, "Old note");
        mvc.perform(put(uri).with(alice()).contentType(MediaType.TEXT_PLAIN).content("New note"))
                .andExpect(status().isNoContent());
        mvc.perform(get(uri).with(alice()))
                .andExpect(xpath("/bookmark/note").string("New note"))
                .andExpect(xpath("/bookmark/target").string(SCENE));
    }

    @Test
    void deleteABookmark() throws Exception {
        String uri = bookmark(SCENE, "To be deleted");
        mvc.perform(delete(uri).with(alice()))
                .andExpect(status().isNoContent());
        mvc.perform(get(uri).with(alice()))
                .andExpect(status().isNotFound());
        mvc.perform(delete(uri).with(alice()))
                .andExpect(status().isNotFound());
        mvc.perform(get(ALICE_BOOKMARKS).with(alice()))
                .andExpect(header().stringValues(HttpHeaders.LINK, not(hasItem(link(uri, "item")))));
    }

    @Test
    void optionsListsTheAllowedMethods() throws Exception {
        String uri = bookmark(SCENE, "Options");
        mvc.perform(options(uri).with(alice()))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("PUT")))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("DELETE")))
                .andExpect(header().string(HttpHeaders.ALLOW, not(containsString("POST"))));
    }

    // --- CORS ----------------------------------------------------------------

    @Test
    void corsPreflightWithAuthorization() throws Exception {
        // Preflight requests carry no credentials, but they are answered.
        mvc.perform(options(ALICE_BOOKMARKS + "/1")
                        .header(HttpHeaders.ORIGIN, "https://example.org")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("PUT")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsStringIgnoringCase("authorization")));
    }

    @Test
    void crossOriginRequestsCanReadTheLocation() throws Exception {
        mvc.perform(post(SCENE + "/bookmark").with(alice())
                        .header(HttpHeaders.ORIGIN, "https://example.org")
                        .contentType(MediaType.TEXT_PLAIN).content("CORS"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Location")));
    }

}
