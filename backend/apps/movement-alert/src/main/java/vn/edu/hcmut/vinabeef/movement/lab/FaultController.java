package vn.edu.hcmut.vinabeef.movement.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/lab/v1/faults")
@ConditionalOnProperty(name = "lab.faults.enabled", havingValue = "true")
public class FaultController {

    private final FaultSwitch faults;

    public FaultController(FaultSwitch faults) {
        this.faults = faults;
    }

    @PostMapping("/halt-after-commit")
    public Map<String, Object> arm(@RequestParam int commits) {
        faults.arm(commits);
        return Map.of("haltAfterCommits", commits);
    }

    @GetMapping
    public Map<String, Object> status() {
        return Map.of("haltAfterCommitsRemaining", faults.remaining());
    }
}
