package com.docsearch;

import org.springframework.boot.SpringApplication;

/**
 * Local dev convenience: boots the real app with Testcontainers standing in for
 * Postgres/Redis/Kafka. Elasticsearch isn't covered by TestcontainersConfiguration (see
 * DocumentApiIntegrationTest for that) - run `docker compose up -d elasticsearch`
 * separately first; the default `app.elasticsearch.uris=http://localhost:9200` will find it.
 */
public class TestDocumentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(DocumentServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
