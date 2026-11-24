package com.vab.messaging;

import io.eventuate.messaging.kafka.consumer.MessageConsumerKafkaImpl;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Wires {@link ResilientMessageHandlerDecorator} + the DLQ store into every Tram service that has a
 * DataSource (Design/13 §4, §7). Eventuate picks the decorator up as a {@code MessageHandlerDecorator} bean
 * and sorts it by {@link ResilientMessageHandlerDecorator#getOrder()}.
 * Switch off with {@code vab.messaging.resilience.enabled=false}.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration"})
@ConditionalOnClass(name = {
        "io.eventuate.tram.consumer.common.MessageHandlerDecorator",
        "javax.sql.DataSource"})
@ConditionalOnProperty(prefix = "vab.messaging.resilience", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MessagingResilienceProperties.class)
public class MessagingResilienceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MessagingResilienceAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ParkedMessageStore parkedMessageStore(DataSource dataSource, PlatformTransactionManager txManager,
                                                 @Value("${eventuate.database.schema:eventuate}") String schema) {
        return new ParkedMessageStore(new JdbcTemplate(dataSource), txManager, schema);
    }

    @Bean
    public ResilientMessageHandlerDecorator resilientMessageHandlerDecorator(ParkedMessageStore store,
                                                                             MessagingResilienceProperties props,
                                                                             ObjectProvider<MessagingMetrics> metrics) {
        return new ResilientMessageHandlerDecorator(store, props, metrics.getIfAvailable(() -> MessagingMetrics.NOOP));
    }

    @Bean
    public MessageEnvelopeGuard messageEnvelopeGuard(ParkedMessageStore store, ObjectProvider<MessagingMetrics> metrics) {
        return new MessageEnvelopeGuard(store, metrics.getIfAvailable(() -> MessagingMetrics.NOOP));
    }

    /** Warn at startup, not at the first poison message, if the DLQ table was never created. */
    @Bean
    public ParkedMessageTableCheck parkedMessageTableCheck(ParkedMessageStore store) {
        return new ParkedMessageTableCheck(store);
    }

    static class ParkedMessageTableCheck implements ApplicationListener<ApplicationReadyEvent> {
        private final ParkedMessageStore store;

        ParkedMessageTableCheck(ParkedMessageStore store) {
            this.store = store;
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            try {
                if (!store.tableExists()) {
                    log.error("DLQ table parked_message is missing: a poison message will stop its subscription "
                            + "instead of being parked. Run deploy/postgres-init/05-parked-message.sql");
                }
            } catch (RuntimeException e) {
                log.warn("Could not check for the parked_message table: {}", e.toString());
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean
        MessagingMetrics messagingMetrics(ObjectProvider<MeterRegistry> registry) {
            MeterRegistry r = registry.getIfAvailable();
            return r == null ? MessagingMetrics.NOOP : new MicrometerMessagingMetrics(r);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "io.eventuate.messaging.kafka.consumer.MessageConsumerKafkaImpl",
            "org.springframework.boot.actuate.health.HealthIndicator"})
    static class HealthConfiguration {
        /** Contributor name {@code eventuateSubscriptions}: add it to the liveness group. */
        @Bean
        EventuateSubscriptionsHealthIndicator eventuateSubscriptionsHealthIndicator(
                ObjectProvider<MessageConsumerKafkaImpl> messageConsumer) {
            return new EventuateSubscriptionsHealthIndicator(messageConsumer);
        }
    }
}
