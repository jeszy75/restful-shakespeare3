# RESTful Shakespeare

A hypermedia REST API that serves the text of Shakespeare's plays in XML,
evaluates XPath expressions on them with the HTTP `QUERY` method (RFC 10008),
and lets authenticated users bookmark parts of the plays (`POST`, `PUT`,
`DELETE`). It was built for the computer lab *Designing and testing REST APIs*
of the *Modern Web Standards* course.

The texts are Jon Bosak's XML editions of the plays (see `src/main/resources`).
For now, only *Macbeth* is served, but more plays can be added by putting
`id.xml` into `src/main/resources` and listing `id` in the `shakespeare.plays`
property (`application.yaml`).

## Building and running

Requirements: Java 21 or later, Maven 3.9 or later. The project uses Spring
Boot 4.2 (a release candidate at the time of writing), the first version with
support for the `QUERY` method (Spring Framework 7.1).

```bash
mvn verify            # compile and run the tests
mvn spring-boot:run   # start the server on http://localhost:8080
```

The bookmarks are stored in the `data` directory of the working directory
(`shakespeare.bookmarks.dbpath` in `application.yaml`); delete it to start
from scratch. The tests use their own databases under `target/test-data`.

## Resources

| URI | Resource |
|-----|----------|
| `/plays` | the list of plays (entry point) |
| `/plays/{play}` | a play (e.g., `/plays/macbeth`) |
| `/plays/{play}/acts/{act}` | an act |
| `/plays/{play}/acts/{act}/scenes/{scene}` | a scene |
| `/plays/{play}/acts/{act}/scenes/{scene}/speeches/{speech}` | a speech |
| `/plays/{play}/bookmark`, `/plays/{play}/acts/{act}/bookmark`, ... | where to `POST` to bookmark a play, act, scene or speech |
| `/users/{user}/bookmarks` | the bookmarks of a user (e.g., `/users/alice/bookmarks`) |
| `/users/{user}/bookmarks/{id}` | a bookmark |

Numbering starts at 1 and counts only elements of the same kind: acts count
`ACT` elements, scenes count `SCENE` elements within an act, and speeches count
`SPEECH` elements within a scene (stage directions are ignored).

Supported methods: `GET`, `HEAD` and `OPTIONS` for the plays and their parts;
plays, acts, scenes and speeches also support `QUERY`. For the bookmarks, see
[Bookmarks](#bookmarks).

## Representations

All representations are XML (`application/xml`): the corresponding fragment of
the source document (`PLAY`, `ACT`, `SCENE` or `SPEECH` element), unchanged.
`/plays` returns a short list:

```xml
<plays><play id="macbeth"><TITLE>The Tragedy of Macbeth</TITLE></play></plays>
```

A bookmark, and the bookmarks of a user:

```xml
<bookmark id="1"><target>/plays/macbeth/acts/1/scenes/3</target><note>The prophecy of the witches</note></bookmark>

<bookmarks><bookmark id="1">...</bookmark><bookmark id="3">...</bookmark></bookmarks>
```

## Hypermedia

Successful `GET` responses have `Link` header fields (RFC 8288) with link relation types
registered at IANA, and one extension relation type:

| Relation | Target |
|----------|--------|
| `self` | the resource itself |
| `up` | the parent (the play of an act, the act of a scene, ...) |
| `first`, `last` | the first and the last sibling (e.g., the first and last scene of the same act) |
| `prev`, `next` | the previous and the next sibling, if any; they never cross act or scene boundaries |
| `item` | each child (the plays in `/plays`, the acts of a play, the scenes of an act, the speeches of a scene), and each bookmark of a user |
| `collection` | the bookmarks of the user, from a bookmark (RFC 6573) |
| `tag:inf.unideb.hu,2026:restful-shakespeare/rels/bookmark` | where to `POST` to bookmark a play, act, scene or speech |

The last one is an *extension relation type* (RFC 8288): there is no
registered relation type for "where to bookmark this", and extension relation
types must be URIs (here a `tag` URI, RFC 4151, like the namespace of the
`QUERY` results). Clients compare it as a string; it does not have to be
dereferenceable.

Starting at `/plays`, a client can reach every part of every play by following
links, without constructing URIs.

The `Link` header fields of the largest scene of Macbeth (Act IV, Scene III,
65 speeches) take about 4.4 KB, below Tomcat's default limit of 8 KB for
response headers. Longer plays need more (e.g., Act II, Scene II of Hamlet,
with 164 speeches, would need about 11 KB), so the limit is raised in
`application.yaml`.

## XPath queries with `QUERY`

A `QUERY` request evaluates the XPath expression in its content
(`Content-Type: text/plain`) with the target resource as the context item:

```http
QUERY /plays/macbeth/acts/1/scenes/3 HTTP/1.1
Content-Type: text/plain

SPEECH[SPEAKER = 'BANQUO']/LINE[1]
```

The result is wrapped into a `results` element whose `type` attribute gives
its XPath 1.0 type: `node-set`, `string`, `number` or `boolean`. A string,
number or boolean is the content of the `results` element; in a node-set,
element (and comment, processing instruction) nodes are copied as they are,
text nodes are wrapped into `text` elements, and attribute nodes into
`attribute` elements (with a `name` attribute). For example, for
`count(//SPEECH[SPEAKER = 'MACBETH'])` on `/plays/macbeth`:

```xml
<r:results xmlns:r="tag:inf.unideb.hu,2026:restful-shakespeare/results" type="number">146</r:results>
```

and for `TITLE/text()` on `/plays/macbeth/acts/3`:

```xml
<r:results xmlns:r="tag:inf.unideb.hu,2026:restful-shakespeare/results" type="node-set"><r:text>ACT III</r:text></r:results>
```

The wrapping elements are in their own namespace (a `tag` URI, RFC 4151), so
they cannot be confused with the elements of the plays, which are in no
namespace. (Absolute paths such as `/PLAY/TITLE` select from the whole
document.)

Responses to `GET` and `HEAD` requests of these resources advertise `QUERY`
with `Accept-Query: text/plain`, and `OPTIONS` lists it in `Allow`. There is no
registered media type for XPath expressions, hence `text/plain`.

Executing client-supplied queries is risky, so several measures limit what a
query can do:

1. **Only XPath 1.0 is accepted.** Expressions are first parsed by the XPath
   1.0 implementation of the JDK, which rejects XQuery (prolog, FLWOR
   expressions, node constructors, updates), XPath 2.0+ syntax, and unknown
   functions such as `doc()` or `unparsed-text()`. XPath 1.0 has no functions
   for accessing files or the network. Prefixed (extension) function calls,
   such as `file:read-text(...)`, are rejected as well.
2. **The evaluation runs without permissions.** Accepted expressions are
   evaluated by BaseX on behalf of a user without any permissions, so even
   functions that slipped through could not access files, the network, or
   databases.
3. **The evaluation is stopped after a time limit**
   (`shakespeare.query.time-limit`, 5 seconds by default), since even XPath
   1.0 expressions can be expensive (e.g., deeply nested predicates).

Status codes: `400` for expressions that are not XPath 1.0 (or for missing
content), `415` for content that is not `text/plain`, `422` if the expression
cannot be evaluated (e.g., an undeclared variable, a type error, or exceeding
the time limit), `404` for a nonexistent target resource, and `405` for
`/plays`.

## Bookmarks

Authenticated users (see [Authentication](#authentication)) can bookmark
plays, acts, scenes and speeches, and add a note to their bookmarks.

| Request | Effect | Response |
|---------|--------|----------|
| `POST /plays/macbeth/acts/1/scenes/3/bookmark` with a note (`text/plain`, may be empty) | creates a bookmark of the scene | `201 Created`, `Location: /users/alice/bookmarks/7`, the new bookmark |
| `GET /users/alice/bookmarks` | | `200`, the bookmarks, with an `item` link to each |
| `GET /users/alice/bookmarks/7` | | `200`, the bookmark |
| `PUT /users/alice/bookmarks/7` with a note (`text/plain`) | replaces the note | `204 No Content` |
| `DELETE /users/alice/bookmarks/7` | deletes the bookmark | `204 No Content` |

Other status codes: `401` without (valid) credentials, `403` for the bookmarks
of another user, `404` for a nonexistent target or bookmark, `405` for other
methods (e.g., `GET .../bookmark`), and `415` if the note is not `text/plain`.

Design decisions:

* **`POST` to `.../bookmark` creates a bookmark.** `PUT` or `DELETE` on the
  URI of a scene would mean replacing or deleting the scene itself, so
  bookmarks are separate resources. The semantics of `POST` are defined by the
  target resource, and creating a resource elsewhere is a standard use of it
  (`201 Created` with `Location`), like submitting an HTML form. The target of
  the bookmark is the URI itself, so the request content is just the note, and
  a nonexistent target is simply a `404`. The responses of plays, acts, scenes
  and speeches link to these URIs (see [Hypermedia](#hypermedia)), so clients
  do not need to construct them. Repeated `POST`s create more bookmarks
  (`POST` is not idempotent).
* **Bookmarks live under `/users/{user}`.** Their URIs do not depend on who is
  logged in (unlike, e.g., `/bookmarks` for "my bookmarks"), so a URI always
  identifies the same resource, and accessing another user's bookmarks is a
  clear `403`.
* **`PUT` replaces the note.** The `text/plain` representation of a bookmark
  is its note, so `PUT` with `text/plain` content replaces it (the target
  cannot be changed). `PUT` and `DELETE` are idempotent: repeating them leaves
  the bookmark in the same state (a repeated `DELETE` answers `404`, but
  the state does not change). `PUT` does not create bookmarks (`404` for a
  nonexistent one), since the server assigns their identifiers.
* **There is no `PATCH`.** It would need a patch format for XML, and there
  is no simple, standard one (RFC 5261 XML Patch uses XPath selectors).

The bookmarks are stored in a persistent BaseX database named `bookmarks`,
with an XML document for each user (e.g., `alice.xml`), modified with XQuery
Update:

```xml
<bookmarks next-id="8">
  <bookmark id="1"><target>/plays/macbeth/acts/1/scenes/3</target><note>The prophecy of the witches</note></bookmark>
  ...
</bookmarks>
```

`next-id` is the identifier of the next bookmark, so the identifiers of deleted
bookmarks are not reused (an old URI never identifies a different bookmark).
The values from the requests are passed to the queries as external variables,
so they cannot be used for injection.

## Authentication

Bookmarking (`POST`) and everything under `/users` need HTTP Basic
authentication (RFC 7617); reading the plays is public. There are two users
with fixed passwords (configured in `web.SecurityConfig`; only good for a
demo):

| User | Password |
|------|----------|
| `alice` | `wonderland` |
| `bob` | `builder` |

* Without credentials, or with wrong ones, the response is `401 Unauthorized`
  with `WWW-Authenticate: Basic realm="RESTful Shakespeare"` (browsers show a
  login dialog) and problem details.
* Users can access their own bookmarks only; otherwise the response is
  `403 Forbidden`.
* The API is stateless: there are no sessions, the credentials are sent with
  every request. So there are no session cookies, and no protection against
  CSRF is needed (CSRF abuses cookies that browsers send automatically).
* HTTP Basic sends the password in every request (Base64 is an encoding, not
  encryption), so real deployments must use HTTPS.
* Spring Security rejects requests with unknown methods (`400`), and its
  default list of allowed methods does not contain `QUERY` yet, so it is
  added in `web.SecurityConfig`.

## Errors

Errors are reported as problem details (RFC 9457) in the XML format of
Appendix B of the RFC (`application/problem+xml`), e.g.:

```xml
<problem xmlns="urn:ietf:rfc:7807">
  <title>Not Found</title>
  <status>404</status>
  <detail>Play 'macbeth' has no /PLAY/ACT[6].</detail>
  <instance>/plays/macbeth/acts/6</instance>
</problem>
```

## Caching

Every response to `GET` and `HEAD` carries:

* `Last-Modified`: the start of the application. The plays never change, so
  the representations can only change with a new deployment (e.g., new links
  when a play is added).
* `Cache-Control`, configured by `shakespeare.cache.max-age` in
  `application.yaml`. The default, `0s`, means `no-cache`: caches may store
  responses, but must revalidate them before each use. A positive value, such
  as `1h`, means `public, max-age=3600`: caches may reuse responses for an hour
  without asking the server.

Spring answers conditional requests (`If-Modified-Since`) automatically with
`304 Not Modified`, without a body.

Responses to `QUERY` carry the same `Cache-Control` (RFC 10008 makes them
cacheable, with the request content as part of the cache key), but no
validator, since conditional requests are defined for `GET` and `HEAD`.

Bookmarks are personal and change, so their responses must not be cached:
Spring Security adds `Cache-Control: no-cache, no-store, max-age=0,
must-revalidate` to every response that has no `Cache-Control` yet (bookmarks,
errors). Shared caches would not store responses to requests with an
`Authorization` header anyway (RFC 9111).

## CORS

JavaScript code from any origin may use the API: responses to cross-origin
requests carry `Access-Control-Allow-Origin: *` and
`Access-Control-Expose-Headers: Link, Accept-Query, Location`. Exposing the
headers is essential, because they are not CORS-safelisted response headers:
without it, scripts could read the representations but not the links (or the
URI of a new bookmark). `QUERY`, `POST` (with `text/plain` content it would be
safelisted, but the `Authorization` header is not), `PUT` and `DELETE` need a
preflight request; the preflight response allows `GET`, `HEAD`, `QUERY`,
`POST`, `PUT` and `DELETE`, and the request headers `Authorization` and
`Content-Type`. `Authorization` must be listed explicitly: the `*` wildcard
does not cover it. The CORS configuration (`web.CorsConfig`) is applied by
Spring Security, before authentication, since preflight requests carry no
credentials.

## Security headers

Following the [OWASP REST Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html#security-headers),
every response carries (added by Spring Security, see `web.SecurityConfig`):

* `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`: a
  response rendered by a browser (e.g., when a URI is opened directly) must not
  load or execute anything, and must not be framed (clickjacking);
* `X-Content-Type-Options: nosniff`: browsers must not guess the media type;
* `X-Frame-Options: DENY`: the older way of forbidding framing.

## Trying it out

The `bruno` directory is a [Bruno](https://www.usebruno.com/) collection (in
the OpenCollection YAML format, Bruno 3.0 or later) that tries out and tests
every feature of the API: navigation, `QUERY`, caching, errors, bookmarks,
authentication, CORS and security headers. Open the directory in Bruno,
select the `Local` environment, and set its secret variables `username` and
`password` (e.g., `alice` and `wonderland`). The values of secret variables
are stored by Bruno, not in the files, so they are not committed. With the
Bruno CLI (`npm install -g @usebruno/cli`):

```bash
cd bruno
bru run . -r --env Local --env-var username=alice --env-var password=wonderland
```

With `curl`:

```bash
curl -i http://localhost:8080/plays
curl -i http://localhost:8080/plays/macbeth/acts/1/scenes/1/speeches/1
curl -I http://localhost:8080/plays/macbeth/acts/3                  # HEAD
curl -i -X OPTIONS http://localhost:8080/plays/macbeth              # OPTIONS
curl -i http://localhost:8080/plays/macbeth/acts/6                  # 404
curl -i -H 'Accept: application/json' http://localhost:8080/plays  # 406
# 304; use the Last-Modified value of a previous response
curl -i -H 'If-Modified-Since: <Last-Modified>' http://localhost:8080/plays/macbeth/acts/1

# QUERY
curl -i -X QUERY -H 'Content-Type: text/plain' \
     --data "SPEECH[SPEAKER = 'BANQUO']/LINE[1]" \
     http://localhost:8080/plays/macbeth/acts/1/scenes/3
curl -i -X QUERY -H 'Content-Type: text/plain' \
     --data "for \$s in SPEECH return \$s" \
     http://localhost:8080/plays/macbeth/acts/1/scenes/1           # 400

# Bookmarks
curl -i -u alice:wonderland -H 'Content-Type: text/plain' \
     --data 'The prophecy of the witches' \
     http://localhost:8080/plays/macbeth/acts/1/scenes/3/bookmark   # 201
curl -i -u alice:wonderland http://localhost:8080/users/alice/bookmarks
curl -i -u alice:wonderland -X PUT -H 'Content-Type: text/plain' \
     --data 'All hail, Macbeth!' http://localhost:8080/users/alice/bookmarks/1  # 204
curl -i -u alice:wonderland -X DELETE http://localhost:8080/users/alice/bookmarks/1  # 204
curl -i http://localhost:8080/users/alice/bookmarks                # 401
curl -i -u bob:builder http://localhost:8080/users/alice/bookmarks  # 403
```

## REST constraints

How the API satisfies the constraints of the REST architectural style
(Fielding, 2000):

1. **Client-server.** The server only provides resources and their
   representations; presentation is left to clients (e.g., `curl`, Java
   programs, or JavaScript in browsers, thanks to CORS).
2. **Stateless.** The server keeps no session state: every request carries all
   the information needed to process it, the URI identifying the resource, the
   method identifying the operation, the content (the XPath expression of
   `QUERY`, or a note), and the credentials (`Authorization`, sent with every
   request instead of logging in once). The bookmarks stored in the database
   are resource state, not session state: they are resources with their own
   URIs.
3. **Cache.** Responses are explicitly labeled as cacheable: `Cache-Control`
   (`no-cache` or `public, max-age=...`, configurable) and the validator
   `Last-Modified` allow caches to reuse responses and to revalidate them
   cheaply with conditional requests (`304 Not Modified`). Responses to
   `QUERY` are cacheable too (RFC 10008, with the request content as part of
   the cache key); they carry the same `Cache-Control`, but no validator.
   Responses about bookmarks are explicitly non-cacheable (`no-store`).
4. **Uniform interface.**
   * *Identification of resources:* every play, act, scene and speech has its
     own URI; resources are distinct from their representations.
   * *Manipulation of resources through representations:* bookmarks are
     created (`POST`) and changed (`PUT`) by sending representations: the
     `text/plain` representation of a bookmark is its note.
   * *Self-descriptive messages:* standard methods with their standard
     semantics (`GET`, `HEAD`, `OPTIONS` and `QUERY` are safe and idempotent,
     `PUT` and `DELETE` are idempotent, `POST` is neither), standard status
     codes (`201`, `204`, `401`, `403`, ...), problem details for errors
     (RFC 9457), and standard header fields (`Content-Type`, `Allow`,
     `Accept-Query`, `Link`, `Location`, `Authorization`, `WWW-Authenticate`,
     `Cache-Control`, `Last-Modified`). A weak point is
     the media type: `application/xml` says that the content is XML, but not
     that it uses Jon Bosak's vocabulary (`PLAY`, `SPEECH`, ...); a
     `describedby` link to the DTD could help. `text/plain` for XPath
     expressions is generic too, but there is no registered media type for
     XPath.
   * *Hypermedia as the engine of application state (HATEOAS):* starting at
     the single entry point `/plays`, clients reach every part of every play by
     following `Link` header fields with IANA-registered relation types
     (`self`, `up`, `first`, `prev`, `next`, `last`, `item`), without
     constructing URIs. `Allow` and `Accept-Query` tell clients where `QUERY`
     can be used, and links with an extension relation type where resources
     can be bookmarked. A new bookmark is reached through `Location`, and the
     bookmarks of the user through its `collection` link. A gap: there is no
     link to `/users/{user}/bookmarks` for a user who has no bookmarks yet, so
     clients must know this URI template. The remaining out-of-band knowledge
     is the media type (the XML vocabulary of the plays and of the bookmarks,
     and XPath), which REST expects clients to know.
5. **Layered system.** The API relies on standard HTTP semantics only, so
   proxies, caches and reverse proxies can be inserted transparently. The
   targets of the `Link` header fields are path-absolute references, resolved
   against the request URI, so they stay valid wherever the server runs (as
   long as a reverse proxy does not add a path prefix, since the context path
   is not taken into account).
6. **Code on demand (optional).** Not used; the `Content-Security-Policy`
   `default-src 'none'` even forbids executing code in responses rendered by
   browsers.

In terms of the Richardson Maturity Model, the API is at level 3 (hypermedia
controls).

## Implementation

Classes (besides `RestfulShakespeareApplication` in the root package
`hu.unideb.inf.shakespeare`):

* `repository.PlayRepository`: parses each XML document once, at startup, into an
  embedded, main-memory [BaseX](https://basex.org/) database, and evaluates
  XPath/XQuery expressions against it. The documents are never modified, so
  concurrent requests can read them safely; each query gets its own BaseX
  `Context`, because contexts are not thread-safe. Client-supplied XPath
  expressions are evaluated in a sandbox (no permissions, time limit).
* `repository.XPathEvaluationException`: a client-supplied expression could
  not be evaluated.
* `web.PlayController`: the `GET` endpoints and their `Link` headers.
* `web.QueryController`: the `QUERY` endpoints and the XPath 1.0 check.
* `repository.BookmarkRepository`: stores the bookmarks in a persistent BaseX
  database, and modifies them with XQuery Update. Its methods are
  `synchronized`, so the bookmarks are accessed by one request at a time, with
  a single BaseX `Context`; this is the simplest way to make concurrent
  requests safe (e.g., two new bookmarks cannot get the same identifier).
* `web.BookmarkController`: the bookmark endpoints, and the check that users
  access their own bookmarks only.
* `web.ProblemDetailsAdvice`: writes errors as problem details in XML.
* `web.SecurityConfig`: authentication and authorization (Spring Security),
  the users, the security headers, and `QUERY` for Spring Security's firewall.
* `web.CorsConfig`: the CORS configuration.

Tests: `PlayApiTest`, `QueryApiTest`, `CacheMaxAgeTest` and `BookmarkApiTest`
(MockMvc; `httpBasic(...)` of Spring Security Test adds the credentials),
`BookmarkRepositoryTest` (the database alone, e.g., persistence), and
`ServerTest` (real Tomcat, for the size of response headers, HEAD responses,
and `QUERY` over the wire, which MockMvc does not check).
`src/test/resources/config/application.yaml` gives every Spring application
context of the tests its own, new database.

## Possible extensions

* Conditional `PUT` and `DELETE` with `ETag` and `If-Match` (`412
  Precondition Failed`), so that concurrent clients do not overwrite each
  other's changes (lost updates).
* Filtering the bookmarks, e.g., `GET /users/alice/bookmarks?play=macbeth`.
* A link from `/plays` to the bookmarks of the authenticated user.
