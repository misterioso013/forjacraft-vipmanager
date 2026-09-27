package com.forjacraft.vipmanager.storage;

import com.forjacraft.vipmanager.service.TierState;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * Self-contained SQLite storage - one file, no external credentials. A single
 * shared connection guarded by a monitor lock is enough for this workload
 * (join events + a once-a-minute sweep); WAL mode lets reads and writes
 * interleave without heavyweight pooling.
 */
public final class SqliteVipStorage implements VipStorage {

    private final File databaseFile;
    private final Logger logger;
    private Connection connection;
    private final Object lock = new Object();

    public SqliteVipStorage(File databaseFile, Logger logger) {
        this.databaseFile = databaseFile;
        this.logger = logger;
    }

    @Override
    public void init() {
        synchronized (lock) {
            try {
                File parent = databaseFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    logger.warning("Could not create data directory: " + parent);
                }
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA busy_timeout=5000");
                }
                createSchema();
            } catch (ClassNotFoundException | SQLException e) {
                throw new IllegalStateException("Failed to initialize SQLite storage at " + databaseFile, e);
            }
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS vip_balances (
                        uuid            TEXT    NOT NULL,
                        tier            TEXT    NOT NULL,
                        banked_seconds  INTEGER NOT NULL DEFAULT 0,
                        active          INTEGER NOT NULL DEFAULT 0,
                        active_since    INTEGER,
                        updated_at      INTEGER NOT NULL,
                        PRIMARY KEY (uuid, tier)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_vip_balances_active ON vip_balances (active)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS vip_audit_log (
                        id                INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid              TEXT    NOT NULL,
                        tier              TEXT    NOT NULL,
                        event_type        TEXT    NOT NULL,
                        delta_seconds     INTEGER,
                        resulting_seconds INTEGER NOT NULL,
                        source            TEXT    NOT NULL,
                        actor             TEXT,
                        created_at        INTEGER NOT NULL
                    )
                    """);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    logger.log(Level.WARNING, "Error closing SQLite connection", e);
                }
            }
        }
    }

    @Override
    public Map<String, TierState> loadBalances(UUID uuid) {
        synchronized (lock) {
            Map<String, TierState> result = new HashMap<>();
            String sql = "SELECT tier, banked_seconds, active, active_since FROM vip_balances WHERE uuid = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
    }

    @Override
    public void saveBalance(UUID uuid, String tier, TierState state) {
        synchronized (lock) {
            String sql = """
                    INSERT INTO vip_balances (uuid, tier, banked_seconds, active, active_since, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid, tier) DO UPDATE SET
                        banked_seconds = excluded.banked_seconds,
                        active = excluded.active,
                        active_since = excluded.active_since,
                        updated_at = excluded.updated_at
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, tier);
                ps.setLong(3, state.bankedSeconds());
                ps.setInt(4, state.active() ? 1 : 0);
                if (state.activeSince() != null) {
                    ps.setLong(5, state.activeSince());
                } else {
                    ps.setNull(5, java.sql.Types.INTEGER);
                }
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to save balance for " + uuid + "/" + tier, e);
            }
        }
    }

    @Override
    public void deleteBalance(UUID uuid, String tier) {
        synchronized (lock) {
            String sql = "DELETE FROM vip_balances WHERE uuid = ? AND tier = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, tier);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to delete balance for " + uuid + "/" + tier, e);
            }
        }
    }

    @Override
    public Set<UUID> allPlayersWithActiveTier() {
        synchronized (lock) {
            Set<UUID> result = new HashSet<>();
            String sql = "SELECT DISTINCT uuid FROM vip_balances WHERE active = 1";
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    result.add(UUID.fromString(rs.getString("uuid")));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to list active players", e);
            }
            return result;
        }
    }

    @Override
    public void appendAudit(AuditEvent event) {
        synchronized (lock) {
            String sql = """
                    INSERT INTO vip_audit_log
                        (uuid, tier, event_type, delta_seconds, resulting_seconds, source, actor, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, event.uuid().toString());
                ps.setString(2, event.tier());
                ps.setString(3, event.eventType().name());
                if (event.deltaSeconds() != null) {
                    ps.setLong(4, event.deltaSeconds());
                } else {
                    ps.setNull(4, java.sql.Types.INTEGER);
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
}
