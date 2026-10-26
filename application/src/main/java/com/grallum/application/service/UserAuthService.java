package com.grallum.application.service;

import com.grallum.application.config.*;
import com.grallum.application.entity.*;
import com.grallum.application.exception.*;
import com.grallum.application.model.*;
import com.grallum.application.repository.*;
import com.grallum.application.security.JwtTokenCodec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Handles explicit credential exchanges and browser rotation without holding long database
 * transactions.
 */
@Service
public class UserAuthService {
  private final UserRepository users;
  private final UserTokenStateService state;
  private final UserAuthorizationService authorization;
  private final JwtTokenCodec codec;
  private final PasswordEncoder encoder;
  private final Clock clock;
  private final JwtProperties jwt;
  private final RefreshProperties refresh;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Attempt> attempts = new HashMap<>();
  private final String dummyHash;

  /**
   * Retains authentication collaborators and prepares a dummy password hash to reduce account
   * enumeration.
   */
  public UserAuthService(
      UserRepository users,
      UserTokenStateService state,
      UserAuthorizationService authorization,
      JwtTokenCodec codec,
      PasswordEncoder encoder,
      Clock clock,
      JwtProperties jwt,
      RefreshProperties refresh) {
    this.users = users;
    this.state = state;
    this.authorization = authorization;
    this.codec = codec;
    this.encoder = encoder;
    this.clock = clock;
    this.jwt = jwt;
    this.refresh = refresh;
    dummyHash = encoder.encode(secret());
  }

  /** Verifies credentials, commits a browser family and then signs its bounded access token. */
  public LoginTokens login(UserCredentials credentials) {
    var user = verify(credentials);
    var secret = secret();
    var session =
        state.openSession(
            user.id(),
            user.credentialVersion(),
            hash(secret),
            clock.instant().plus(refresh.absoluteTtl()));
    return new LoginTokens(
        access(
            user,
            TokenAudience.MANAGEMENT,
            Optional.of(session.familyId()),
            session.absoluteExpiresAt(),
            List.of()),
        secret,
        session.absoluteExpiresAt());
  }

  /**
   * Rotates a hashed secret once; failed replay results are rejected only after state transactions
   * commit.
   */
  public LoginTokens refresh(String refreshSecret) {
    if (refreshSecret == null || !refreshSecret.matches("[A-Za-z0-9_-]{43}")) throw denied();
    limit("refresh:" + hash(refreshSecret));
    var replacement = secret();
    var rotation = state.rotate(hash(refreshSecret), hash(replacement), clock.instant());
    if (rotation.status() != RotationStatus.ROTATED) throw denied();
    var session = rotation.session().orElseThrow();
    var user =
        users
            .findById(session.userId())
            .filter(UserEntity::enabled)
            .orElseThrow(UserAuthService::denied);
    return new LoginTokens(
        access(
            user,
            TokenAudience.MANAGEMENT,
            Optional.of(session.familyId()),
            session.absoluteExpiresAt(),
            List.of()),
        replacement,
        session.absoluteExpiresAt());
  }

  /** Revokes a known browser family; absent or malformed credentials remain idempotent. */
  public void logout(String refreshSecret) {
    if (refreshSecret != null && refreshSecret.matches("[A-Za-z0-9_-]{43}"))
      state.revokeFamily(hash(refreshSecret), clock.instant());
  }

  /** Exchanges explicit credentials for a management token without creating browser state. */
  public AccessToken managementToken(UserCredentials credentials) {
    return access(
        verify(credentials), TokenAudience.MANAGEMENT, Optional.empty(), Instant.MAX, List.of());
  }

  /**
   * Exchanges explicit Docker credentials for the intersection of requested and current repository
   * permissions.
   */
  public AccessToken registryToken(
      String service, List<RegistryScope> scopes, UserCredentials credentials) {
    if (!"grallum-registry".equals(service) || scopes.size() > 100)
      throw new BusinessException(
          BusinessErrorCode.INVALID_INPUT, "Invalid registry token request");
    var user = verify(credentials);
    return access(
        user,
        TokenAudience.REGISTRY,
        Optional.empty(),
        Instant.MAX,
        authorization.registryGrants(user.id(), scopes));
  }

  /**
   * Verifies JWT cryptography and current database state before returning a trusted request
   * identity.
   */
  public AuthenticatedUser authenticate(String token, TokenAudience expected) {
    var claims = codec.decode(token, expected);
    var user = state.requireActive(claims);
    return new AuthenticatedUser(user.id(), user.username(), user.administrator(), claims);
  }

  /** Verifies a bounded credential with uniform failures and per-account attempt limits. */
  private UserEntity verify(UserCredentials credentials) {
    if (credentials == null
        || credentials.username() == null
        || credentials.username().length() > 64
        || credentials.password() == null
        || credentials.password().getBytes(StandardCharsets.UTF_8).length > 72) throw denied();
    limit("login:" + hash(credentials.username()));
    var user = users.findByUsername(credentials.username());
    boolean matches =
        encoder.matches(
            credentials.password(), user.map(UserEntity::passwordHash).orElse(dummyHash));
    if (!matches || user.isEmpty() || !user.get().enabled()) throw denied();
    return user.get();
  }

  /**
   * Creates fixed-audience claims with an optional absolute family deadline and no cached
   * administrator role.
   */
  private AccessToken access(
      UserEntity user,
      TokenAudience audience,
      Optional<UUID> family,
      Instant deadline,
      List<RegistryGrant> grants) {
    var now = clock.instant();
    var expiry =
        now.plus(audience == TokenAudience.MANAGEMENT ? jwt.managementTtl() : jwt.registryTtl());
    if (expiry.isAfter(deadline)) expiry = deadline;
    return codec.encode(
        new JwtClaims(
            user.id(),
            audience,
            user.credentialVersion(),
            UUID.randomUUID(),
            family,
            now,
            now,
            expiry,
            grants));
  }

  /** Bounds credential work to thirty attempts per key per minute and ten thousand active keys. */
  private synchronized void limit(String key) {
    var now = clock.instant();
    attempts.entrySet().removeIf(e -> !now.isBefore(e.getValue().until()));
    var prior = attempts.get(key);
    if (prior == null && attempts.size() >= 10000 || prior != null && prior.count() >= 30)
      throw new BusinessException(
          BusinessErrorCode.RATE_LIMITED, "Too many authentication attempts");
    attempts.put(
        key,
        new Attempt(
            prior == null ? now.plusSeconds(60) : prior.until(),
            prior == null ? 1 : prior.count() + 1));
  }

  /** Returns a fresh 256-bit URL-safe opaque secret; never persists its plaintext. */
  private String secret() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Hashes a bounded opaque credential or rate-limit key without retaining its plaintext. */
  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Constructs a safe authentication failure without echoing supplied credentials. */
  private static BusinessException denied() {
    return new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "Invalid credentials");
  }

  /** Holds only a bounded counter and expiry, never the original account or credential. */
  private record Attempt(Instant until, int count) {}
}
