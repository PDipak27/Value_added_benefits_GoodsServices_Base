package com.vab.fulfilment;

import com.vab.fulfilment.command.FulfilmentCommandHandlers;
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
 * Fulfilment Service — saga participant on the "fulfilmentService" channel
 * (Design/09, Q2(ii)). The PlaceOrderSaga sends a single {@code FulfilOrderCommand}
 * and this service dispatches by {@code productType} to the right delivery path:
 * <ul>
 *   <li>{@code PHYSICAL_GOOD}        → create a shipment (internal delivery stub) → trackingRef</li>
 *   <li>{@code DIGITAL_SUBSCRIPTION} → provision an OTT entitlement → externalRef</li>
 *   <li>{@code SOFTWARE_LICENSE}     → record the key already allocated at reserve → activationKey</li>
 * </ul>
 * Keeping the dispatch <em>inside</em> the participant means new product types add
 * a branch here, never a new saga step — the saga stays one linear orchestrator.
 */
@SpringBootApplication
@Import({SagaParticipantConfiguration.class,
         TramMessageProducerJdbcConfiguration.class,
         EventuateTramKafkaMessageConsumerConfiguration.class,
         TramCommandConsumerConfiguration.class})
public class FulfilmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FulfilmentServiceApplication.class, args);
    }

    @Bean
    public CommandDispatcher fulfilmentCommandDispatcher(
            FulfilmentCommandHandlers handlers,
            CommandDispatcherFactory factory) {
        return factory.make("fulfilmentCommandDispatcher", handlers.commandHandlerDefinitions());
    }
}
