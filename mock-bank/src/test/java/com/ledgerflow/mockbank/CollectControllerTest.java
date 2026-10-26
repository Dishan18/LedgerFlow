package com.ledgerflow.mockbank;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerflow.common.bank.BankApi;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "mockbank.chaos.min-delay=0ms",
        "mockbank.chaos.max-delay=0ms",
        "mockbank.chaos.hang=0ms",
        "mockbank.chaos.error-rate=0",
        "mockbank.chaos.timeout-rate=0",
        "mockbank.chaos.decline-rate=0",
        "mockbank.chaos.pending-rate=0"
})
@AutoConfigureMockMvc
class CollectControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void collectThenQueryStatus() throws Exception {
        UUID id = UUID.randomUUID();
        collect(id, "alice@upi", 500)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.bankReference").isNotEmpty());

        mvc.perform(get(BankApi.COLLECT_BY_PAYMENT, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void unknownCollectIs404() throws Exception {
        mvc.perform(get(BankApi.COLLECT_BY_PAYMENT, UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void outageIs503() throws Exception {
        collect(UUID.randomUUID(), "alice+error@upi", 500).andExpect(status().isServiceUnavailable());
    }

    @Test
    void conflictingRetryIs409() throws Exception {
        UUID id = UUID.randomUUID();
        collect(id, "alice@upi", 500).andExpect(status().isOk());
        collect(id, "alice@upi", 999).andExpect(status().isConflict());
    }

    @Test
    void invalidRequestIs400() throws Exception {
        collect(UUID.randomUUID(), "alice@upi", -1).andExpect(status().isBadRequest());
    }

    private ResultActions collect(UUID paymentId, String vpa, long amount) throws Exception {
        return mvc.perform(post(BankApi.COLLECTS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentId\":\"" + paymentId + "\",\"payerVpa\":\"" + vpa + "\",\"amount\":" + amount + "}"));
    }
}
