package com.ledgerflow.mockbank;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScenarioTest {

    @Test
    void readsTagFromVpa() {
        assertThat(Scenario.fromVpaTag("alice+timeout@upi")).contains(Scenario.TIMEOUT);
        assertThat(Scenario.fromVpaTag("cust_42+pending-decline")).contains(Scenario.PENDING_DECLINE);
        assertThat(Scenario.fromVpaTag("bob+FLAKY@okbank")).contains(Scenario.FLAKY);
    }

    @Test
    void noOrUnknownTagMeansRandom() {
        assertThat(Scenario.fromVpaTag("alice@upi")).isEmpty();
        assertThat(Scenario.fromVpaTag("alice+holiday@upi")).isEmpty();
    }
}
