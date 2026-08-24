package dev.kousik.jobhunt.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client the board connectors share.
 *
 * Timeouts are set explicitly because the default is none, and a job board that
 * hangs would otherwise hold the nightly sweep open indefinitely. The read
 * timeout is generous rather than tight: a large Ashby board is a couple of
 * megabytes in a single response, and a slow board is a reason to wait, not a
 * reason to lose the run.
 *
 * No retry policy and no rate limiter yet. docs/PLAN.md puts Resilience4j in
 * this phase; it has been left out because the sweep runs once a day against
 * three well-behaved public APIs, and a dependency added before it earns its
 * place is harder to remove than to add. The seam is here when it is needed.
 */
@Configuration(proxyBeanMethods = false)
public class BoardClientConfig {

	/**
	 * Built from {@code RestClient.builder()} rather than the auto-configured
	 * builder, which Boot 4 does not register unless the RestClient starter is
	 * on the classpath. Standing it up directly also keeps this client free of
	 * whatever customisers the application picks up later -- these are calls to
	 * other people's public APIs, and they should not inherit anything meant
	 * for internal traffic.
	 */
	@Bean
	RestClient boardClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(10));
		factory.setReadTimeout(Duration.ofSeconds(45));
		return RestClient.builder()
				.requestFactory(factory)
				// Board APIs are public, but identifying the caller is basic manners
				// and makes this traceable from their side if it ever misbehaves.
				.defaultHeader("User-Agent", "job-hunt-engine (personal job search; single user)")
				.defaultHeader("Accept", "application/json")
				.build();
	}

}
