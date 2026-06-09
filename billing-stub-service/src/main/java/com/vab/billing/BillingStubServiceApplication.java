package com.vab.billing;

import com.vab.billing.command.BillingCommandHandlers;
import io.eventuate.tram.commands.consumer.CommandDispatcher;
import io.eventuate.tram.commands.consumer.CommandDispatcherFactory;
import io.eventuate.tram.sagas.spring.participant.SagaParticipantConfiguration;
import io.eventuate.tram.spring.commands.consumer.TramCommandConsumerConfiguration;
import io.eventuate.tram.spring.consumer.kafka.EventuateTramKafkaMessageConsumerConfiguration;
import io.eventuate.tram.spring.messaging.producer.jdbc.TramMessageProducerJdbcConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Billing Stub Service — saga participant on the "billingService" channel.
 *
 * Simulated two-phase billing (Authorize → Capture, with Refund as
 * compensation). Demo rule: amount &gt; 999 INR is declined, exercising the
 * saga's compensation path. Every call writes a ledger row.
 */
@SpringBootApplication
@Import({SagaParticipantConfiguration.class,
         TramMessageProducerJdbcConfiguration.class,
         EventuateTramKafkaMessageConsumerConfiguration.class,
         TramCommandConsumerConfiguration.class})
public class BillingStubServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingStubServiceApplication.class, args);
    }

    @Bean
    public CommandDispatcher billingCommandDispatcher(
            BillingCommandHandlers handlers,
            CommandDispatcherFactory factory) {
        return factory.make("billingCommandDispatcher", handlers.commandHandlerDefinitions());
    }
}
