package se.nordia.swedencore.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A thin JDBC helper bound to one connection inside one transaction.
 *
 * <p>Parameters are bound positionally. Supported types: {@code null}, {@link String}, {@link Integer}, {@link Long},
 * {@link Boolean}, {@link UUID}, {@link Instant}, {@link Enum} (bound by name), {@code byte[]}.
 */
public final class Tx {

    private final Connection connection;

    Tx(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    public int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params)) {
            return ps.executeUpdate();
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            T value = mapper.map(rs);
            if (rs.next()) {
                throw new DatabaseException("Expected at most one row for query: " + sql);
            }
            // A row whose mapped value is null (e.g. MAX over no rows) counts as "no value".
            return Optional.ofNullable(value);
        }
    }

    public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
            List<T> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapper.map(rs));
            }
            return result;
        }
    }

    public long queryLong(String sql, Object... params) throws SQLException {
        return queryOne(sql, rs -> rs.getLong(1), params)
                .orElseThrow(() -> new DatabaseException("Expected a row for query: " + sql));
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) {
                bind(ps, i + 1, params[i]);
            }
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
        return ps;
    }

    private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        switch (value) {
            case null -> ps.setNull(index, Types.NULL);
            case String s -> ps.setString(index, s);
            case Integer i -> ps.setInt(index, i);
            case Long l -> ps.setLong(index, l);
            case Boolean b -> ps.setBoolean(index, b);
            case UUID u -> ps.setObject(index, u);
            case Instant instant -> ps.setTimestamp(index, Timestamp.from(instant));
            case Enum<?> e -> ps.setString(index, e.name());
            case byte[] bytes -> ps.setBytes(index, bytes);
            default -> throw new IllegalArgumentException("Unsupported parameter type: " + value.getClass());
        }
    }

    // ---- ResultSet helpers ----

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    public static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
