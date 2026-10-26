package com.grallum.application.security;

import static org.junit.jupiter.api.Assertions.*;

import com.grallum.application.config.JwtProperties;
import com.grallum.application.exception.BusinessException;
import com.grallum.application.model.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies real RSA signing and fixed audience, issuer, time and key trust boundaries. */
class JwtTokenCodecTest {
  @TempDir Path directory;
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /**
   * Generates isolated PEM fixtures and returns a codec without persisting production credentials.
   */
  private JwtTokenCodec codec(String issuer, Clock clock) throws Exception {
    var privatePath = directory.resolve("private.pem");
    var publicPath = directory.resolve("public.pem");
    if (!Files.exists(privatePath)) {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      pem(privatePath, "PRIVATE KEY", pair.getPrivate().getEncoded());
      pem(publicPath, "PUBLIC KEY", pair.getPublic().getEncoded());
    }
    return new JwtTokenCodec(
        new JwtProperties(
            issuer, "test", privatePath, Map.of("test", publicPath), null, null, Duration.ZERO),
        clock);
  }

  /** Writes only ephemeral test key material in PEM format. */
  private static void pem(Path path, String type, byte[] bytes) throws Exception {
    Files.writeString(
        path,
        "-----BEGIN "
            + type
            + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {10}).encodeToString(bytes)
            + "\n-----END "
            + type
            + "-----\n");
  }

  /** Creates a bounded management claim set for cryptographic verification tests. */
  private static JwtClaims claims() {
    return new JwtClaims(
        UUID.randomUUID(),
        TokenAudience.MANAGEMENT,
        3,
        UUID.randomUUID(),
        Optional.empty(),
        NOW,
        NOW,
        NOW.plusSeconds(600),
        List.of());
  }

  /** Round-trips real signatures and rejects use of a management token at the registry boundary. */
  @Test
  void validatesSignatureAndAudience() throws Exception {
    var codec = codec("issuer", Clock.fixed(NOW, ZoneOffset.UTC));
    var claims = claims();
    var token = codec.encode(claims);
    assertEquals(claims, codec.decode(token.value(), TokenAudience.MANAGEMENT));
    assertThrows(
        BusinessException.class, () -> codec.decode(token.value(), TokenAudience.REGISTRY));
  }

  /** Rejects expired and incorrectly issued tokens even when the signing key remains trusted. */
  @Test
  void rejectsWrongIssuerAndExpiredToken() throws Exception {
    var codec = codec("issuer", Clock.fixed(NOW, ZoneOffset.UTC));
    var token = codec.encode(claims());
    assertThrows(
        BusinessException.class,
        () ->
            codec("different", Clock.fixed(NOW, ZoneOffset.UTC))
                .decode(token.value(), TokenAudience.MANAGEMENT));
    assertThrows(
        BusinessException.class,
        () ->
            codec("issuer", Clock.fixed(NOW.plusSeconds(601), ZoneOffset.UTC))
                .decode(token.value(), TokenAudience.MANAGEMENT));
  }

  /** Rejects signature tampering and an unknown key id without fetching attacker-provided keys. */
  @Test
  void rejectsTamperingAndUnknownKeys() throws Exception {
    var codec = codec("issuer", Clock.fixed(NOW, ZoneOffset.UTC));
    String token = codec.encode(claims()).value();
    String[] parts = token.split("\\.");
    String bad =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"alg\":\"RS256\",\"kid\":\"evil\"}".getBytes());
    assertThrows(
        BusinessException.class,
        () -> codec.decode(bad + "." + parts[1] + "." + parts[2], TokenAudience.MANAGEMENT));
    assertThrows(
        BusinessException.class,
        () -> codec.decode(parts[0] + "." + parts[1] + ".AAAA", TokenAudience.MANAGEMENT));
  }

  /**
   * Redacted value objects cannot disclose token or password material through diagnostic
   * formatting.
   */
  @Test
  void secretsAreRedacted() {
    assertFalse(
        new UserCredentials("user", "secret-password").toString().contains("secret-password"));
    assertFalse(
        new AccessToken("secret-token", NOW, NOW.plusSeconds(60))
            .toString()
            .contains("secret-token"));
  }
}
