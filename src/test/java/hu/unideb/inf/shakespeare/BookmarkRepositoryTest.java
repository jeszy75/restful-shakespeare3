package hu.unideb.inf.shakespeare;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import hu.unideb.inf.shakespeare.repository.BookmarkRepository;

/** Tests of the BaseX database of the bookmarks, without Spring. */
class BookmarkRepositoryTest {

    @TempDir
    Path dbpath;

    @Test
    void bookmarksArePersistent() {
        BookmarkRepository repository = new BookmarkRepository(dbpath.toString());
        int id = repository.create("alice", "/plays/macbeth", "A note");
        repository.close();

        BookmarkRepository reopened = new BookmarkRepository(dbpath.toString());
        assertThat(reopened.get("alice", id)).hasValue(
                "<bookmark id=\"" + id + "\"><target>/plays/macbeth</target><note>A note</note></bookmark>");
        reopened.close();
    }

    @Test
    void identifiersAreNotReused() {
        BookmarkRepository repository = new BookmarkRepository(dbpath.toString());
        int first = repository.create("alice", "/plays/macbeth", "");
        int second = repository.create("alice", "/plays/macbeth/acts/1", "");
        assertThat(repository.delete("alice", second)).isTrue();
        int third = repository.create("alice", "/plays/macbeth/acts/2", "");
        assertThat(repository.ids("alice")).containsExactly(first, third);
        assertThat(third).isGreaterThan(second);
        repository.close();
    }

    @Test
    void usersHaveTheirOwnBookmarks() {
        BookmarkRepository repository = new BookmarkRepository(dbpath.toString());
        int id = repository.create("alice", "/plays/macbeth", "Alice's");
        assertThat(repository.ids("bob")).isEmpty();
        assertThat(repository.list("bob")).isEqualTo("<bookmarks/>");
        assertThat(repository.update("bob", id, "Bob's")).isFalse();
        assertThat(repository.delete("bob", id)).isFalse();
        assertThat(repository.get("alice", id)).get().asString().contains("<note>Alice's</note>");
        repository.close();
    }

    @Test
    void notesAreEscaped() {
        BookmarkRepository repository = new BookmarkRepository(dbpath.toString());
        int id = repository.create("alice", "/plays/macbeth", "<b>Fair & foul</b>");
        assertThat(repository.get("alice", id)).get().asString()
                .contains("<note>&lt;b&gt;Fair &amp; foul&lt;/b&gt;</note>");
        repository.close();
    }

}
