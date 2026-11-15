package werger.http;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import werger.adapters.db.UserRepo;

/** Registers {@link SignedInUser} so controllers can take the signed-in user as a parameter. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final UserRepo users;

    /**
     * Resolves users through {@code users}.
     *
     * @param users the user repository
     */
    public WebConfig(UserRepo users) {
        this.users = users;
    }

    /**
     * Adds the signed-in user resolver.
     *
     * @param resolvers Spring MVC's resolver list
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new SignedInUser(users));
    }
}
