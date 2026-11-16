package hu.unideb.inf.shakespeare;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import hu.unideb.inf.shakespeare.repository.PlayRepository;

/** Tests of the QUERY method (RFC 10008) with XPath expressions. */
@SpringBootTest
@AutoConfigureMockMvc
class QueryApiTest {

    private static final String MACBETH = "/plays/macbeth";

    private static final Map<String, String> PROBLEM = Map.of("p", "urn:ietf:rfc:7807");

    private static final Map<String, String> RESULTS = Map.of("r", PlayRepository.RESULTS_NAMESPACE);

    @Autowired
    private MockMvc mvc;

    private ResultActions query(String uri, String xpath) throws Exception {
        return mvc.perform(request(HttpMethod.QUERY, uri)
                .contentType(MediaType.TEXT_PLAIN)
                .content(xpath));
    }

    // --- Results ------------------------------------------------------------

    @Test
    void elementsAreReturnedInAResultsElement() throws Exception {
        query(MACBETH + "/acts/1/scenes/3", "SPEECH[SPEAKER = 'BANQUO']/LINE[1]")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(header().string("Accept-Query", "text/plain"))
                .andExpect(xpath("/r:results/@type", RESULTS).string("node-set"))
                .andExpect(xpath("count(/r:results/LINE)", RESULTS).number(12.0))
                .andExpect(xpath("/r:results/LINE[1]", RESULTS).string("How far is't call'd to Forres? What are these"));
    }

    @Test
    void theTargetResourceIsTheContextItem() throws Exception {
        query(MACBETH + "/acts/3", "string(TITLE)")
                .andExpect(status().isOk())
                .andExpect(xpath("/r:results/@type", RESULTS).string("string"))
                .andExpect(xpath("/r:results", RESULTS).string("ACT III"));
    }

    @Test
    void numbers() throws Exception {
        query(MACBETH, "count(//SPEECH[SPEAKER = 'MACBETH'])")
                .andExpect(status().isOk())
                .andExpect(xpath("/r:results/@type", RESULTS).string("number"))
                .andExpect(xpath("/r:results", RESULTS).string("146"));
    }

    @Test
    void booleans() throws Exception {
        query(MACBETH + "/acts/1/scenes/1", "count(SPEECH) > 5")
                .andExpect(status().isOk())
                .andExpect(xpath("/r:results/@type", RESULTS).string("boolean"))
                .andExpect(xpath("/r:results", RESULTS).string("true"));
    }

    @Test
    void textNodesAreWrapped() throws Exception {
        query(MACBETH + "/acts/3", "TITLE/text()")
                .andExpect(status().isOk())
                .andExpect(xpath("/r:results/@type", RESULTS).string("node-set"))
                .andExpect(xpath("/r:results/r:text", RESULTS).string("ACT III"));
    }

    @Test
    void emptyNodeSet() throws Exception {
        query(MACBETH + "/acts/1/scenes/1", "SPEECH[SPEAKER = 'MACBETH']")
                .andExpect(status().isOk())
                .andExpect(xpath("/r:results/@type", RESULTS).string("node-set"))
                .andExpect(xpath("count(/r:results/node())", RESULTS).number(0.0));
    }

    @Test
    void responsesAreCacheableButHaveNoValidator() throws Exception {
        query(MACBETH + "/acts/3", "string(TITLE)")
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(header().doesNotExist(HttpHeaders.LAST_MODIFIED));
    }

    // --- Discoverability ----------------------------------------------------

    @Test
    void getResponsesAdvertiseQuery() throws Exception {
        mvc.perform(get(MACBETH + "/acts/1"))
                .andExpect(header().string("Accept-Query", "text/plain"));
        mvc.perform(get("/plays"))
                .andExpect(header().doesNotExist("Accept-Query"));
    }

    @Test
    void optionsListsQuery() throws Exception {
        mvc.perform(options(MACBETH + "/acts/1"))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("QUERY")));
        mvc.perform(options("/plays"))
                .andExpect(header().string(HttpHeaders.ALLOW, not(containsString("QUERY"))));
    }

    @Test
    void queryIsNotSupportedOnThePlaysCollection() throws Exception {
        query("/plays", "count(*)")
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void corsPreflight() throws Exception {
        mvc.perform(options(MACBETH + "/acts/1")
                        .header(HttpHeaders.ORIGIN, "https://example.org")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "QUERY"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("QUERY")));
    }

    // --- Errors -------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "for $i in SPEECH return $i",           // XQuery/XPath 2.0+ FLWOR expression
        "<x/>",                                 // XQuery node constructor
        "delete node SPEECH[1]",                // XQuery Update
        "(SPEECH[1], SPEECH[2])",               // XPath 2.0+ sequence
        "doc('/etc/passwd')",                   // XPath 2.0+ function
        "unparsed-text('/etc/passwd')",         // XPath 3.0+ function
        "file:read-text('/etc/passwd')",        // BaseX extension function
        "false() and proc:system('ls')",        // extension function, even if never called
        " "                                     // empty expression
    })
    void onlyXPath10IsAccepted(String xpath) throws Exception {
        query(MACBETH, xpath)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_XML))
                .andExpect(xpath("/p:problem/p:status", PROBLEM).number(400.0));
    }

    @Test
    void undeclaredVariable() throws Exception {
        query(MACBETH, "$x")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_XML));
    }

    @Test
    void missingQueryContent() throws Exception {
        mvc.perform(request(HttpMethod.QUERY, MACBETH).contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedQueryMediaType() throws Exception {
        mvc.perform(request(HttpMethod.QUERY, MACBETH)
                        .contentType("application/xquery")
                        .content("count(//SPEECH)"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void nonexistentTarget() throws Exception {
        query(MACBETH + "/acts/6", "TITLE")
                .andExpect(status().isNotFound());
        query("/plays/othello", "TITLE")
                .andExpect(status().isNotFound());
    }

}
