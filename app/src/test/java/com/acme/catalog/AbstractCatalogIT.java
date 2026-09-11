package com.acme.catalog;

import org.opensearch.testcontainers.OpenSearchContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared container fixture. Both integration tests point at the same MySQL and the
 * same OpenSearch, so the suite pays for one set of containers rather than two.
 * <p>
 * OpenSearch is pinned to 2.19.x deliberately: Hibernate Search ships no OpenSearch 3
 * dialect in any released version, so a 3.x container would fail the backend version
 * check at startup.
 *
 * <h2>Why these are started by hand and not with @Container</h2>
 * The JUnit extension stops @Container fields when the class that declares them
 * finishes -- which, for containers declared on a shared base class, means the second
 * test class inherits stopped containers and fails on connection timeouts. Started
 * once here instead, and reaped by Ryuk when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractCatalogIT {

    static final String OPENSEARCH_IMAGE = "opensearchproject/opensearch:2.19.6";

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    static final OpenSearchContainer<?> OPENSEARCH =
            new OpenSearchContainer<>(DockerImageName.parse(OPENSEARCH_IMAGE));

    static {
        MYSQL.start();
        OPENSEARCH.start();
    }

    static String openSearchUri() {
        String address = OPENSEARCH.getHttpHostAddress();
        return address.startsWith("http") ? address : "http://" + address;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("catalog.search.opensearch.uri", AbstractCatalogIT::openSearchUri);
        registry.add("spring.jpa.properties.hibernate.search.backend.uris", AbstractCatalogIT::openSearchUri);
        registry.add("catalog.search.engine", () -> "OPENSEARCH");
        registry.add("catalog.demo.seed-enabled", () -> false);
        // The hand-rolled relay is driven by the test; Hibernate Search's own event
        // processor stays on, because its scheduling is the thing under test.
        registry.add("catalog.indexing.relay-enabled", () -> false);
    }
}
