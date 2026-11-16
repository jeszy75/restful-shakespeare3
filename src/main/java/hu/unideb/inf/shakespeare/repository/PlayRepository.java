package hu.unideb.inf.shakespeare.repository;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.basex.core.Context;
import org.basex.core.jobs.JobException;
import org.basex.core.users.User;
import org.basex.io.IOContent;
import org.basex.query.QueryException;
import org.basex.query.QueryProcessor;
import org.basex.query.value.Value;
import org.basex.query.value.node.DBNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

/**
 * Keeps the plays in embedded, main-memory BaseX databases and evaluates
 * XQuery (XPath) expressions against them.
 *
 * <p>Each XML document is parsed only once, at startup. Since the documents
 * are never modified, any number of request threads can read them at the
 * same time. A BaseX {@link Context}, however, must not be shared between
 * threads, so each query gets its own one.
 *
 * <p>Note: {@code Value} is BaseX's XDM value type here, so Spring's
 * {@code @Value} annotation is written with its fully qualified name.
 */
@Repository
public class PlayRepository {

    /** The namespace of the elements wrapping the results of XPath expressions. */
    public static final String RESULTS_NAMESPACE = "tag:inf.unideb.hu,2026:restful-shakespeare/results";

    /**
     * Wraps the result of a client-supplied XPath 1.0 expression (a node-set
     * or a single string, number, or boolean) into a well-formed XML document.
     * The {@code type} attribute gives the XPath 1.0 type of the result.
     * Element, comment and processing instruction nodes are copied as they
     * are; text and attribute nodes are wrapped into elements. The wrapping
     * elements are in their own namespace, so they cannot be confused with
     * the (no-namespace) elements of the plays.
     */
    private static final String RESULTS_QUERY = """
            declare namespace r = "%s";
            declare variable $result external;
            <r:results type="{
              if (every $item in $result satisfies $item instance of node()) then 'node-set'
              else if ($result instance of xs:boolean) then 'boolean'
              else if ($result instance of xs:numeric) then 'number'
              else 'string'
            }">{
              for $item in $result
              return typeswitch ($item)
                case attribute() return <r:attribute name="{ name($item) }">{ string($item) }</r:attribute>
                case text() return <r:text>{ $item }</r:text>
                default return $item
            }</r:results>""".formatted(RESULTS_NAMESPACE);

    private final Context mainContext = new Context(false);

    /** Play identifier (e.g., {@code macbeth}) to document node. */
    private final Map<String, DBNode> plays = new LinkedHashMap<>();

    /** The time limit for evaluating client-supplied XPath expressions. */
    private final Duration timeLimit;

    /** Stops XPath evaluations that exceed the time limit. */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("xpath-time-limit").daemon().factory());

    /**
     * Loads the plays listed in the {@code shakespeare.plays} property. The
     * document of play {@code id} is read from {@code classpath:id.xml}.
     */
    public PlayRepository(
            @org.springframework.beans.factory.annotation.Value("${shakespeare.plays}")
            List<String> ids,
            @org.springframework.beans.factory.annotation.Value("${shakespeare.query.time-limit}")
            Duration timeLimit) throws IOException {
        this.timeLimit = timeLimit;
        for (String id : ids) {
            try (InputStream in = new ClassPathResource(id + ".xml").getInputStream()) {
                plays.put(id, new DBNode(new IOContent(in.readAllBytes())));
            }
        }
    }

    public Set<String> ids() {
        return plays.keySet();
    }

    public boolean exists(String play) {
        return plays.containsKey(play);
    }

    /**
     * Evaluates an XQuery expression with the document of the play as the
     * context item, and returns the serialized result.
     */
    public String query(String play, String xquery) {
        return serialize(evaluate(xquery, plays.get(play), Map.of()));
    }

    /** Returns the number of nodes selected by an XPath expression. */
    public int count(String play, String xpath) {
        return Integer.parseInt(query(play, "count(" + xpath + ")"));
    }

    /**
     * Evaluates a client-supplied XPath expression with the element selected
     * by {@code contextXpath} (e.g., {@code /PLAY/ACT[2]}) as the context item,
     * and returns the result wrapped into a {@code results} element.
     *
     * <p>The expression is evaluated in a sandbox: by a BaseX user without any
     * permissions (so functions accessing files, the network, or databases
     * are rejected), and it is stopped when it exceeds the time limit.
     *
     * @throws XPathEvaluationException if the evaluation fails
     */
    public String evaluateXPath(String play, String contextXpath, String xpath) {
        Value part = evaluate(contextXpath, plays.get(play), Map.of());
        Context sandbox = new Context(mainContext);
        sandbox.user(new User("xpath"));
        Value result;
        try (QueryProcessor processor = new QueryProcessor(xpath, sandbox)) {
            processor.context(part);
            ScheduledFuture<?> stop = timer.schedule(processor::timeout,
                    timeLimit.toMillis(), TimeUnit.MILLISECONDS);
            try {
                result = processor.value();
            } finally {
                stop.cancel(false);
            }
        } catch (QueryException e) {
            throw new XPathEvaluationException(e.getLocalizedMessage(), e);
        } catch (JobException e) {
            throw new XPathEvaluationException("The evaluation exceeded the time limit of %d ms."
                    .formatted(timeLimit.toMillis()), e);
        }
        return serialize(evaluate(RESULTS_QUERY, part, Map.of("result", result)));
    }

    /** Evaluates a (trusted) XQuery expression. */
    private Value evaluate(String xquery, Value contextValue, Map<String, Value> variables) {
        try (QueryProcessor processor = new QueryProcessor(xquery, new Context(mainContext))) {
            processor.context(contextValue);
            for (Map.Entry<String, Value> variable : variables.entrySet()) {
                processor.variable(variable.getKey(), variable.getValue());
            }
            return processor.value();
        } catch (QueryException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String serialize(Value value) {
        try {
            return value.serialize().toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

}
