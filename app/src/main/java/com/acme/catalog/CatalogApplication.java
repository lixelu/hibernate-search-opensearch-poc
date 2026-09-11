package com.acme.catalog;

import com.acme.catalog.config.DemoProperties;
import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.indexer.IndexAdmin;
import com.acme.catalog.seed.DataSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticsearchRestHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Two auto-configurations are excluded on purpose.
 * <p>
 * Hibernate Search's OpenSearch backend pulls in Elasticsearch's low-level REST client
 * and its sniffer transitively. Spring Boot sees those classes and helpfully builds a
 * second, unrelated {@code RestClient} from {@code spring.elasticsearch.uris}, plus a
 * health indicator for it. Nothing in this application uses that client -- Hibernate
 * Search has its own, configured separately -- but the sniffer rewrites the node list
 * to the cluster's published address, which from outside the container network is
 * unreachable. The result is a service that works perfectly and reports DOWN.
 * <p>
 * That is not cosmetic: a false DOWN fails readiness probes and pulls a healthy
 * instance out of the load balancer.
 */
@SpringBootApplication(exclude = {
        ElasticsearchRestClientAutoConfiguration.class,
        ElasticsearchRestHealthContributorAutoConfiguration.class})
@EnableScheduling
@EnableConfigurationProperties(DemoProperties.class)
public class CatalogApplication {

    private static final Logger log = LoggerFactory.getLogger(CatalogApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }

    /**
     * Index bootstrap and demo seeding.
     * <p>
     * Note the try/catch around bootstrap: a data service must start, serve reads
     * from MySQL and accept writes even when the search cluster is unreachable.
     * Failing startup on OpenSearch would hand it a veto over your availability.
     */
    @Bean
    ApplicationRunner startupRunner(IndexAdmin indexAdmin, DataSeeder seeder,
                                    SearchProperties searchProperties, DemoProperties demoProperties) {
        return new ApplicationRunner() {
            @Override
            public void run(ApplicationArguments args) {
                if (searchProperties.opensearch().bootstrapIndex()) {
                    try {
                        indexAdmin.bootstrapIfMissing();
                    } catch (RuntimeException e) {
                        log.warn("OpenSearch bootstrap skipped ({}). Reads fall back to MySQL.", e.toString());
                    }
                }
                if (demoProperties.seedEnabled() && seeder.productCount() == 0) {
                    seeder.seed(demoProperties.products(), demoProperties.enqueueAfterSeed());
                }
            }
        };
    }
}
