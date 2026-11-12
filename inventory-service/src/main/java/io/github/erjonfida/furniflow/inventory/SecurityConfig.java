package io.github.erjonfida.furniflow.inventory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// Roles come from the token (see spring.security in application.yml)
@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(requests -> requests
				.requestMatchers(HttpMethod.GET, "/api/inventory/stock/*").permitAll() // the shop shows availability
				.requestMatchers(HttpMethod.GET, "/api/inventory/stock").hasRole("admin")
				.requestMatchers("/actuator/health/**").permitAll() // Kubernetes probes; shows only UP or DOWN
				.requestMatchers("/api/inventory/stock/*/restock").hasRole("admin")
				// Only order-service may hold stock, so nobody can tie units up without placing an order
				.requestMatchers("/api/inventory/reservations/**").hasRole("reserve-stock")
				.anyRequest().authenticated())
			.oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
			// Bearer tokens only, no cookies, so there is nothing for CSRF to abuse
			.csrf(csrf -> csrf.disable())
			.build();
	}

}
