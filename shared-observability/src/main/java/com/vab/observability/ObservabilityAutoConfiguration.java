package com.vab.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * §C2 B-2: auto-wires correlation propagation. Just add the {@code shared-observability}
 * dependency — the HTTP filter registers in any servlet app; the Tram interceptor only
 * where {@code eventuate-tram-messaging} is on the classpath (kept as a string in
 * {@link ConditionalOnClass} so nothing loads the Tram type when it's absent).
 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> registration = new FilterRegistrationBean<>(new CorrelationFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);   // set the id before anything logs
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.eventuate.tram.messaging.common.MessageInterceptor")
    static class TramCorrelationConfiguration {
        @Bean
        public CorrelationMessageInterceptor correlationMessageInterceptor() {
            return new CorrelationMessageInterceptor();
        }
    }

    /** Startup log of this JVM's Eventuate outbox-id node, to spot id collisions between co-located services. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.eventuate.common.id.IdGenerator")
    static class OutboxIdReportConfiguration {
        @Bean
        public OutboxIdNodeReporter outboxIdNodeReporter(
                org.springframework.beans.factory.ObjectProvider<io.eventuate.common.id.IdGenerator> idGenerator) {
            return new OutboxIdNodeReporter(idGenerator);
        }
    }
}
