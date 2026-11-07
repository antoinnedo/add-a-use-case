package team.projectpulse.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import team.projectpulse.user.UserService;

@Component
public class ActiveAccountTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final UserService userService;

    public ActiveAccountTokenValidator(UserService userService) {
        this.userService = userService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object claim = jwt.getClaim("userId");
        // The issuer writes an integer. JWT JSON decoding may represent it as Integer or Long.
        if ((claim instanceof Integer || claim instanceof Long) && claim instanceof Number number) {
            long id = number.longValue();
            if (id > 0 && id <= Integer.MAX_VALUE && this.userService.isActiveAccount((int) id)) {
                return OAuth2TokenValidatorResult.success();
            }
        }

        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The token does not identify an active account.", null));
    }
}
