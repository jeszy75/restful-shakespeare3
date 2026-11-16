package hu.unideb.inf.shakespeare.web;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Allows JavaScript code from any origin to use the API (CORS). The
 * configuration is applied by Spring Security (see {@link SecurityConfig}),
 * before authentication, so preflight requests (which never carry
 * credentials) are answered without authentication.
 *
 * <ul>
 * <li>QUERY, POST, PUT and DELETE are not CORS-safelisted methods, so browsers
 * send a preflight request before such cross-origin requests.</li>
 * <li>{@code Authorization} must be allowed explicitly: it is not covered
 * even by the {@code *} wildcard of {@code Access-Control-Allow-Headers}.
 * (Scripts set it themselves, so these are not "credentialed" requests in
 * the sense of CORS, which is about cookies and credentials stored by the
 * browser; that is why {@code Access-Control-Allow-Origin: *} is enough.)</li>
 * <li>The {@code Link}, {@code Accept-Query} and {@code Location} header
 * fields are not CORS-safelisted response headers, so they must be exposed
 * explicitly; otherwise scripts could read the body of a response but not
 * these headers.</li>
 * </ul>
 *
 * <p>The configuration applies to every response, including error responses
 * (e.g., 404 for an unknown path, or 401).
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of("*"));
        cors.setAllowedMethods(List.of("GET", "HEAD", "QUERY", "POST", "PUT", "DELETE"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        cors.setExposedHeaders(List.of(HttpHeaders.LINK, "Accept-Query", HttpHeaders.LOCATION));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

}
