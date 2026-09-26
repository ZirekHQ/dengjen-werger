package werger.http;

public record HealthStatus(String status, String version, String commit, String builtAt) {}
