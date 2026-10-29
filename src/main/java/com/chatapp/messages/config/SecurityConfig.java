package com.chatapp.messages.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.chatapp.messages.client.AuthClient;
import com.chatapp.messages.security.BearerTokenAuthenticationFilter;
import com.chatapp.messages.security.RestAuthenticationEntryPoint;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.DispatcherType;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The only unauthenticated paths. Mirrored by BearerTokenAuthenticationFilter.shouldNotFilter. */
    static final String[] PUBLIC_PATHS = {"/actuator/health", "/actuator/health/**", "/ws/**"};

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, AuthClient authClient,
                                           RestAuthenticationEntryPoint entryPoint, ObjectMapper mapper) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)          // bearer header, no cookies: nothing for CSRF to forge
                .cors(AbstractHttpConfigurer::disable)          // CORS is the gateway's job alone (spec §16)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()   // /error after a 404/500 keeps its status
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new BearerTokenAuthenticationFilter(authClient, entryPoint, mapper),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
