package sh.vork.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal Spring Security configuration for development.
 *
 * <ul>
 *   <li>Routes under {@code /ui/**} and {@code /api/**} require authentication.</li>
 *   <li>Static assets, the WebSocket endpoint, and everything else are publicly accessible.</li>
 *   <li>Both HTTP Basic and form login are enabled so browser clients and API clients work.</li>
 *   <li>CSRF is disabled — the SPA submits JSON/multipart over fetch, not HTML forms with cookies.</li>
 * </ul>
 *
 * <p><strong>Default credentials (development only):</strong> {@code admin} / {@code password}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for development — SPA uses Bearer/Basic, not cookie-based CSRF tokens
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Static assets — always public
                .requestMatchers("/css/**", "/js/**", "/images/**", "/*.html", "/*.ico").permitAll()
                // WebSocket SockJS handshake
                .requestMatchers("/ws/**").permitAll()
                // Secured areas
                .requestMatchers("/ui/**", "/api/**").authenticated()
                // Everything else (root, health, etc.) is public
                .anyRequest().permitAll()
            )
            // Support both browser form login and programmatic Basic auth
            .httpBasic(Customizer.withDefaults())
            .formLogin(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails admin = User.withUsername("admin")
                .password(passwordEncoder.encode("password"))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
