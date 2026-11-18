package mdg.mijery.media.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless API security.
 *
 * <ul>
 *   <li>{@code GET /media/**} — public variants. Protected assets still require the
 *       existing signed URL, verified in {@code ServeController}.</li>
 *   <li>{@code GET /logo/**} — public product logos (they appear on published content).</li>
 *   <li>{@code /api/**} — trusted services only; authenticated by
 *       {@link ServiceTokenFilter}.</li>
 *   <li>anything else — denied; this service has no UI.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ServiceTokenFilter serviceTokenFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/media/**", "/logo/**").permitAll()
                        .requestMatchers("/api/**").hasRole("SERVICE")
                        .anyRequest().denyAll())
                .addFilterBefore(serviceTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
