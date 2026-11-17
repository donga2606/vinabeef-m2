package vn.edu.hcmut.vinabeef.movement.lab;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lab-only fault hook for T04: halt the JVM after the Nth commit, BEFORE the broker ACK.
 * Disarmed by default; armed only through {@link FaultController}, which exists only when lab.faults.enabled=true.
 * In-memory, so a restart always comes back disarmed.
 */
@Component
public class FaultSwitch {

    private final AtomicInteger commitsUntilHalt = new AtomicInteger(-1);

    public void arm(int commits) {
        commitsUntilHalt.set(commits);
    }

    public int remaining() {
        return commitsUntilHalt.get();
    }

    /** Called after each commit. */
    public boolean haltNow() {
        return commitsUntilHalt.get() > 0 && commitsUntilHalt.decrementAndGet() == 0;
    }
}
