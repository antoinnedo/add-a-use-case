package team.projectpulse.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.user.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ActiveAccountTokenValidatorTest {

    private final UserService users = mock(UserService.class);
    private final ActiveAccountTokenValidator validator = new ActiveAccountTokenValidator(this.users);

    @Test
    void acceptsAnActiveAccountAndRejectsAnInactiveOrMissingAccount() {
        given(this.users.isActiveAccount(1)).willReturn(true);

        assertThat(this.validator.validate(token().claim("userId", 1L).build()).hasErrors()).isFalse();
        assertThat(this.validator.validate(token().claim("userId", 2).build()).hasErrors()).isTrue();
    }

    @Test
    void malformedIdentifiersNeverReachTheAccountLookup() {
        assertThat(this.validator.validate(token().build()).hasErrors()).isTrue();
        for (Object invalidId : new Object[]{"bad", 1.5, 0, Integer.MAX_VALUE + 1L}) {
            assertThat(this.validator.validate(token().claim("userId", invalidId).build()).hasErrors()).isTrue();
        }
        verifyNoInteractions(this.users);
    }

    private Jwt.Builder token() {
        return Jwt.withTokenValue("test").header("alg", "RS256").subject("test");
    }
}
