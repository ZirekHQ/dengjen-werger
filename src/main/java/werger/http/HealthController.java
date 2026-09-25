package werger.http;

import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final BuildProperties build;

    public HealthController(BuildProperties build) {
        this.build = build;
    }

    @GetMapping("/healthz")
    public HealthStatus healthz() {
        return new HealthStatus("ok", build.getVersion(), build.get("gitCommit"), String.valueOf(build.getTime()));
    }
}
