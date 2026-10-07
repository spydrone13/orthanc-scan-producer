package com.spydrone.orthanc_scan_producer.web;

import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the orthanc-scan-ui build that the jar carries in static/ (see README). Deep links into the
 * app's routes get index.html so a reload or bookmark works; /api/** is untouched.
 */
@Configuration
public class UiConfig implements WebMvcConfigurer {

	private static final String STATIC = "classpath:/static/";

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addViewController("/scan-lot").setViewName("forward:/index.html");
		registry.addViewController("/scan-lot/**").setViewName("forward:/index.html");
		registry.addViewController("/admin").setViewName("forward:/index.html");
	}

	/** index.html is always revalidated so a new release is picked up; its hashed bundles never change. */
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/index.html")
				.addResourceLocations(STATIC)
				.setCacheControl(CacheControl.noCache());
		registry.addResourceHandler("/*.js", "/*.css")
				.addResourceLocations(STATIC)
				.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
	}
}
