package hu.unideb.inf.shakespeare.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import hu.unideb.inf.shakespeare.repository.PlayRepository;

/**
 * The resources of the API. Every response has {@code Link} header fields
 * (RFC 8288) with the following link relation types:
 *
 * <ul>
 * <li>{@code self}: the resource itself,</li>
 * <li>{@code up}: the parent (e.g., the act of a scene),</li>
 * <li>{@code first}, {@code prev}, {@code next}, {@code last}: the siblings
 * (e.g., the other scenes of the same act),</li>
 * <li>{@code item}: the children (e.g., the speeches of a scene),</li>
 * <li>{@link BookmarkController#BOOKMARK_REL}: where to POST to bookmark
 * the resource (plays, acts, scenes and speeches only).</li>
 * </ul>
 *
 * <p>The play, act, scene and speech resources also support the QUERY method
 * (see {@link QueryController}); their responses advertise this with the
 * {@code Accept-Query} header field (RFC 10008).
 *
 * <p>Caching: responses carry {@code Last-Modified} (the start of the
 * application, since the representations can only change with a new
 * deployment) and {@code Cache-Control}, either {@code no-cache} (caches must
 * revalidate every time) or {@code public, max-age=...}, depending on the
 * {@code shakespeare.cache.max-age} property. Spring answers conditional
 * requests ({@code If-Modified-Since}) with 304 (Not Modified) automatically.
 *
 * <p>HEAD and OPTIONS requests are handled by Spring automatically.
 */
@RestController
@RequestMapping(path = "/plays", produces = MediaType.APPLICATION_XML_VALUE)
public class PlayController {

    private final PlayRepository plays;

    /** The validator of all responses: the start of the application. */
    private final Instant lastModified = Instant.now();

    private final CacheControl cacheControl;

    /**
     * @param maxAge how long caches may reuse a response without revalidation;
     *               zero means {@code Cache-Control: no-cache}
     */
    public PlayController(PlayRepository plays,
            @Value("${shakespeare.cache.max-age}") Duration maxAge) {
        this.plays = plays;
        this.cacheControl = maxAge.isZero()
                ? CacheControl.noCache()
                : CacheControl.maxAge(maxAge).cachePublic();
    }

    @GetMapping
    public ResponseEntity<String> plays() {
        List<String> links = new ArrayList<>();
        links.add(link("/plays", "self"));
        StringBuilder xml = new StringBuilder("<plays>");
        for (String play : plays.ids()) {
            links.add(link("/plays/%s".formatted(play), "item"));
            xml.append("<play id=\"%s\">%s</play>".formatted(play, plays.query(play, "/PLAY/TITLE")));
        }
        xml.append("</plays>");
        return response(xml.toString(), links);
    }

    @GetMapping("/{play}")
    public ResponseEntity<String> play(@PathVariable String play) {
        checkPlay(play);
        String uri = "/plays/%s".formatted(play);
        List<String> links = new ArrayList<>();
        links.add(link(uri, "self"));
        links.add(link("/plays", "up"));
        addItems(links, play, "/PLAY/ACT", "%s/acts".formatted(uri));
        links.add(link(uri + "/bookmark", BookmarkController.BOOKMARK_REL));
        return queryableResponse(plays.query(play, "/PLAY"), links);
    }

    @GetMapping("/{play}/acts/{act}")
    public ResponseEntity<String> act(@PathVariable String play, @PathVariable int act) {
        Siblings acts = new Siblings(
                "/PLAY/ACT", "/plays/%s".formatted(play), "acts",
                "SCENE", "scenes");
        return part(play, acts, act);
    }

    @GetMapping("/{play}/acts/{act}/scenes/{scene}")
    public ResponseEntity<String> scene(@PathVariable String play, @PathVariable int act,
            @PathVariable int scene) {
        Siblings scenes = new Siblings(
                "/PLAY/ACT[%d]/SCENE".formatted(act),
                "/plays/%s/acts/%d".formatted(play, act), "scenes",
                "SPEECH", "speeches");
        return part(play, scenes, scene);
    }

    @GetMapping("/{play}/acts/{act}/scenes/{scene}/speeches/{speech}")
    public ResponseEntity<String> speech(@PathVariable String play, @PathVariable int act,
            @PathVariable int scene, @PathVariable int speech) {
        Siblings speeches = new Siblings(
                "/PLAY/ACT[%d]/SCENE[%d]/SPEECH".formatted(act, scene),
                "/plays/%s/acts/%d/scenes/%d".formatted(play, act, scene), "speeches",
                null, null);
        return part(play, speeches, speech);
    }

    /**
     * A set of sibling elements of the same kind (the acts of a play, the
     * scenes of an act, or the speeches of a scene), and where to find them in
     * the document and in the URI space.
     *
     * @param xpath        selects the siblings, e.g., {@code /PLAY/ACT[1]/SCENE}
     * @param parentUri    the URI of their parent, e.g., {@code /plays/macbeth/acts/1}
     * @param segment      their path segment, e.g., {@code scenes}
     * @param childElement the name of their child elements, e.g., {@code SPEECH},
     *                     or {@code null} if there are no children to link to
     * @param childSegment the path segment of the children, e.g., {@code speeches}
     */
    private record Siblings(String xpath, String parentUri, String segment,
            String childElement, String childSegment) {

        /** The n-th sibling, e.g., {@code /PLAY/ACT[1]/SCENE[n]}. */
        String elementXpath(int n) {
            return "%s[%d]".formatted(xpath, n);
        }

        /** The URI of the n-th sibling, e.g., {@code /plays/macbeth/acts/1/scenes/n}. */
        String elementUri(int n) {
            return "%s/%s/%d".formatted(parentUri, segment, n);
        }

        boolean hasChildren() {
            return childElement != null;
        }

        /** The children of the n-th sibling, e.g., {@code /PLAY/ACT[1]/SCENE[n]/SPEECH}. */
        String childrenXpath(int n) {
            return "%s/%s".formatted(elementXpath(n), childElement);
        }

        /** The base URI of the children of the n-th sibling, e.g., {@code .../n/speeches}. */
        String childrenUri(int n) {
            return "%s/%s".formatted(elementUri(n), childSegment);
        }

    }

    /**
     * Returns the {@code number}-th of the siblings (an act, a scene, or a
     * speech) with its links.
     */
    private ResponseEntity<String> part(String play, Siblings siblings, int number) {
        checkPlay(play);
        // The XPath expressions contain numbers only (no user-supplied text),
        // so they cannot be used for injection.
        int count = plays.count(play, siblings.xpath());
        if (number < 1 || number > count) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Play '%s' has no %s.".formatted(play, siblings.elementXpath(number)));
        }
        List<String> links = new ArrayList<>();
        links.add(link(siblings.elementUri(number), "self"));
        links.add(link(siblings.parentUri(), "up"));
        links.add(link(siblings.elementUri(1), "first"));
        if (number > 1) {
            links.add(link(siblings.elementUri(number - 1), "prev"));
        }
        if (number < count) {
            links.add(link(siblings.elementUri(number + 1), "next"));
        }
        links.add(link(siblings.elementUri(count), "last"));
        if (siblings.hasChildren()) {
            addItems(links, play, siblings.childrenXpath(number), siblings.childrenUri(number));
        }
        links.add(link(siblings.elementUri(number) + "/bookmark", BookmarkController.BOOKMARK_REL));
        return queryableResponse(plays.query(play, siblings.elementXpath(number)), links);
    }

    /**
     * Adds an {@code item} link for each element selected by {@code xpath};
     * the URI of the i-th one is {@code baseUri/i}.
     */
    private void addItems(List<String> links, String play, String xpath, String baseUri) {
        int count = plays.count(play, xpath);
        for (int i = 1; i <= count; i++) {
            links.add(link("%s/%d".formatted(baseUri, i), "item"));
        }
    }

    private void checkPlay(String play) {
        if (!plays.exists(play)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "There is no play '%s'.".formatted(play));
        }
    }

    static String link(String uri, String rel) {
        return "<%s>; rel=\"%s\"".formatted(uri, rel);
    }

    private ResponseEntity<String> response(String xml, List<String> links) {
        return ResponseEntity.ok()
                .header(HttpHeaders.LINK, links.toArray(String[]::new))
                .cacheControl(cacheControl)
                .lastModified(lastModified)
                .body(xml);
    }

    /** The response of a resource that also supports the QUERY method. */
    private ResponseEntity<String> queryableResponse(String xml, List<String> links) {
        return ResponseEntity.ok()
                .header(HttpHeaders.LINK, links.toArray(String[]::new))
                .header("Accept-Query", QueryController.ACCEPT_QUERY)
                .cacheControl(cacheControl)
                .lastModified(lastModified)
                .body(xml);
    }

}
