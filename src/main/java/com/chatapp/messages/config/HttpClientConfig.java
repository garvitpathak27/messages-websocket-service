package com.chatapp.messages.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ChatServiceProperties.class)
public class HttpClientConfig {

    /** Spring Cloud LoadBalancer adds its interceptor to @LoadBalanced builders: "http://chat-service" -> an Eureka instance IP. */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * Explicit request factory: HttpURLConnection with a connect timeout and a per-read timeout that also bounds the
     * response body. Not left to Boot's auto-detection, which would change timeout semantics if a dependency
     * (Apache HttpClient, Jetty, Reactor Netty) ever appeared on the classpath.
     */
    @Bean
    public RestClient chatServiceRestClient(@LoadBalanced RestClient.Builder builder, ChatServiceProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
        return builder
                .baseUrl("http://" + props.serviceId())
                .requestFactory(factory)
                .build();
    }
}
