package hu.unideb.inf.shakespeare.repository;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.basex.core.BaseXException;
import org.basex.core.Context;
import org.basex.core.StaticOptions;
import org.basex.core.cmd.XQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PreDestroy;

/**
 * Stores the bookmarks of the users in a persistent (on-disk) BaseX database
 * named {@code bookmarks}. Each user has an XML document in the database,
 * e.g., {@code alice.xml}:
 *
 * <pre>
 * &lt;bookmarks next-id="3"&gt;
 *   &lt;bookmark id="1"&gt;
 *     &lt;target&gt;/plays/macbeth/acts/1/scenes/3&lt;/target&gt;
 *     &lt;note&gt;The prophecy of the witches&lt;/note&gt;
 *   &lt;/bookmark&gt;
 *   &lt;bookmark id="2"&gt;...&lt;/bookmark&gt;
 * &lt;/bookmarks&gt;
 * </pre>
 *
 * <p>The {@code next-id} attribute is the identifier of the next bookmark,
 * so the identifiers of deleted bookmarks are not used again. The document of
 * a user is created with the first bookmark.
 *
 * <p>The documents are modified with XQuery Update. The values (user names,
 * notes, ...) are passed to the queries as external variables, never pasted
 * into them, so they cannot be used for injection.
 *
 * <p>All methods are {@code synchronized}: the bookmarks are accessed by one
 * request thread at a time, with one BaseX {@link Context}. This is the
 * simplest way to make sure that concurrent requests do not interfere (e.g.,
 * two new bookmarks do not get the same identifier).
 */
@Repository
public class BookmarkRepository {

    /** Creates the database when the application is started for the first time. */
    private static final String CREATE_DATABASE = """
            if (db:exists('bookmarks')) then () else db:create('bookmarks')""";

    /** Creates the document of a user if it does not exist yet. */
    private static final String CREATE_DOCUMENT = """
            declare variable $user external;
            if (db:exists('bookmarks', $user || '.xml')) then ()
            else db:put('bookmarks', <bookmarks next-id="1"/>, $user || '.xml')""";

    /** Adds a bookmark, and returns its identifier. */
    private static final String CREATE = """
            declare variable $user external;
            declare variable $target external;
            declare variable $note external;
            let $bookmarks := db:get('bookmarks', $user || '.xml')/bookmarks
            let $id := $bookmarks/@next-id
            return (
              insert node <bookmark id="{ $id }">
                <target>{ $target }</target>
                <note>{ $note }</note>
              </bookmark> into $bookmarks,
              replace value of node $id with xs:integer($id) + 1,
              update:output(string($id))
            )""";

    /** The identifiers of the bookmarks of a user, separated by spaces. */
    private static final String IDS = """
            declare variable $user external;
            string-join(db:get('bookmarks', $user || '.xml')/bookmarks/bookmark/@id, ' ')""";

    private static final String LIST = """
            declare variable $user external;
            <bookmarks>{ db:get('bookmarks', $user || '.xml')/bookmarks/bookmark }</bookmarks>""";

    private static final String GET = """
            declare variable $user external;
            declare variable $id external;
            db:get('bookmarks', $user || '.xml')/bookmarks/bookmark[@id = $id]""";

    private static final String UPDATE = """
            declare variable $user external;
            declare variable $id external;
            declare variable $note external;
            replace value of node db:get('bookmarks', $user || '.xml')/bookmarks/bookmark[@id = $id]/note
            with $note""";

    private static final String DELETE = """
            declare variable $user external;
            declare variable $id external;
            delete node db:get('bookmarks', $user || '.xml')/bookmarks/bookmark[@id = $id]""";

    private final Context context;

    /**
     * Opens the {@code bookmarks} database in the directory given by the
     * {@code shakespeare.bookmarks.dbpath} property, and creates it if it
     * does not exist yet.
     */
    public BookmarkRepository(@Value("${shakespeare.bookmarks.dbpath}") String dbpath) {
        // The directory of the databases must be set before the context is created.
        StaticOptions options = new StaticOptions(false);
        options.set(StaticOptions.DBPATH, Path.of(dbpath).toAbsolutePath().toString());
        context = new Context(options);
        query(CREATE_DATABASE, Map.of());
    }

    /** Adds a bookmark to the bookmarks of a user, and returns its identifier. */
    public synchronized int create(String user, String target, String note) {
        query(CREATE_DOCUMENT, Map.of("user", user));
        return Integer.parseInt(query(CREATE, Map.of("user", user, "target", target, "note", note)));
    }

    /** Returns the identifiers of the bookmarks of a user. */
    public synchronized List<Integer> ids(String user) {
        String ids = query(IDS, Map.of("user", user));
        return ids.isEmpty() ? List.of() : Arrays.stream(ids.split(" ")).map(Integer::valueOf).toList();
    }

    /** Returns the bookmarks of a user in a {@code bookmarks} element. */
    public synchronized String list(String user) {
        return query(LIST, Map.of("user", user));
    }

    /** Returns a bookmark ({@code bookmark} element), if it exists. */
    public synchronized Optional<String> get(String user, int id) {
        String bookmark = query(GET, Map.of("user", user, "id", String.valueOf(id)));
        return bookmark.isEmpty() ? Optional.empty() : Optional.of(bookmark);
    }

    /**
     * Replaces the note of a bookmark.
     *
     * @return {@code false} if the bookmark does not exist
     */
    public synchronized boolean update(String user, int id, String note) {
        if (get(user, id).isEmpty()) {
            return false;
        }
        query(UPDATE, Map.of("user", user, "id", String.valueOf(id), "note", note));
        return true;
    }

    /**
     * Deletes a bookmark.
     *
     * @return {@code false} if the bookmark does not exist
     */
    public synchronized boolean delete(String user, int id) {
        if (get(user, id).isEmpty()) {
            return false;
        }
        query(DELETE, Map.of("user", user, "id", String.valueOf(id)));
        return true;
    }

    /** Closes the database when the application is stopped. */
    @PreDestroy
    public synchronized void close() {
        context.close();
    }

    /** Executes an XQuery (Update) expression, and returns the serialized result. */
    private String query(String xquery, Map<String, String> variables) {
        XQuery command = new XQuery(xquery);
        variables.forEach(command::bind);
        try {
            return command.execute(context);
        } catch (BaseXException e) {
            throw new IllegalStateException(e);
        }
    }

}
