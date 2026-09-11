package com.acme.catalog.config;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.URI;

@Configuration
@EnableConfigurationProperties({SearchProperties.class, IndexingProperties.class})
public class OpenSearchConfig {

    @Bean(destroyMethod = "close")
    public OpenSearchTransport openSearchTransport(SearchProperties properties) {
        SearchProperties.OpenSearch config = properties.opensearch();
        URI uri = URI.create(config.uri());
        HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort());

        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host)
                // Deliberately NOT the application's Jackson ObjectMapper. The index wire
                // format must not shift because someone tunes API serialisation.
                .setMapper(new JacksonJsonpMapper())
                .setRequestConfigCallback(requestConfig -> requestConfig
                        .setResponseTimeout(Timeout.ofMilliseconds(config.socketTimeoutMillis()))
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(config.connectTimeoutMillis())))
                .setConnectionConfigCallback(connectionConfig -> connectionConfig
                        .setConnectTimeout(Timeout.ofMilliseconds(config.connectTimeoutMillis())));

        if (StringUtils.hasText(config.username())) {
            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(new AuthScope(host),
                    new UsernamePasswordCredentials(config.username(),
                            config.password() == null ? new char[0] : config.password().toCharArray()));
            builder.setHttpClientConfigCallback(httpClient -> httpClient.setDefaultCredentialsProvider(credentials));
        }
        return builder.build();
    }

    @Bean
    public OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }
}
