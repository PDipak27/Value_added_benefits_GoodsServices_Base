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
 * Billing Service — saga participant on the "billingService" channel.
 * PAY_NOW: two-phase Authorize → Capture (Refund compensates); amount &gt; 999
 * is declined. BILL_TO_MOBILE: CheckAccountLimit → AppendToLedger (ReverseLedger
 * compensates) against per-subscriber accounts. Every call writes a ledger row.
 */
@SpringBootApplication
@Import({SagaParticipantConfiguration.class,
         TramMessageProducerJdbcConfiguration.class,
         EventuateTramKafkaMessageConsumerConfiguration.class,
         TramCommandConsumerConfiguration.class})
public class BillingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingServiceApplication.class, args);
    }

    @Bean
    public CommandDispatcher billingCommandDispatcher(
            BillingCommandHandlers handlers,
            CommandDispatcherFactory factory) {
        return factory.make("billingCommandDispatcher", handlers.commandHandlerDefinitions());
    }
}
