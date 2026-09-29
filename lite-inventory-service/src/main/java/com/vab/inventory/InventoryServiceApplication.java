package com.vab.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.vab.inventory.command.InventoryCommandHandlers;

import io.eventuate.tram.commands.consumer.CommandDispatcher;
import io.eventuate.tram.sagas.spring.participant.SagaParticipantConfiguration;
import io.eventuate.tram.spring.commands.consumer.TramCommandConsumerConfiguration;
import io.eventuate.tram.spring.consumer.kafka.EventuateTramKafkaMessageConsumerConfiguration;
import io.eventuate.tram.spring.messaging.producer.jdbc.TramMessageProducerJdbcConfiguration;

@SpringBootApplication
@EnableScheduling
@Import({SagaParticipantConfiguration.class, TramMessageProducerJdbcConfiguration.class,
			EventuateTramKafkaMessageConsumerConfiguration.class,
			TramCommandConsumerConfiguration.class})
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }

    @Bean
    public CommandDispatcher inventoryCommandDispatcher(
            InventoryCommandHandlers handlers,
            io.eventuate.tram.commands.consumer.CommandDispatcherFactory factory) {
        return factory.make("inventoryCommandDispatcher", handlers.commandHandlerDefinitions());
    }
}
