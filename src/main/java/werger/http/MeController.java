package werger.http;

import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import werger.domain.User;

/** Who the signed-in volunteer is, so the page can greet them and show what they may do. */
@RestController
public class MeController {
    /** The fields the page needs about the signed-in volunteer; no contact details. */
    public record Me(UUID id, String role, String timezone) {}

    /**
     * The signed-in volunteer, created on their first sign-in.
     *
     * @param user the signed-in user
     * @return their id, role and timezone
     */
    @GetMapping("/me")
    public Me me(User user) {
        return new Me(
                user.id(),
                user.role().name().toLowerCase(Locale.ROOT),
                user.timezone().getId());
    }
}
