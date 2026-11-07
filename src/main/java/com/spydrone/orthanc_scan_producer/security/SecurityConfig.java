package com.spydrone.orthanc_scan_producer.security;

import static org.springframework.security.config.Customizer.withDefaults;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * Every /api/** call needs a logged-in session (see {@link AuthController}); the UI shell and the health
 * endpoints stay open. Users come from {@link SecurityUsersProperties} for now. To move to LDAP, replace the
 * {@link AuthenticationManager} bean; the endpoints stay the same.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityUsersProperties.class)
public class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/auth/login", "/actuator/health/**", "/error").permitAll()
						.requestMatchers("/api/**").authenticated()
						// The UI bundle and its deep links: it has to load before anyone can log in.
						.anyRequest().permitAll())
				.exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				// XSRF-TOKEN cookie, echoed back in the X-XSRF-TOKEN header (Angular does this by itself).
				.csrf(csrf -> csrf.spa())
				.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
				.cors(withDefaults())
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(logout -> logout
						.logoutUrl("/api/auth/logout")
						.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	UserDetailsService userDetailsService(SecurityUsersProperties properties) {
		if (properties.users().isEmpty()) {
			log.warn("No users configured (app.security.users), so nobody can log in. See README, Security.");
		}
		return new InMemoryUserDetailsManager(properties.users().stream()
				.map(u -> User.withUsername(u.username()).password(u.password()).roles("USER").build())
				.toArray(UserDetails[]::new));
	}

	/** Checks logins for {@link AuthController}. Replace with an LDAP provider to move to LDAP. */
	@Bean
	AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
			PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}
}
