package vn.utetaste.security;
import java.nio.charset.StandardCharsets;
import java.time.*;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import vn.utetaste.domain.*;
@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    public JwtService(@Value("${app.jwt-secret}") String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if(key.length<32) throw new IllegalStateException("JWT_SECRET phải có ít nhất 32 byte.");
        encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var verifier=NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key,"HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        verifier.setJwtValidator(JwtValidators.createDefaultWithIssuer("utetaste")); decoder=verifier;
    }
    public String staff(AppUser u) { return issue("staff:"+u.getId(),u.getTokenVersion(),Duration.ofHours(8)); }
    public String guest(DiningSession s) { return issue("guest:"+s.getId(),0,Duration.ofHours(6)); }
    private String issue(String subject,int version,Duration duration) {
        var claims=JwtClaimsSet.builder().issuer("utetaste").subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plus(duration)).claim("ver",version).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    public Jwt decode(String token) { return decoder.decode(token); }
}
