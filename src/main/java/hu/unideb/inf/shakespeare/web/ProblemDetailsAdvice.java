package hu.unideb.inf.shakespeare.web;

import static org.apache.commons.text.StringEscapeUtils.escapeXml10;

import java.net.URI;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Reports errors as problem details (RFC 9457) in the XML format of Appendix B
 * of the RFC. {@link ResponseEntityExceptionHandler} turns the exceptions of
 * Spring MVC (e.g., 404 for an unknown path, 400 for a non-numeric act number,
 * 405 for an unsupported method) and {@code ResponseStatusException}s into
 * {@link ProblemDetail} objects; here we only write them in XML.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail p ? p : ProblemDetail.forStatus(statusCode);
        if (problem.getInstance() == null) {
            problem.setInstance(URI.create(((ServletWebRequest) request).getRequest().getRequestURI()));
        }
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_XML)
                .body(toXML(problem));
    }

    /**
     * Serializes a problem details object in XML (RFC 9457, Appendix B). Also
     * used by {@link SecurityConfig} for 401 (Unauthorized) responses, which
     * are sent by Spring Security, not Spring MVC.
     */
    static String toXML(ProblemDetail problem) {
        return """
                <problem xmlns="urn:ietf:rfc:7807">
                  <title>%s</title>
                  <status>%d</status>
                  <detail>%s</detail>
                  <instance>%s</instance>
                </problem>
                """.formatted(
                escapeXml10(problem.getTitle()),
                problem.getStatus(),
                escapeXml10(Objects.toString(problem.getDetail(), "")),
                escapeXml10(Objects.toString(problem.getInstance(), "")));
    }

}
