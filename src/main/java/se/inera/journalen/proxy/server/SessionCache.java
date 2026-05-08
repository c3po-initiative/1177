package se.inera.journalen.proxy.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import se.inera.journalen.proxy.upstream.InvanarClient;

/**
 * Caches per-identifier upstream clients for a short window so that within one client's burst of
 * FHIR calls (search → individual reads), the upstream session and ephemeral resource UUIDs stay
 * stable. The portal regenerates UUIDs on every fresh login, so without this cache, a Bundle
 * search returns ids that immediately stop resolving.
 *
 * Keying is by credentials hash, not identifier alone — so a credential change forces a fresh login.
 */
public class SessionCache {

    private static final Logger log = LoggerFactory.getLogger(SessionCache.class);

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;

    public SessionCache(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * Returns a logged-in client for the given creds, reusing a cached session if it is younger
     * than the TTL. Always returns a usable client or throws.
     */
    public InvanarClient acquire(String identifier, String password,
                                  ClientFactory factory) throws IOException {
        String key = key(identifier, password);
        Entry existing = entries.get(key);
        if (existing != null && !existing.isExpired(ttl)) {
            log.debug("Reusing cached session for {}", redact(identifier));
            return existing.client;
        }
        synchronized (this) {
            existing = entries.get(key);
            if (existing != null && !existing.isExpired(ttl)) return existing.client;
            if (existing != null) {
                quietlyClose(existing.client);
                entries.remove(key);
            }
            InvanarClient fresh = factory.create();
            fresh.login(identifier, password);
            entries.put(key, new Entry(fresh, Instant.now()));
            log.info("Logged in upstream session for {} (ttl {}s)", redact(identifier), ttl.toSeconds());
            return fresh;
        }
    }

    /** Drops expired entries. Safe to call as a periodic sweep. */
    public void sweep() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(e -> {
            if (e.getValue().isExpired(ttl, now)) {
                quietlyClose(e.getValue().client);
                return true;
            }
            return false;
        });
    }

    public void closeAll() {
        entries.values().forEach(e -> quietlyClose(e.client));
        entries.clear();
    }

    public interface ClientFactory {
        InvanarClient create();
    }

    private static String key(String id, String pw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((id + ":" + pw).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return id; // fallback (shouldn't happen)
        }
    }

    private static String redact(String identifier) {
        if (identifier == null || identifier.length() < 4) return "***";
        return identifier.substring(0, 4) + "****";
    }

    private static void quietlyClose(InvanarClient c) {
        try { c.close(); } catch (IOException ignored) {}
    }

    private record Entry(InvanarClient client, Instant loggedInAt) {
        boolean isExpired(Duration ttl) { return isExpired(ttl, Instant.now()); }
        boolean isExpired(Duration ttl, Instant now) {
            return loggedInAt.plus(ttl).isBefore(now);
        }
    }
}
