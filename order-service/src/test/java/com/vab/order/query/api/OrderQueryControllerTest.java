package com.vab.order.query.api;

import com.vab.order.command.domain.OrderRepository;
import com.vab.order.query.document.OrderView;
import com.vab.order.query.repository.OrderViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §B2: the standalone audit-timeline endpoint serves OrderView.timeline; 404 when
 * the order has not projected yet.
 */
@ExtendWith(MockitoExtension.class)
class OrderQueryControllerTest {

    @Mock OrderViewRepository repo;
    @Mock OrderRepository orderRepo;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new OrderQueryController(repo, orderRepo)).build();
    }

    @Test
    void timeline_returns_the_audit_entries() throws Exception {
        OrderView v = new OrderView();
        v.setOrderId("ord-1");
        v.addTimelineEntry(Instant.parse("2026-01-01T00:00:00Z"), "PLACED");
        v.addTimelineEntry(Instant.parse("2026-01-01T00:01:00Z"), "COMPLETED");
        when(repo.findById("ord-1")).thenReturn(Optional.of(v));

        mvc().perform(get("/v1/orders/ord-1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PLACED"))
                .andExpect(jsonPath("$[1].status").value("COMPLETED"));
    }

    @Test
    void timeline_404_when_order_not_projected() throws Exception {
        when(repo.findById("missing")).thenReturn(Optional.empty());

        mvc().perform(get("/v1/orders/missing/timeline"))
                .andExpect(status().isNotFound());
    }
}
