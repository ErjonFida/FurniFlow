package io.github.erjonfida.furniflow.orders;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// Requests carry a Keycloak access token; roles come from it (see spring.security in application.yml)
@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		return http
			// Which orders a customer may see is checked in OrderService: only their own
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/api/orders/**").hasRole("customer")
				.requestMatchers("/actuator/health/**").permitAll() // Kubernetes probes; shows only UP or DOWN
				.anyRequest().authenticated())
			.oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
			// CSRF abuses cookies the browser sends by itself. A bearer token is only sent when our code adds it.
			.csrf(csrf -> csrf.disable())
			.build();
	}

}
