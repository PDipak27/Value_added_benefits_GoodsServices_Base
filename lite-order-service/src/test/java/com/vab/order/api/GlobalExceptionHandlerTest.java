package com.vab.order.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The advice maps every exception to a clean ProblemDetail: deliberate
 * ResponseStatusException keeps its status + reason, DataAccessException → 503,
 * anything else → 500 (no internals in the body), while Spring's own client errors
 * (e.g. unreadable body) keep their 4xx via the ResponseEntityExceptionHandler base.
 * The advice is identical in ott-service and catalog-service.
 */
class GlobalExceptionHandlerTest {

    @RestController
    @RequestMapping("/t")
    static class ThrowingController {
        @GetMapping("/conflict")
        String conflict() { throw new ResponseStatusException(HttpStatus.CONFLICT, "already parked"); }
        @GetMapping("/sql")
        String sql() { throw new DataAccessResourceFailureException("db down"); }
        @GetMapping("/boom")
        String boom() { throw new IllegalStateException("secret internals"); }
        @PostMapping("/echo")
        String echo(@RequestBody String body) { return body; }
    }

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void response_status_exception_keeps_status_and_reason() throws Exception {
        mvc.perform(get("/t/conflict"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.detail").value("already parked"));
    }

    @Test
    void data_access_exception_becomes_503_without_internals() throws Exception {
        mvc.perform(get("/t/sql"))
           .andExpect(status().isServiceUnavailable())
           .andExpect(jsonPath("$.detail").value("Storage temporarily unavailable"))
           .andExpect(content().string(not(containsString("db down"))));
    }

    @Test
    void unexpected_exception_becomes_500_without_internals() throws Exception {
        mvc.perform(get("/t/boom"))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.detail").value("Internal error"))
           .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void malformed_body_stays_400_not_500() throws Exception {
        // ResponseEntityExceptionHandler base: a client error keeps its 4xx status.
        mvc.perform(post("/t/echo").contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());
    }
}
