package se.nordia.swedencore.market;

import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;
import se.nordia.swedencore.economy.Money;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Market statistics derived from real trades — never from NPC prices.
 *
 * <p>Sources: shop sales (players buying goods) and buy-order fills (companies/players buying raw materials).
 * Prices are volume-weighted averages per single item over a rolling window, compared with the previous window.
 * This is information, not a trading venue: acting on it still requires visiting shops or filling orders.
 */
public final class MarketService {

    /** Volume-weighted price over a window; {@code averagePrice} is null when nothing traded. */
    public record Window(long volume, Money value, Money averagePrice) {
    }

    /**
     * @param trendPercent change of the combined average price versus the previous window, null if not computable
     */
    public record Report(String material, Window shops, Window orders, Window combined, Window previous, Integer trendPercent,
                         Money bestShopPrice, Money bestOrderPrice) {
    }

    public record Traded(String material, long volume, Money value) {
    }

    private final Database database;
    private final Clock clock;
    private final Duration window;

    public MarketService(Database database, Clock clock, Duration window) {
        this.database = database;
        this.clock = clock;
        this.window = window;
    }

    public Duration window() {
        return window;
    }

    public Report report(String material) {
        Instant now = clock.instant();
        Instant from = now.minus(window);
        Instant previousFrom = from.minus(window);
        // Window ends are exclusive; the current window must include trades recorded at this very instant.
        Instant currentEnd = now.plusMillis(1);
        return database.inTransaction(tx -> {
            Window shops = shopWindow(tx, material, from, currentEnd);
            Window orders = orderWindow(tx, material, from, currentEnd);
            Window combined = combine(shops, orders);
            Window previous = combine(shopWindow(tx, material, previousFrom, from), orderWindow(tx, material, previousFrom, from));
            Integer trend = null;
            if (combined.averagePrice() != null && previous.averagePrice() != null && previous.averagePrice().isPositive()) {
                trend = (int) Math.round((combined.averagePrice().ore() - previous.averagePrice().ore()) * 100.0
                        / previous.averagePrice().ore());
            }
            Money bestShop = tx.queryOne("""
                            SELECT MIN(l.price / l.bundle_size) FROM shop_listings l JOIN shops s ON s.id = l.shop_id
                            WHERE l.material = ? AND s.status = 'OPEN'""",
                    rs -> {
                        long v = rs.getLong(1);
                        return rs.wasNull() ? null : Money.ofOre(v);
                    }, material).orElse(null);
            Money bestOrder = tx.queryOne("""
                            SELECT MAX(unit_price) FROM buy_orders WHERE material = ? AND status = 'OPEN' AND deadline_at > ?""",
                    rs -> {
                        long v = rs.getLong(1);
                        return rs.wasNull() ? null : Money.ofOre(v);
                    }, material, now).orElse(null);
            return new Report(material, shops, orders, combined, previous, trend, bestShop, bestOrder);
        });
    }

    /** Most traded materials by value in the current window. */
    public List<Traded> topTraded(int limit) {
        Instant now = clock.instant().plusMillis(1);
        Instant from = now.minus(window);
        return database.inTransaction(tx -> tx.queryList("""
                        SELECT material, SUM(volume) AS volume, SUM(value) AS value FROM (
                            SELECT material, items AS volume, total AS value FROM shop_sales WHERE created_at >= ? AND created_at < ?
                            UNION ALL
                            SELECT o.material, f.quantity, f.payout FROM buy_order_fills f JOIN buy_orders o ON o.id = f.order_id
                            WHERE f.created_at >= ? AND f.created_at < ?
                        ) t GROUP BY material ORDER BY value DESC, material LIMIT ?""",
                rs -> new Traded(rs.getString("material"), rs.getLong("volume"), Money.ofOre(rs.getLong("value"))),
                from, now, from, now, Math.clamp(limit, 1, 20)));
    }

    private static Window shopWindow(Tx tx, String material, Instant from, Instant to) throws SQLException {
        return tx.queryOne("SELECT COALESCE(SUM(items), 0), COALESCE(SUM(total), 0) FROM shop_sales WHERE material = ? AND created_at >= ? AND created_at < ?",
                rs -> window(rs.getLong(1), rs.getLong(2)), material, from, to).orElseThrow();
    }

    private static Window orderWindow(Tx tx, String material, Instant from, Instant to) throws SQLException {
        return tx.queryOne("""
                        SELECT COALESCE(SUM(f.quantity), 0), COALESCE(SUM(f.payout), 0) FROM buy_order_fills f
                        JOIN buy_orders o ON o.id = f.order_id
                        WHERE o.material = ? AND f.created_at >= ? AND f.created_at < ?""",
                rs -> window(rs.getLong(1), rs.getLong(2)), material, from, to).orElseThrow();
    }

    private static Window combine(Window a, Window b) {
        return window(a.volume() + b.volume(), a.value().ore() + b.value().ore());
    }

    private static Window window(long volume, long valueOre) {
        return new Window(volume, Money.ofOre(valueOre), volume == 0 ? null : Money.ofOre(valueOre / volume));
    }
}
