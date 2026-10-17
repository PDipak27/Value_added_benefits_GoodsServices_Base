package com.vab.observability;

import io.eventuate.common.id.IdGenerator;
import io.eventuate.common.id.Int128;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Logs once at startup which node component this JVM puts into its Eventuate outbox message ids.
 *
 * <p>Eventuate's default {@code ApplicationIdGenerator} builds ids as
 * {@code <epoch-millis>-<node(48 bits)><counter(16 bits)>}, where node = env {@code EVENTUATE_MAC_ADDRESS}
 * or else the first NIC's MAC. All services share one {@code eventuate.message} table, so two JVMs with the
 * same node (e.g. all services on one laptop/EC2 host) can mint the same id in the same millisecond →
 * duplicate key on insert → the sending handler fails and Eventuate stops that Kafka subscription.
 * Compare this line across services: every JVM writing to the same outbox must show a different node.
 */
public class OutboxIdNodeReporter implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(OutboxIdNodeReporter.class);

    private final ObjectProvider<IdGenerator> idGenerators;

    public OutboxIdNodeReporter(ObjectProvider<IdGenerator> idGenerators) {
        this.idGenerators = idGenerators;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        IdGenerator idGenerator = idGenerators.getIfUnique();
        if (idGenerator == null) return;   // service has no Tram outbox
        String generator = idGenerator.getClass().getSimpleName();
        if (idGenerator.databaseIdRequired()) {
            log.info("Eventuate outbox ids: generator={} (database-assigned ids, no node collision possible)", generator);
            return;
        }
        Int128 sample = idGenerator.genId(null, null);
        String node = String.format("%012x", sample.getLo() >>> 16);
        log.info("Eventuate outbox ids: generator={}, node={}, EVENTUATE_MAC_ADDRESS={}, sampleId={} "
                        + " must differ from every other JVM writing to the same eventuate.message table",
                generator, node, System.getenv("EVENTUATE_MAC_ADDRESS"), sample.asString());
    }
}
