package vn.utetaste;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.utetaste.service.EmailAccountService;
import static org.assertj.core.api.Assertions.*;

class OtpSecretConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings={"short","CHANGE_ME_RANDOM_SECRET_AT_LEAST_32_BYTES"})
    void rejectsShortAndPublicPlaceholderSecrets(String secret) {
        assertThatThrownBy(()->new EmailAccountService(null,null,null,null,null,null,null,secret))
            .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(secret);
    }
}
