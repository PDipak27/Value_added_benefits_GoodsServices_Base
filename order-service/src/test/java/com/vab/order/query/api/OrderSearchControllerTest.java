package com.vab.order.query.api;

import com.vab.order.query.document.OrderSearchView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §B3: the ops search endpoint passes the optional filters through to the
 * order_search_v1 query and returns the flattened rows.
 */
@ExtendWith(MockitoExtension.class)
class OrderSearchControllerTest {

    @Mock OrderSearchService service;

    @Test
    void ops_search_passes_filters_and_returns_rows() throws Exception {
        OrderSearchView v = new OrderSearchView();
        v.setOrderId("ord-1");
        v.setStatus("COMPLETED");
        v.setOfferCode("OTT_X_1M");
        when(service.search(eq("COMPLETED"), eq("OTT_X_1M"), any(), any(), eq(100)))
                .thenReturn(List.of(v));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderSearchController(service)).build();

        mvc.perform(get("/v1/ops/orders").param("status", "COMPLETED").param("offerCode", "OTT_X_1M"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value("ord-1"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }
}
