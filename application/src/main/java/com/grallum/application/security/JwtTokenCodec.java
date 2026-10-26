package com.grallum.application.security;

import com.grallum.application.config.JwtProperties;
import com.grallum.application.exception.*;
import com.grallum.application.model.*;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/** Signs and verifies bounded JWTs against configured local RSA keys with a pinned algorithm. */
@Component
public class JwtTokenCodec {
  private final JwtProperties properties;
  private final Clock clock;
  private final JwtEncoder encoder;
  private final Map<String, JwtDecoder> decoders = new HashMap<>();

  /**
   * Loads trusted PEM keys, validates RSA strength and configures issuer and timestamp validators.
   */
  public JwtTokenCodec(JwtProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
    try {
      var factory = KeyFactory.getInstance("RSA");
      var privateKey =
          (RSAPrivateCrtKey)
              factory.generatePrivate(
                  new PKCS8EncodedKeySpec(pem(properties.privateKeyLocation(), "PRIVATE KEY")));
      RSAPublicKey active = null;
      for (var entry : properties.verificationKeys().entrySet()) {
        var key =
            (RSAPublicKey)
                factory.generatePublic(new X509EncodedKeySpec(pem(entry.getValue(), "PUBLIC KEY")));
        if (key.getModulus().bitLength() < 2048)
          throw new IllegalArgumentException("RSA keys must be at least 2048 bits");
        var decoder =
            NimbusJwtDecoder.withPublicKey(key)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        var timestamps = new JwtTimestampValidator(properties.clockSkew());
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        timestamps.setAllowEmptyNotBeforeClaim(false);
        decoder.setJwtValidator(
            new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(properties.issuer())));
        decoders.put(entry.getKey(), decoder);
        if (entry.getKey().equals(properties.activeKeyId())) active = key;
      }
      if (active == null
          || !active.getModulus().equals(privateKey.getModulus())
          || !active.getPublicExponent().equals(privateKey.getPublicExponent()))
        throw new IllegalArgumentException("Active RSA keys do not match");
      var jwk =
          new RSAKey.Builder(active).privateKey(privateKey).keyID(properties.activeKeyId()).build();
      encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    } catch (java.io.IOException | java.security.GeneralSecurityException e) {
      throw new IllegalArgumentException("Unable to load configured JWT keys", e);
    }
  }

  /**
   * Encodes claims with the configured issuer, audience and key; returns only the signed access
   * token.
   */
  public AccessToken encode(JwtClaims claims) {
    var builder =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .subject(claims.subject().toString())
            .audience(List.of(audience(claims.audience())))
            .id(claims.tokenId().toString())
            .issuedAt(claims.issuedAt())
            .notBefore(claims.notBefore())
            .expiresAt(claims.expiresAt())
            .claim("ver", claims.credentialVersion());
    claims.familyId().ifPresent(id -> builder.claim("sid", id.toString()));
    builder.claim(
        "access",
        claims.grants().stream()
            .map(
                grant ->
                    Map.of(
                        "type",
                        "repository",
                        "name",
                        grant.repository().externalName(),
                        "actions",
                        grant.actions().stream()
                            .map(action -> action.name().toLowerCase(Locale.ROOT))
                            .sorted()
                            .toList()))
            .toList());
    var token =
        encoder.encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256)
                    .keyId(properties.activeKeyId())
                    .type("JWT")
                    .build(),
                builder.build()));
    return new AccessToken(token.getTokenValue(), claims.issuedAt(), claims.expiresAt());
  }

  /**
   * Verifies signature, issuer, audience, temporal bounds and structured scopes; performs no
   * database access.
   */
  public JwtClaims decode(String token, TokenAudience expected) {
    try {
      if (token == null || token.length() > 16384) throw new IllegalArgumentException();
      var header = SignedJWT.parse(token).getHeader();
      if (!header.getAlgorithm().getName().equals("RS256")
          || header.getJWKURL() != null
          || header.getX509CertURL() != null
          || header.getJWK() != null) throw new IllegalArgumentException();
      var decoder = decoders.get(header.getKeyID());
      if (decoder == null) throw new IllegalArgumentException();
      var jwt = decoder.decode(token);
      var now = clock.instant();
      var ttl =
          expected == TokenAudience.MANAGEMENT
              ? properties.managementTtl()
              : properties.registryTtl();
      if (!jwt.getAudience().equals(List.of(audience(expected)))
          || jwt.getIssuedAt() == null
          || jwt.getNotBefore() == null
          || jwt.getExpiresAt() == null
          || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
          || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plus(ttl))
          || jwt.getIssuedAt().isAfter(now.plus(properties.clockSkew()))
          || jwt.getNotBefore().isBefore(jwt.getIssuedAt())
          || jwt.getNotBefore().isAfter(jwt.getExpiresAt())) throw new IllegalArgumentException();
      Object version = jwt.getClaim("ver");
      if (!(version instanceof Number number)
          || number.longValue() < 0
          || number.doubleValue() != number.longValue()) throw new IllegalArgumentException();
      List<RegistryGrant> grants = new ArrayList<>();
      Object raw = jwt.getClaim("access");
      if (!(raw instanceof List<?> entries) || entries.size() > 100)
        throw new IllegalArgumentException();
      for (Object value : entries) {
        if (!(value instanceof Map<?, ?> entry)
            || !"repository".equals(entry.get("type"))
            || !(entry.get("name") instanceof String name)
            || !(entry.get("actions") instanceof List<?> actions))
          throw new IllegalArgumentException();
        int slash = name.indexOf('/');
        if (slash < 1 || actions.size() > 3) throw new IllegalArgumentException();
        Set<RegistryAction> parsed = EnumSet.noneOf(RegistryAction.class);
        for (Object action : actions)
          parsed.add(RegistryAction.valueOf(((String) action).toUpperCase(Locale.ROOT)));
        grants.add(
            new RegistryGrant(
                new DockerRepositoryName(name.substring(0, slash), name.substring(slash + 1)),
                parsed));
      }
      var family = Optional.ofNullable(jwt.getClaimAsString("sid")).map(UUID::fromString);
      if (expected == TokenAudience.REGISTRY && family.isPresent()
          || expected == TokenAudience.MANAGEMENT && !grants.isEmpty())
        throw new IllegalArgumentException();
      return new JwtClaims(
          UUID.fromString(jwt.getSubject()),
          expected,
          number.longValue(),
          UUID.fromString(jwt.getId()),
          family,
          jwt.getIssuedAt(),
          jwt.getNotBefore(),
          jwt.getExpiresAt(),
          grants);
    } catch (Exception e) {
      throw new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "Invalid access token");
    }
  }

  /**
   * Maps the fixed enum to its external audience without accepting caller-supplied audience
   * strings.
   */
  private static String audience(TokenAudience value) {
    return value == TokenAudience.MANAGEMENT ? "grallum-management" : "grallum-registry";
  }

  /** Reads a single PEM block from a configured local path; no remote key discovery occurs. */
  private static byte[] pem(Path path, String type) throws java.io.IOException {
    String content =
        Files.readString(path)
            .replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "")
            .replaceAll("\\s", "");
    return Base64.getDecoder().decode(content);
  }
}
