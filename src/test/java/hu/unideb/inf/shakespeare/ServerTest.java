package hu.unideb.inf.shakespeare;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Tests against a real embedded Tomcat, for things that MockMvc cannot check
 * (such as the size limit of response headers and the body of HEAD responses).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ServerTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void largestSceneFitsIntoTheResponseHeaders() throws Exception {
        // Act IV, Scene III has 65 speeches, the most in the play.
        HttpResponse<String> response = send("GET", "/plays/macbeth/acts/4/scenes/3");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Link"))
                .filteredOn(link -> link.endsWith("rel=\"item\""))
                .hasSize(65)
                .endsWith("</plays/macbeth/acts/4/scenes/3/speeches/65>; rel=\"item\"");
    }

    @Test
    void headResponseHasNoBody() throws Exception {
        HttpResponse<String> get = send("GET", "/plays/macbeth/acts/2");
        HttpResponse<String> head = send("HEAD", "/plays/macbeth/acts/2");
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().allValues("Link")).isEqualTo(get.headers().allValues("Link"));
    }

    @Test
    void problemDetails() throws Exception {
        HttpResponse<String> response = send("GET", "/plays/macbeth/acts/9");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).get().asString()
                .startsWith("application/problem+xml");
        assertThat(response.body()).contains("<problem xmlns=\"urn:ietf:rfc:7807\">");
    }

    @Test
    void queryOverTheWire() throws Exception {
        // Tomcat passes the QUERY method to Spring MVC, and the request content is read.
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/plays/macbeth"))
                .method("QUERY", HttpRequest.BodyPublishers.ofString("count(ACT)"))
                .header("Content-Type", "text/plain")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("tag:inf.unideb.hu,2026:restful-shakespeare/results")
                .contains("type=\"number\"")
                .contains(">5</r:results>");
    }

}
