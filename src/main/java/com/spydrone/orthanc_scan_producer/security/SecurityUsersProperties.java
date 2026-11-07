package com.spydrone.orthanc_scan_producer.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The users who can log in, from {@code app.security.users[n].username/password} (see README, Security).
 * Passwords carry their encoding prefix, e.g. {@code {bcrypt}...}. A stand-in until LDAP.
 */
@ConfigurationProperties("app.security")
public record SecurityUsersProperties(List<User> users) {

	public SecurityUsersProperties {
		users = users == null ? List.of() : List.copyOf(users);
	}

	public record User(String username, String password) {

		/** Keeps the password out of logs. */
		@Override
		public String toString() {
			return "User[username=" + username + "]";
		}
	}
}
