package io.github.erjonfida.furniflow.catalog;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MongoDBContainer mongoDbContainer() {
		// Same image as compose.yaml. MongoDB 8.3.13 refuses to start on Linux 6.19+ (the CI runners), 9.0 doesn't.
		return new MongoDBContainer(DockerImageName.parse("mongo:9.0"));
	}

}
