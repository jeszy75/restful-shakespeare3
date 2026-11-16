package hu.unideb.inf.shakespeare.web;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authentication and authorization (Spring Security).
 *
 * <ul>
 * <li>Reading is public: the plays (GET, HEAD, OPTIONS, QUERY) need no
 * authentication.</li>
 * <li>Creating bookmarks (POST) and everything under {@code /users} need
 * HTTP Basic authentication (RFC 7617). Whether the authenticated user may
 * access the bookmarks of the user in the URI is decided by
 * {@link BookmarkController} (403 Forbidden).</li>
 * <li>There are two users with fixed passwords: {@code alice} (password
 * {@code wonderland}) and {@code bob} (password {@code builder}). This is
 * only good for a demo: real applications store hashed passwords, e.g., in a
 * database.</li>
 * <li>The API is stateless: there are no sessions (the credentials are sent
 * with every request), so there are no session cookies, and protection
 * against CSRF (which abuses cookies) is not needed.</li>
 * </ul>
 *
 * <p>Spring Security also adds security headers to the responses:
 * {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY},
 * the {@code Content-Security-Policy} configured here, and
 * {@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate} if
 * the response has no {@code Cache-Control} yet (e.g., bookmarks and errors).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/users/**").authenticated()
                        .requestMatchers(HttpMethod.POST).authenticated()
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(SecurityConfig::unauthorized))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(SecurityConfig::unauthorized))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults()) // uses the CorsConfigurationSource of CorsConfig
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp
                        .policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .build();
    }

    @Bean
    public UserDetailsService users() {
        return new InMemoryUserDetailsManager(
                // {noop}: the passwords are stored as they are, without hashing
                User.withUsername("alice").password("{noop}wonderland").build(),
                User.withUsername("bob").password("{noop}builder").build());
    }

    /**
     * Spring Security rejects requests with unknown methods (400 Bad Request),
     * and its default list does not contain QUERY yet.
     */
    @Bean
    public HttpFirewall httpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHttpMethods(
                List.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE", "QUERY"));
        return firewall;
    }

    /**
     * Sends 401 (Unauthorized) with problem details, when credentials are
     * missing or wrong. The {@code WWW-Authenticate} header field tells the
     * client which authentication scheme to use; browsers show a login dialog.
     */
    private static void unauthorized(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"RESTful Shakespeare\"");
        response.setContentType(MediaType.APPLICATION_PROBLEM_XML_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(ProblemDetailsAdvice.toXML(problem));
    }

}
