package com.trustgrid.api.system;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

@Service
public class DependencyProbeService {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final KafkaAdmin kafkaAdmin;
    private final HttpClient httpClient;
    private final String opensearchUrl;
    private final String minioEndpoint;

    public DependencyProbeService(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            KafkaAdmin kafkaAdmin,
            @Value("${trustgrid.opensearch-url}") String opensearchUrl,
            @Value("${trustgrid.minio-endpoint}") String minioEndpoint
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.kafkaAdmin = kafkaAdmin;
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        this.opensearchUrl = opensearchUrl;
        this.minioEndpoint = minioEndpoint;
    }

    public List<SystemDependencyResponse> dependencies() {
        return List.of(
                database(),
                redis(),
                kafka(),
                opensearch(),
                minio()
        );
    }

    private SystemDependencyResponse database() {
        return probe("database", () -> {
            Integer result = jdbcTemplate.queryForObject("select 1", Integer.class);
            if (result == null || result != 1) {
                throw new IllegalStateException("database probe returned an unexpected result");
            }
            return "select 1 succeeded";
        });
    }

    private SystemDependencyResponse redis() {
        return probe("redis", () -> {
            if (redisTemplate.getConnectionFactory() == null) {
                throw new IllegalStateException("redis connection factory is unavailable");
            }
            try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
                String response = connection.ping();
                if (!"PONG".equalsIgnoreCase(response)) {
                    throw new IllegalStateException("redis ping returned " + response);
                }
            }
            return "ping succeeded";
        });
    }

    private SystemDependencyResponse kafka() {
        return probe("kafka", () -> {
            Map<String, Object> config = kafkaAdmin.getConfigurationProperties();
            Properties properties = new Properties();
            properties.putAll(config);
            properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000");
            properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "2000");
            try (AdminClient adminClient = AdminClient.create(properties)) {
                adminClient.describeCluster().nodes().get(2, TimeUnit.SECONDS);
            }
            return "cluster metadata available";
        });
    }

    private SystemDependencyResponse opensearch() {
        return httpProbe("opensearch", opensearchUrl);
    }

    private SystemDependencyResponse minio() {
        return httpProbe("minio", minioEndpoint + "/minio/health/live");
    }

    private SystemDependencyResponse httpProbe(String name, String url) {
        return probe(name, () -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("http status " + response.statusCode());
            }
            return "http probe succeeded";
        });
    }

    private SystemDependencyResponse probe(String name, Probe probe) {
        try {
            return new SystemDependencyResponse(name, "UP", probe.run());
        } catch (Exception exception) {
            return new SystemDependencyResponse(name, "DOWN", exception.getMessage());
        }
    }

    private interface Probe {
        String run() throws Exception;
    }
}
