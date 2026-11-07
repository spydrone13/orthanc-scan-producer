package com.spydrone.orthanc_scan_producer.security;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String username, @NotBlank String password) {

	/** Keeps the password out of logs. */
	@Override
	public String toString() {
		return "LoginRequest[username=" + username + "]";
	}
}
