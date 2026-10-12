package com.vab.inventory.command;

import com.vab.events.inventory.AllocateInventoryCommand;
import com.vab.events.inventory.InventoryAllocated;
import com.vab.events.inventory.InventoryReserved;
import com.vab.events.inventory.ReserveInventoryCommand;
import com.vab.inventory.it.AbstractIntegrationTest;
import io.eventuate.tram.commands.common.ReplyMessageHeaders;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * No overselling under concurrency, on real Postgres: many reserve/allocate commands race for the last
 * units of one offer through the real transactional handlers. The PESSIMISTIC_WRITE locks on the item row
 * (and on the FREE license key) must let exactly "capacity" of them win; the rest fail cleanly with
 * OUT_OF_STOCK / POOL_EXHAUSTED, and no activation key is ever handed out twice.
 */
@SpringBootTest
class InventoryConcurrencyIT extends AbstractIntegrationTest {

    private static final int CONTENDERS = 20;

    @Autowired InventoryCommandHandlers handlers;
    @Autowired JdbcTemplate jdbc;

    @SuppressWarnings("unchecked")
    private static <C> CommandMessage<C> cmd(C command) {
        CommandMessage<C> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(command);
        return cm;
    }

    private static String newOfferCode(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private void insertItem(String offerCode, String type, int total) {
        jdbc.update("INSERT INTO inventory.inventory_items (offer_code, type, total, reserved, allocated) VALUES (?, ?, ?, 0, 0)",
                offerCode, type, total);
    }

    /** Fires all tasks at once (start latch) on a thread pool and returns their replies. */
    private static List<Message> race(List<Callable<Message>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Message>> futures = new ArrayList<>();
            for (Callable<Message> task : tasks) {
                futures.add(pool.submit(() -> { start.await(); return task.call(); }));
            }
            start.countDown();
            List<Message> replies = new ArrayList<>();
            for (Future<Message> f : futures) replies.add(f.get(60, TimeUnit.SECONDS));
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Message> successes(List<Message> replies) {
        return replies.stream().filter(m -> "SUCCESS".equals(m.getRequiredHeader(ReplyMessageHeaders.REPLY_OUTCOME))).toList();
    }

    private Map<String, Object> itemRow(String offerCode) {
        return jdbc.queryForMap("SELECT total, reserved, allocated FROM inventory.inventory_items WHERE offer_code = ?", offerCode);
    }

    /** 20 concurrent PAY_NOW reserves for a physical good with 5 in stock: exactly 5 succeed, 15 fail OUT_OF_STOCK, reserved == 5. */
    @Test
    void concurrent_reserves_never_oversell_physical_stock() throws Exception {
        String offer = newOfferCode("IT_PHYS");
        insertItem(offer, "PHYSICAL_GOOD", 5);

        List<Callable<Message>> tasks = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) tasks.add(() -> handlers.reserveInventory(cmd(new ReserveInventoryCommand(offer, 1))));
        List<Message> replies = race(tasks);

        assertThat(successes(replies)).hasSize(5);
        assertThat(replies).filteredOn(m -> "FAILURE".equals(m.getRequiredHeader(ReplyMessageHeaders.REPLY_OUTCOME)))
                .hasSize(CONTENDERS - 5)
                .allSatisfy(m -> assertThat(m.getPayload()).contains("OUT_OF_STOCK"));
        assertThat(((Number) itemRow(offer).get("reserved")).intValue()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.reservations WHERE offer_code = ?", Integer.class, offer))
                .isEqualTo(5);
    }

    /** Reserves (PAY_NOW) and allocates (BILL_TO_MOBILE) racing on the same item share one capacity: reserved + allocated == total, never more. */
    @Test
    void reserve_and_allocate_racing_share_one_capacity() throws Exception {
        String offer = newOfferCode("IT_MIX");
        insertItem(offer, "PHYSICAL_GOOD", 4);

        List<Callable<Message>> tasks = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) {
            tasks.add(i % 2 == 0
                    ? () -> handlers.reserveInventory(cmd(new ReserveInventoryCommand(offer, 1)))
                    : () -> handlers.allocateInventory(cmd(new AllocateInventoryCommand(offer, 1))));
        }
        List<Message> replies = race(tasks);

        assertThat(successes(replies)).hasSize(4);
        Map<String, Object> row = itemRow(offer);
        assertThat(((Number) row.get("reserved")).intValue() + ((Number) row.get("allocated")).intValue()).isEqualTo(4);
    }

    /** 20 concurrent reserves for a 3-key license pool: exactly 3 succeed, each with a DIFFERENT key, and all 3 keys end ALLOCATED. */
    @Test
    void concurrent_reserves_never_hand_out_the_same_license_key_twice() throws Exception {
        String offer = newOfferCode("IT_LIC");
        insertItem(offer, "SOFTWARE_LICENSE", 3);
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO inventory.license_keys (license_key, offer_code) VALUES (?, ?)", offer + "-KEY-" + i, offer);
        }

        List<Callable<Message>> tasks = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) tasks.add(() -> handlers.reserveInventory(cmd(new ReserveInventoryCommand(offer, 1))));
        List<Message> replies = race(tasks);

        List<String> keys = successes(replies).stream()
                .map(m -> Replies.payload(m, InventoryReserved.class).getActivationKey())
                .toList();
        assertThat(keys).hasSize(3).doesNotHaveDuplicates().allSatisfy(k -> assertThat(k).startsWith(offer + "-KEY-"));
        assertThat(replies).filteredOn(m -> "FAILURE".equals(m.getRequiredHeader(ReplyMessageHeaders.REPLY_OUTCOME)))
                .allSatisfy(m -> assertThat(m.getPayload()).contains("POOL_EXHAUSTED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.license_keys WHERE offer_code = ? AND status = 'ALLOCATED'",
                Integer.class, offer)).isEqualTo(3);
    }

    /** Concurrent BILL_TO_MOBILE allocations of the last license keys also stay unique (one-step path, same locking). */
    @Test
    void concurrent_allocations_never_hand_out_the_same_license_key_twice() throws Exception {
        String offer = newOfferCode("IT_LICA");
        insertItem(offer, "SOFTWARE_LICENSE", 2);
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO inventory.license_keys (license_key, offer_code) VALUES (?, ?)", offer + "-KEY-" + i, offer);
        }

        List<Callable<Message>> tasks = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) tasks.add(() -> handlers.allocateInventory(cmd(new AllocateInventoryCommand(offer, 1))));
        List<Message> replies = race(tasks);

        assertThat(successes(replies).stream().map(m -> Replies.payload(m, InventoryAllocated.class).getActivationKey()))
                .hasSize(2).doesNotHaveDuplicates();
    }
}
