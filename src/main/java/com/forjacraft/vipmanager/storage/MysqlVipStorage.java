package com.forjacraft.vipmanager.storage;

import com.forjacraft.vipmanager.service.TierState;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared MySQL storage - same schema as {@link SqliteVipStorage}, in MySQL dialect,
 * for a future multi-server deployment (separate backend Paper servers per map, e.g.
 * lobby/world/recursos, all sharing one VIP state instead of each having its own
 * isolated SQLite file). Selected via {@code storage.type: mysql} in config.yml;
 * {@code sqlite} (the default) is unaffected by this class existing.
 *
 * <p>Pooled via HikariCP since multiple backend JVMs will open connections against
 * the same database concurrently - a single shared connection (as SQLite uses) isn't
 * appropriate here.</p>
 *
 * <p><b>Known limitation</b>: this only makes the VIP balance/kit-claim state
 * consistent across hosts. The LuckPerms group {@link com.forjacraft.vipmanager.integration.LuckPermsBridge}
 * applies is still local to whichever host's JVM ran the change - for tier-gated
 * permissions (shop discounts, Jobs boosts, etc.) to be consistent network-wide too,
 * the network's LuckPerms would also need to move off its current per-host local H2
 * storage to a shared database plus a messaging service (LuckPerms supports Redis for
 * this natively). That's a separate, network-wide infrastructure decision - out of
 * scope for this plugin.</p>
 */
public final class MysqlVipStorage implements VipStorage {

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Logger logger;
    private HikariDataSource dataSource;

    public MysqlVipStorage(String host, int port, String database, String username, String password, Logger logger) {
        this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&autoReconnect=true&characterEncoding=utf8";
        this.username = username;
        this.password = password;
        this.logger = logger;
    }

    @Override
    public void init() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setPoolName("VipManager-MySQL");
        config.setMaximumPoolSize(6);
        config.setMinimumIdle(1);
        this.dataSource = new HikariDataSource(config);
        createSchema();
    }

    private void createSchema() {
        try (Connection connection = dataSource.getConnection();
             Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS vip_balances (
                        uuid            VARCHAR(36) NOT NULL,
                        tier            VARCHAR(32) NOT NULL,
                        banked_seconds  BIGINT      NOT NULL DEFAULT 0,
                        active          TINYINT(1)  NOT NULL DEFAULT 0,
                        active_since    BIGINT      NULL,
                        updated_at      BIGINT      NOT NULL,
                        PRIMARY KEY (uuid, tier),
                        INDEX idx_vip_balances_active (active)
                    ) ENGINE=InnoDB
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS vip_audit_log (
                        id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                        uuid              VARCHAR(36) NOT NULL,
                        tier              VARCHAR(32) NOT NULL,
                        event_type        VARCHAR(32) NOT NULL,
                        delta_seconds     BIGINT      NULL,
                        resulting_seconds BIGINT      NOT NULL,
                        source            VARCHAR(32) NOT NULL,
                        actor             VARCHAR(64) NULL,
                        created_at        BIGINT      NOT NULL
                    ) ENGINE=InnoDB
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS kit_claims (
                        uuid            VARCHAR(36) NOT NULL,
                        kit_id          VARCHAR(64) NOT NULL,
                        last_claimed_at BIGINT      NOT NULL,
                        PRIMARY KEY (uuid, kit_id)
                    ) ENGINE=InnoDB
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS kit_cooldowns (
                        uuid              VARCHAR(36) NOT NULL,
                        kit_id            VARCHAR(64) NOT NULL,
                        remaining_seconds BIGINT      NOT NULL DEFAULT 0,
                        active            TINYINT(1)  NOT NULL DEFAULT 0,
                        active_since      BIGINT      NULL,
                        updated_at        BIGINT      NOT NULL,
                        PRIMARY KEY (uuid, kit_id)
                    ) ENGINE=InnoDB
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize MySQL storage at " + jdbcUrl, e);
        }
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Override
    public Map<String, TierState> loadBalances(UUID uuid) {
        Map<String, TierState> result = new HashMap<>();
        String sql = "SELECT tier, banked_seconds, active, active_since FROM vip_balances WHERE uuid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tier = rs.getString("tier");
                    long banked = rs.getLong("banked_seconds");
                    boolean active = rs.getInt("active") != 0;
                    long activeSinceRaw = rs.getLong("active_since");
                    Long activeSince = rs.wasNull() ? null : activeSinceRaw;
                    result.put(tier, new TierState(banked, active, activeSince));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load balances for " + uuid, e);
        }
        return result;
    }

    @Override
    public void saveBalance(UUID uuid, String tier, TierState state) {
        String sql = """
                INSERT INTO vip_balances (uuid, tier, banked_seconds, active, active_since, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    banked_seconds = VALUES(banked_seconds),
                    active = VALUES(active),
                    active_since = VALUES(active_since),
                    updated_at = VALUES(updated_at)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, tier);
            ps.setLong(3, state.bankedSeconds());
            ps.setInt(4, state.active() ? 1 : 0);
            if (state.activeSince() != null) {
                ps.setLong(5, state.activeSince());
            } else {
                ps.setNull(5, java.sql.Types.BIGINT);
            }
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save balance for " + uuid + "/" + tier, e);
        }
    }

    @Override
    public void deleteBalance(UUID uuid, String tier) {
        String sql = "DELETE FROM vip_balances WHERE uuid = ? AND tier = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, tier);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete balance for " + uuid + "/" + tier, e);
        }
    }

    @Override
    public Set<UUID> allPlayersWithActiveTier() {
        Set<UUID> result = new HashSet<>();
        String sql = "SELECT DISTINCT uuid FROM vip_balances WHERE active = 1";
        try (Connection connection = dataSource.getConnection();
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(UUID.fromString(rs.getString("uuid")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list active players", e);
        }
        return result;
    }

    @Override
    public java.util.Optional<Long> getKitClaimTime(UUID uuid, String kitId) {
        String sql = "SELECT last_claimed_at FROM kit_claims WHERE uuid = ? AND kit_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kitId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return java.util.Optional.of(rs.getLong("last_claimed_at"));
                }
                return java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read kit claim time for " + uuid + "/" + kitId, e);
        }
    }

    @Override
    public void recordKitClaim(UUID uuid, String kitId, long claimedAtMillis) {
        String sql = """
                INSERT INTO kit_claims (uuid, kit_id, last_claimed_at)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE last_claimed_at = VALUES(last_claimed_at)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kitId);
            ps.setLong(3, claimedAtMillis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record kit claim for " + uuid + "/" + kitId, e);
        }
    }

    @Override
    public java.util.Optional<TierState> getKitCooldown(UUID uuid, String kitId) {
        String sql = "SELECT remaining_seconds, active, active_since FROM kit_cooldowns WHERE uuid = ? AND kit_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kitId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return java.util.Optional.empty();
                }
                long remaining = rs.getLong("remaining_seconds");
                boolean active = rs.getInt("active") != 0;
                long activeSinceRaw = rs.getLong("active_since");
                Long activeSince = rs.wasNull() ? null : activeSinceRaw;
                return java.util.Optional.of(new TierState(remaining, active, activeSince));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read kit cooldown for " + uuid + "/" + kitId, e);
        }
    }

    @Override
    public void saveKitCooldown(UUID uuid, String kitId, TierState state) {
        String sql = """
                INSERT INTO kit_cooldowns (uuid, kit_id, remaining_seconds, active, active_since, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    remaining_seconds = VALUES(remaining_seconds),
                    active = VALUES(active),
                    active_since = VALUES(active_since),
                    updated_at = VALUES(updated_at)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kitId);
            ps.setLong(3, state.bankedSeconds());
            ps.setInt(4, state.active() ? 1 : 0);
            if (state.activeSince() != null) {
                ps.setLong(5, state.activeSince());
            } else {
                ps.setNull(5, java.sql.Types.BIGINT);
            }
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save kit cooldown for " + uuid + "/" + kitId, e);
        }
    }

    @Override
    public void appendAudit(AuditEvent event) {
        String sql = """
                INSERT INTO vip_audit_log
                    (uuid, tier, event_type, delta_seconds, resulting_seconds, source, actor, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, event.uuid().toString());
            ps.setString(2, event.tier());
            ps.setString(3, event.eventType().name());
            if (event.deltaSeconds() != null) {
                ps.setLong(4, event.deltaSeconds());
            } else {
                ps.setNull(4, java.sql.Types.BIGINT);
            }
            ps.setLong(5, event.resultingSeconds());
            ps.setString(6, event.source().name());
            ps.setString(7, event.actor());
            ps.setLong(8, event.createdAtMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to append audit log entry (non-fatal)", e);
        }
    }
}
