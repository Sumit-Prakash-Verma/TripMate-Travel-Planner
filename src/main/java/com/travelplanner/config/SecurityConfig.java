
package com.travelplanner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            UserDetailsService userDetailsService,
            SecurityContextRepository securityContextRepository) throws Exception {

        http
                .userDetailsService(userDetailsService)

                // CSRF will be enabled after frontend token integration.
                .csrf(AbstractHttpConfigurer::disable)

                // Store authentication in HTTP session.
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository))

                .authorizeHttpRequests(auth -> auth

                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/style.css",
                                "/script.js",
                                "/register-test.html",
                                "/login-test.html",
                                "/favicon.ico",
                                "/api/auth/register",
                                "/api/auth/login")
                        .permitAll()

                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/trips/**").authenticated()
                        .requestMatchers("/api/saved-itineraries/**").authenticated()
                        .requestMatchers("/api/ai/**").authenticated()

                        .anyRequest().permitAll());

        return http.build();
    }
}
