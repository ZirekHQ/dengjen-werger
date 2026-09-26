package werger.domain;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

public record User(
        UUID id,
        String authProviderRef,
        Role role,
        UserStatus status,
        ZoneId timezone,
        Optional<Commitment> commitment,
        Optional<String> email,
        boolean emailOptIn) {}
