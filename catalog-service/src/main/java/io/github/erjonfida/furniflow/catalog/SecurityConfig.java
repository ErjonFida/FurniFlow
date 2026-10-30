package io.github.erjonfida.furniflow.catalog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// Requests carry a Keycloak access token; roles come from it (see spring.security in application.yml)
@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(requests -> requests
				.requestMatchers(HttpMethod.GET, "/api/catalog/**").permitAll() // anyone may browse the shop
				.requestMatchers("/api/catalog/**").hasRole("admin")
				.anyRequest().authenticated())
			.oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
			// Bearer tokens only, no cookies, so there is nothing for CSRF to abuse
			.csrf(csrf -> csrf.disable())
			.build();
	}

}
