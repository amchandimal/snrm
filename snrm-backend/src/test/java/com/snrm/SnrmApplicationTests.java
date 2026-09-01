package com.snrm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Context load against a throwaway MySQL: repositories are tested on
 * Testcontainers-MySQL, never against the developer's local database.
 *
 * <p>The primary local setup has no Docker installed, so this test is skipped
 * rather than failed when no Docker daemon is reachable — it starts running the
 * moment one is. Correctness tests that need no database (engine micro-networks)
 * must not carry this condition.
 */
@SpringBootTest
@Testcontainers
@EnabledIf("dockerAvailable")
class SnrmApplicationTests {

	@Container
	@ServiceConnection
	static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8");

	static boolean dockerAvailable() {
		return DockerClientFactory.instance().isDockerAvailable();
	}

	@Test
	void contextLoads() {
	}

}
