package hu.unideb.inf.shakespeare.web;

import static hu.unideb.inf.shakespeare.web.PlayController.link;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import hu.unideb.inf.shakespeare.repository.BookmarkRepository;
import hu.unideb.inf.shakespeare.repository.PlayRepository;

/**
 * The bookmarks of the users. A bookmark refers to a play, an act, a scene,
 * or a speech (its target), and has a note.
 *
 * <ul>
 * <li>{@code POST .../bookmark} (e.g., {@code /plays/macbeth/acts/1/bookmark})
 * with a note ({@code text/plain}, may be empty) bookmarks the resource for
 * the authenticated user: 201 (Created), and the {@code Location} of the new
 * bookmark. The responses of the plays, acts, scenes and speeches link to
 * these URIs (link relation type {@link #BOOKMARK_REL}).</li>
 * <li>{@code GET /users/{user}/bookmarks}: the bookmarks of a user.</li>
 * <li>{@code GET /users/{user}/bookmarks/{id}}: a bookmark.</li>
 * <li>{@code PUT /users/{user}/bookmarks/{id}} with a note
 * ({@code text/plain}): replaces the note of a bookmark (204 No Content). The
 * {@code text/plain} representation of a bookmark is its note.</li>
 * <li>{@code DELETE /users/{user}/bookmarks/{id}}: deletes a bookmark (204 No
 * Content).</li>
 * </ul>
 *
 * <p>All of these require authentication (see {@link SecurityConfig}); users
 * can access their own bookmarks only (403 Forbidden).
 */
@RestController
public class BookmarkController {

    /**
     * The link relation type of the links to the URIs where resources can be
     * bookmarked. There is no registered link relation type for this, so it is
     * an extension relation type (RFC 8288), which must be a URI.
     */
    public static final String BOOKMARK_REL = "tag:inf.unideb.hu,2026:restful-shakespeare/rels/bookmark";

    private final PlayRepository plays;

    private final BookmarkRepository bookmarks;

    public BookmarkController(PlayRepository plays, BookmarkRepository bookmarks) {
        this.plays = plays;
        this.bookmarks = bookmarks;
    }

    // --- Creating bookmarks --------------------------------------------------

    @PostMapping(path = "/plays/{play}/bookmark",
            consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> bookmarkPlay(@PathVariable String play,
            @RequestBody(required = false) String note, Principal user) {
        return create(user, play, "/PLAY", "/plays/%s".formatted(play), note);
    }

    @PostMapping(path = "/plays/{play}/acts/{act}/bookmark",
            consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> bookmarkAct(@PathVariable String play, @PathVariable int act,
            @RequestBody(required = false) String note, Principal user) {
        return create(user, play, "/PLAY/ACT[%d]".formatted(act),
                "/plays/%s/acts/%d".formatted(play, act), note);
    }

    @PostMapping(path = "/plays/{play}/acts/{act}/scenes/{scene}/bookmark",
            consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> bookmarkScene(@PathVariable String play, @PathVariable int act,
            @PathVariable int scene, @RequestBody(required = false) String note, Principal user) {
        return create(user, play, "/PLAY/ACT[%d]/SCENE[%d]".formatted(act, scene),
                "/plays/%s/acts/%d/scenes/%d".formatted(play, act, scene), note);
    }

    @PostMapping(path = "/plays/{play}/acts/{act}/scenes/{scene}/speeches/{speech}/bookmark",
            consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> bookmarkSpeech(@PathVariable String play, @PathVariable int act,
            @PathVariable int scene, @PathVariable int speech,
            @RequestBody(required = false) String note, Principal user) {
        return create(user, play, "/PLAY/ACT[%d]/SCENE[%d]/SPEECH[%d]".formatted(act, scene, speech),
                "/plays/%s/acts/%d/scenes/%d/speeches/%d".formatted(play, act, scene, speech), note);
    }

    /**
     * Creates a bookmark of the element selected by {@code xpath} (e.g.,
     * {@code /PLAY/ACT[2]}), whose URI is {@code target}.
     */
    private ResponseEntity<String> create(Principal user, String play, String xpath, String target,
            String note) {
        // The XPath expressions contain numbers only (no user-supplied text).
        if (!plays.exists(play) || plays.count(play, xpath) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no %s.".formatted(target));
        }
        int id = bookmarks.create(user.getName(), target, Objects.toString(note, ""));
        return ResponseEntity.created(URI.create(bookmarkUri(user.getName(), id)))
                .body(bookmarks.get(user.getName(), id).orElseThrow());
    }

    // --- The bookmarks of a user ---------------------------------------------

    @GetMapping(path = "/users/{user}/bookmarks", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> bookmarks(@PathVariable String user, Principal principal) {
        checkUser(user, principal);
        List<String> links = new ArrayList<>();
        links.add(link(bookmarksUri(user), "self"));
        for (int id : bookmarks.ids(user)) {
            links.add(link(bookmarkUri(user, id), "item"));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.LINK, links.toArray(String[]::new))
                .body(bookmarks.list(user));
    }

    @GetMapping(path = "/users/{user}/bookmarks/{id}", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> bookmark(@PathVariable String user, @PathVariable int id,
            Principal principal) {
        checkUser(user, principal);
        String bookmark = bookmarks.get(user, id).orElseThrow(() -> notFound(user, id));
        return ResponseEntity.ok()
                .header(HttpHeaders.LINK,
                        link(bookmarkUri(user, id), "self"),
                        link(bookmarksUri(user), "collection"))
                .body(bookmark);
    }

    @PutMapping(path = "/users/{user}/bookmarks/{id}", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> replaceNote(@PathVariable String user, @PathVariable int id,
            @RequestBody(required = false) String note, Principal principal) {
        checkUser(user, principal);
        if (!bookmarks.update(user, id, Objects.toString(note, ""))) {
            throw notFound(user, id);
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{user}/bookmarks/{id}")
    public ResponseEntity<Void> delete(@PathVariable String user, @PathVariable int id,
            Principal principal) {
        checkUser(user, principal);
        if (!bookmarks.delete(user, id)) {
            throw notFound(user, id);
        }
        return ResponseEntity.noContent().build();
    }

    /** Users may access their own bookmarks only. */
    private static void checkUser(String user, Principal principal) {
        if (!user.equals(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "User '%s' cannot access the bookmarks of user '%s'.".formatted(principal.getName(), user));
        }
    }

    private static ResponseStatusException notFound(String user, int id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "User '%s' has no bookmark %d.".formatted(user, id));
    }

    private static String bookmarksUri(String user) {
        return "/users/%s/bookmarks".formatted(user);
    }

    private static String bookmarkUri(String user, int id) {
        return "/users/%s/bookmarks/%d".formatted(user, id);
    }

}
