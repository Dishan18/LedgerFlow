package com.ledgerflow.mockbank;

import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

@Component
public class ScenarioPicker {

    private final ChaosProperties chaos;
    private final RandomGenerator random;

    public ScenarioPicker(ChaosProperties chaos, RandomGenerator random) {
        this.chaos = chaos;
        this.random = random;
    }

    public Scenario pick(String vpa) {
        return Scenario.fromVpaTag(vpa).orElseGet(this::random);
    }

    private Scenario random() {
        double roll = random.nextDouble();
        if ((roll -= chaos.errorRate()) < 0) {
            return Scenario.ERROR;
        }
        if ((roll -= chaos.timeoutRate()) < 0) {
            return Scenario.TIMEOUT;
        }
        if ((roll -= chaos.declineRate()) < 0) {
            return Scenario.DECLINE;
        }
        if ((roll -= chaos.pendingRate()) < 0) {
            return random.nextDouble() < chaos.pendingApproveRate() ? Scenario.PENDING : Scenario.PENDING_DECLINE;
        }
        return Scenario.APPROVE;
    }
}
