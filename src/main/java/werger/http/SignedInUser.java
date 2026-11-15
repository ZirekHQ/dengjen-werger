package werger.http;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import werger.adapters.db.UserRepo;
import werger.domain.User;
import werger.domain.UserStatus;

/**
 * Gives a controller method the signed-in {@link User}: the token's subject, provisioned on first sign-in. A suspended
 * user is refused here, so no controller has to remember to check.
 */
public class SignedInUser implements HandlerMethodArgumentResolver {
    private final UserRepo users;

    /**
     * Resolves users through {@code users}.
     *
     * @param users the user repository
     */
    public SignedInUser(UserRepo users) {
        this.users = users;
    }

    /**
     * Handles every controller parameter of type {@link User}.
     *
     * @param parameter the controller method parameter
     * @return whether it is a {@link User}
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == User.class;
    }

    /**
     * Finds or provisions the user the bearer token names.
     *
     * @param parameter the controller method parameter
     * @param mavContainer unused
     * @param webRequest unused
     * @param binderFactory unused
     * @return the active signed-in user
     * @throws ResponseStatusException 401 without a verified token, 403 for a suspended user
     */
    @Override
    public User resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        User user = users.findOrProvision(token.getToken().getSubject());
        if (user.status() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account suspended");
        }
        return user;
    }
}
