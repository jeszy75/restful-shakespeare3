package hu.unideb.inf.shakespeare.repository;

/**
 * Thrown when a client-supplied XPath expression cannot be evaluated (e.g.,
 * because of a type error, a missing permission, or the time limit).
 */
public class XPathEvaluationException extends RuntimeException {

    public XPathEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }

}
