package dev.kousik.jobhunt;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	public PostgreSQLContainer postgresContainer() {
		// Pinned to match the local PG17 the engine actually runs against.
		// "latest" would silently drift away from production behaviour.
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
	}

}
