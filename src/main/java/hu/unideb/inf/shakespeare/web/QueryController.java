package hu.unideb.inf.shakespeare.web;

import java.time.Duration;
import java.util.regex.Pattern;

import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import hu.unideb.inf.shakespeare.repository.PlayRepository;
import hu.unideb.inf.shakespeare.repository.XPathEvaluationException;

/**
 * Evaluates XPath expressions on parts of the plays with the QUERY method
 * (RFC 10008). The expression is the request content ({@code text/plain}),
 * its context item is the target resource (a play, an act, a scene, or a
 * speech), and the result is returned wrapped into a {@code results} element:
 *
 * <pre>
 * QUERY /plays/macbeth/acts/1/scenes/3 HTTP/1.1
 * Content-Type: text/plain
 *
 * SPEECH[SPEAKER = 'BANQUO']/LINE[1]
 * </pre>
 *
 * <p>Only XPath 1.0 expressions are accepted; see {@link #checkXPath(String)}.
 *
 * <p>Responses to QUERY are cacheable (RFC 10008, with the request content as
 * part of the cache key); they carry the same {@code Cache-Control} as the
 * GET responses, but no validator, since conditional requests and 304 (Not
 * Modified) are defined for GET and HEAD.
 */
@RestController
@RequestMapping(path = "/plays/{play}", method = RequestMethod.QUERY,
        consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
public class QueryController {

    /**
     * The media type of the query content, advertised in the
     * {@code Accept-Query} header field. There is no registered media type
     * for XPath expressions.
     */
    public static final String ACCEPT_QUERY = MediaType.TEXT_PLAIN_VALUE;

    /** A prefixed function name followed by a parenthesis, e.g., {@code file:read-text(}. */
    private static final Pattern EXTENSION_FUNCTION =
            Pattern.compile("[\\p{L}_][\\p{L}\\p{N}._-]*:[\\p{L}_][\\p{L}\\p{N}._-]*\\s*\\(");

    /** An XPath 1.0 string literal (it cannot contain its delimiter). */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"[^\"]*\"|'[^']*'");

    private final PlayRepository plays;

    private final CacheControl cacheControl;

    public QueryController(PlayRepository plays,
            @Value("${shakespeare.cache.max-age}") Duration maxAge) {
        this.plays = plays;
        this.cacheControl = maxAge.isZero()
                ? CacheControl.noCache()
                : CacheControl.maxAge(maxAge).cachePublic();
    }

    @RequestMapping
    public ResponseEntity<String> play(@PathVariable String play, @RequestBody String xpath) {
        return query(play, "/PLAY", xpath);
    }

    @RequestMapping("/acts/{act}")
    public ResponseEntity<String> act(@PathVariable String play, @PathVariable int act,
            @RequestBody String xpath) {
        return query(play, "/PLAY/ACT[%d]".formatted(act), xpath);
    }

    @RequestMapping("/acts/{act}/scenes/{scene}")
    public ResponseEntity<String> scene(@PathVariable String play, @PathVariable int act,
            @PathVariable int scene, @RequestBody String xpath) {
        return query(play, "/PLAY/ACT[%d]/SCENE[%d]".formatted(act, scene), xpath);
    }

    @RequestMapping("/acts/{act}/scenes/{scene}/speeches/{speech}")
    public ResponseEntity<String> speech(@PathVariable String play, @PathVariable int act,
            @PathVariable int scene, @PathVariable int speech, @RequestBody String xpath) {
        return query(play, "/PLAY/ACT[%d]/SCENE[%d]/SPEECH[%d]".formatted(act, scene, speech), xpath);
    }

    /**
     * Evaluates {@code xpath} with the element selected by {@code part}
     * (e.g., {@code /PLAY/ACT[2]}) as the context item.
     */
    private ResponseEntity<String> query(String play, String part, String xpath) {
        if (!plays.exists(play) || plays.count(play, part) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Play '%s' has no %s.".formatted(play, part));
        }
        checkXPath(xpath);
        try {
            return ResponseEntity.ok()
                    .header("Accept-Query", ACCEPT_QUERY)
                    .cacheControl(cacheControl)
                    .body(plays.evaluateXPath(play, part, xpath));
        } catch (XPathEvaluationException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
    }

    /**
     * Rejects everything but XPath 1.0 expressions with 400 (Bad Request).
     *
     * <p>The XPath 1.0 parser of the JDK checks the syntax, so XQuery
     * features (prolog, FLWOR expressions, node constructors, updates) and
     * XPath 2.0+ features are rejected, and so are unknown functions such as
     * {@code doc()} or {@code unparsed-text()}. XPath 1.0 itself has no
     * functions to access files or the network, but the parser accepts
     * prefixed (extension) function calls, so these are rejected separately.
     * Variables are rejected by BaseX, since none are declared.
     *
     * <p>The accepted expressions are evaluated by BaseX (with XPath 3.1
     * semantics), whose evaluation can be stopped after a time limit.
     */
    static void checkXPath(String xpath) {
        try {
            XPathFactory.newInstance().newXPath().compile(xpath);
        } catch (XPathExpressionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Not an XPath 1.0 expression: " + cause.getMessage());
        }
        String withoutLiterals = STRING_LITERAL.matcher(xpath).replaceAll("''");
        if (EXTENSION_FUNCTION.matcher(withoutLiterals).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Extension functions are not allowed.");
        }
    }

}
