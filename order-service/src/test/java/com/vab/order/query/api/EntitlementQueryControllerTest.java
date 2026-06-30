package com.vab.order.query.api;

import com.vab.order.query.document.EntitlementView;
import com.vab.order.query.repository.EntitlementViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "My Benefits" read API: maps the entitlements_v1 projection to a DTO list for
 * the subscriber's ACTIVE entitlements.
 */
@ExtendWith(MockitoExtension.class)
class EntitlementQueryControllerTest {

    @Mock EntitlementViewRepository repo;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new EntitlementQueryController(repo)).build();
    }

    @Test
    void returns_active_benefits_mapped_to_dto() throws Exception {
        EntitlementView e = new EntitlementView();
        e.setOfferCode("OTT_X_1M");
        e.setProductType("DIGITAL_SUBSCRIPTION");
        e.setStatus("ACTIVE");
        e.setExternalRef("OTT-REF9");
        e.setActivatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(repo.findBySubscriberIdAndStatus("sub-9", "ACTIVE")).thenReturn(List.of(e));

        mvc().perform(get("/v1/entitlements").param("subscriberId", "sub-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].offerCode").value("OTT_X_1M"))
                .andExpect(jsonPath("$[0].productType").value("DIGITAL_SUBSCRIPTION"))
                .andExpect(jsonPath("$[0].externalRef").value("OTT-REF9"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void empty_when_no_active_benefits() throws Exception {
        when(repo.findBySubscriberIdAndStatus("sub-0", "ACTIVE")).thenReturn(List.of());

        mvc().perform(get("/v1/entitlements").param("subscriberId", "sub-0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
