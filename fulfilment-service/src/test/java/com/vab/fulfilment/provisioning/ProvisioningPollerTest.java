package com.vab.fulfilment.provisioning;

import com.vab.events.fulfilment.FulfilOrderCommand;
import com.vab.fulfilment.command.OttProvisioningService;
import com.vab.fulfilment.command.OttProvisioningService.Attempt;
import com.vab.fulfilment.ott.OttClient;
import io.eventuate.common.json.mapper.JSonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** One poller tick (Design/13 §6): pass 1 always runs, pass 2 only while the OTT breaker allows calls. */
@ExtendWith(MockitoExtension.class)
class ProvisioningPollerTest {

    @Mock ProvisioningTaskService service;
    @Mock OttProvisioningService provisioning;

    private ProvisioningPoller poller;

    @BeforeEach
    void setUp() {
        poller = new ProvisioningPoller(service, provisioning);
    }

    private static ProvisioningTask claimed(String orderId) {
        String cmd = JSonMapper.toJson(new FulfilOrderCommand(orderId, "sub-1", "OTT_PRIME_12M", "DIGITAL_SUBSCRIPTION", null));
        return new ProvisioningTask(orderId, cmd, "{}", ProvisioningTask.Status.IN_PROGRESS, false, null, false, 1,
                Instant.now(), Instant.now().plusSeconds(3600), "poller-x", null);
    }

    private static Attempt unavailable(String orderId) {
        return new Attempt(new OttClient.Result(OttClient.Outcome.UNAVAILABLE, null, "PROVISIONING_UNAVAILABLE", "503", false),
                orderId, null, null);
    }

    private static FulfilOrderCommand forOrder(String orderId) {
        return argThat(c -> c != null && orderId.equals(c.getOrderId()));
    }

    @Test
    void open_breaker_still_resolves_decisions_but_makes_no_ott_calls() {
        when(provisioning.isOttCallPermitted()).thenReturn(false);

        poller.tick();

        verify(service).reclaimExpiredLeases();
        verify(service).deleteExpiredTombstones();
        verify(service).resolveDecisions();
        verify(service, never()).claimDue(anyString());
        verify(service, never()).claimRevokesDue();
        verify(provisioning, never()).attempt(any());
    }

    @Test
    void a_failing_step_does_not_stop_the_rest_of_the_tick() {
        when(service.reclaimExpiredLeases()).thenThrow(new IllegalStateException("db blip"));
        when(service.resolveDecisions()).thenThrow(new IllegalStateException("db blip"));
        when(provisioning.isOttCallPermitted()).thenReturn(true);

        poller.tick();

        verify(service).deleteExpiredTombstones();
        verify(service).claimRevokesDue();
        verify(service).claimDue(anyString());
    }

    @Test
    void due_tasks_are_attempted_and_finished_by_this_poller() {
        when(provisioning.isOttCallPermitted()).thenReturn(true);
        ProvisioningTask task = claimed("ord-1");
        when(service.claimDue(anyString())).thenReturn(List.of(task));
        Attempt attempt = unavailable("ord-1");
        doReturn(attempt).when(provisioning).attempt(forOrder("ord-1"));

        poller.tick();

        verify(service).finish(eq(task), eq(attempt), argThat(owner -> owner.startsWith("poller-")));
    }

    @Test
    void a_crashing_attempt_is_left_to_its_lease_and_the_next_task_still_runs() {
        when(provisioning.isOttCallPermitted()).thenReturn(true);
        ProvisioningTask first = claimed("ord-1"), second = claimed("ord-2");
        when(service.claimDue(anyString())).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("bug")).when(provisioning).attempt(forOrder("ord-1"));
        doReturn(unavailable("ord-2")).when(provisioning).attempt(forOrder("ord-2"));

        poller.tick();

        verify(service, never()).finish(eq(first), any(), anyString());
        verify(service).finish(eq(second), any(), anyString());
    }

    @Test
    void owed_revokes_are_recorded_only_when_ott_confirms() {
        when(provisioning.isOttCallPermitted()).thenReturn(true);
        when(service.claimRevokesDue()).thenReturn(List.of("ord-ok", "ord-down"));
        doReturn(true).when(provisioning).revokeByOrder("ord-ok");
        doReturn(false).when(provisioning).revokeByOrder("ord-down");

        poller.tick();

        verify(service).revokeDone("ord-ok");
        verify(service, never()).revokeDone("ord-down");
    }
}
