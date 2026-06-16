package com.vab.catalog.domain;

import com.vab.events.common.ProductType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Write-side logic only. Cache eviction is declarative ({@code @CacheEvict}) and
 * runs via a Spring proxy, which is intentionally absent here — these tests cover
 * the persistence behaviour, not the AOP wiring.
 */
@ExtendWith(MockitoExtension.class)
class OfferAdminServiceTest {

    @Mock OfferRepository offers;

    private OfferAdminService service;

    private static Offer offer(OfferStatus status) {
        return new Offer("OFF-1", "Test", "desc", ProductType.DIGITAL_SUBSCRIPTION,
                1000, "INR", "px-1", status, null, null, null, null);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new OfferAdminService(offers);
    }

    @Test
    void upsert_saves_and_returns_the_persisted_offer() {
        Offer in = offer(OfferStatus.PUBLISHED);
        when(offers.save(in)).thenReturn(in);

        assertThat(service.upsert(in)).isSameAs(in);
        verify(offers).save(in);
    }

    @Test
    void withdraw_sets_status_withdrawn_and_saves() {
        Offer existing = offer(OfferStatus.PUBLISHED);
        when(offers.findById("OFF-1")).thenReturn(Optional.of(existing));
        when(offers.save(any(Offer.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Offer> result = service.withdraw("OFF-1");

        assertThat(result).isPresent();
        ArgumentCaptor<Offer> saved = ArgumentCaptor.forClass(Offer.class);
        verify(offers).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(OfferStatus.WITHDRAWN);
    }

    @Test
    void withdraw_returns_empty_and_saves_nothing_when_not_found() {
        when(offers.findById("MISSING")).thenReturn(Optional.empty());

        assertThat(service.withdraw("MISSING")).isEmpty();
        verify(offers, never()).save(any());
    }
}
