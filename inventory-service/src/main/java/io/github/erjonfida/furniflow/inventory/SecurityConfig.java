package io.github.erjonfida.furniflow.inventory;

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
				.requestMatchers(HttpMethod.GET, "/api/inventory/stock/*").permitAll() // the shop shows availability
				.requestMatchers("/api/inventory/stock/*/restock").hasRole("admin")
				// Only order-service may hold stock, so nobody can tie units up without placing an order
				.requestMatchers("/api/inventory/reservations/**").hasRole("reserve-stock")
				.anyRequest().authenticated())
			.oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
			// CSRF abuses cookies the browser sends by itself. A bearer token is only sent when our code adds it.
			.csrf(csrf -> csrf.disable())
			.build();
	}

}
