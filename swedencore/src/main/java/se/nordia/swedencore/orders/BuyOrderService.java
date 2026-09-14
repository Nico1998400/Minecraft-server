package se.nordia.swedencore.orders;

import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.companies.CompanyService;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;
import se.nordia.swedencore.economy.Account;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.economy.TransactionType;
import se.nordia.swedencore.economy.TransferReceipt;
import se.nordia.swedencore.economy.TransferRequest;
import se.nordia.swedencore.inventory.ItemStashService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Buy orders connect producers with buyers without an auction house: a buyer publishes demand with an escrowed budget,
 * any seller delivers into it and is paid immediately. Goods arrive in the buyer's stash.
 *
 * <p>Differences from contracts: orders are non-exclusive (many sellers), have no skill/XP/reputation rewards
 * (they are market trades, not trust relationships), and pay a fixed unit price per item.
 */
public final class BuyOrderService {

    public record Config(int feePercent, int maxActivePerIssuer, int maxDurationHours) {
        public Config {
            if (feePercent < 0 || feePercent > 50 || maxActivePerIssuer < 1 || maxDurationHours < 1) {
                throw new IllegalArgumentException("Invalid buy order configuration");
            }
        }

        public static Config defaults() {
            return new Config(1, 10, 24 * 14);
        }
    }

    public record FillResult(BuyOrder order, int quantity, Money payout) {
    }

    private final Database database;
    private final EconomyService economy;
    private final CompanyService companies;
    private final ItemStashService stash;
    private final Config config;
    private final Clock clock;

    public BuyOrderService(Database database, EconomyService economy, CompanyService companies, ItemStashService stash,
                           Config config, Clock clock) {
        this.database = database;
        this.economy = economy;
        this.companies = companies;
        this.stash = stash;
        this.config = config;
        this.clock = clock;
        companies.addDissolutionCheck((tx, companyId) -> {
            if (tx.queryLong("SELECT count(*) FROM buy_orders WHERE issuer_company_id = ? AND status = 'OPEN'", companyId) > 0) {
                throw new DomainException("company.dissolve_has_orders");
            }
        });
    }

    public Config config() {
        return config;
    }

    public BuyOrder create(UUID actor, Long companyId, String material, int quantity, Money unitPrice, int hours) {
        if (material == null || !ItemStashService.MATERIAL.matcher(material).matches()) {
            throw new DomainException("contract.invalid_material");
        }
        if (quantity < 1 || quantity > 1_000_000) {
            throw new DomainException("contract.invalid_quantity");
        }
        if (!unitPrice.isPositive()) {
            throw new DomainException("economy.invalid_amount");
        }
        if (hours < 1 || hours > config.maxDurationHours()) {
            throw DomainException.of("contract.invalid_duration", "max", config.maxDurationHours());
        }
        Money budget;
        try {
            budget = unitPrice.times(quantity);
        } catch (ArithmeticException e) {
            throw new DomainException("economy.amount_too_large");
        }
        Money fee = Money.ofOre(Math.multiplyExact(budget.ore(), config.feePercent()) / 100);
        return database.inTransaction(tx -> {
            Account payer;
            long active;
            if (companyId != null) {
                companies.lockActive(tx, companyId);
                companies.requireRole(tx, companyId, actor, CompanyRole.OWNER);
                payer = economy.requireAccount(tx, AccountOwner.company(companyId));
                active = tx.queryLong("SELECT count(*) FROM buy_orders WHERE issuer_company_id = ? AND status = 'OPEN'", companyId);
            } else {
                tx.queryOne("SELECT uuid FROM players WHERE uuid = ? FOR UPDATE", rs -> true, actor)
                        .orElseThrow(() -> new DomainException("player.unknown"));
                payer = economy.requireAccount(tx, AccountOwner.player(actor));
                active = tx.queryLong("SELECT count(*) FROM buy_orders WHERE issuer_player_uuid = ? AND status = 'OPEN'", actor);
            }
            if (active >= config.maxActivePerIssuer()) {
                throw DomainException.of("order.too_many_active", "max", config.maxActivePerIssuer());
            }
            long id = tx.queryLong("""
                            INSERT INTO buy_orders (issuer_type, issuer_player_uuid, issuer_company_id, created_by, material,
                                                    quantity, unit_price, deadline_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    companyId != null ? "COMPANY" : "PLAYER", companyId != null ? null : actor, companyId, actor, material,
                    quantity, unitPrice.ore(), clock.instant().plus(Duration.ofHours(hours)));
            Account escrow = economy.getOrCreateAccount(tx, AccountOwner.buyOrder(id), Account.ESCROW);
            economy.transfer(tx, new TransferRequest(payer.id(), escrow.id(), budget, TransactionType.ORDER_ESCROW,
                    "order-escrow:" + id, actor, "ORDER", Long.toString(id), null));
            if (fee.isPositive()) {
                economy.burn(tx, payer.id(), fee, TransactionType.ORDER_FEE, "order-fee:" + id, actor);
            }
            return find(tx, id).orElseThrow();
        });
    }

    /**
     * Sells items into an order. The caller already removed exactly these items from the seller's inventory.
     *
     * @param token unique per fill attempt (resolves ambiguous failures, see {@link #fillRecorded})
     */
    public FillResult fill(UUID seller, long orderId, List<ItemStashService.StashItem> items, UUID token) {
        if (items == null || items.isEmpty()) {
            throw new DomainException("contract.nothing_to_deliver");
        }
        int quantity = 0;
        for (ItemStashService.StashItem item : items) {
            quantity = Math.addExact(quantity, item.amount());
        }
        final int sold = quantity;
        return database.inTransaction(tx -> {
            BuyOrder order = lock(tx, orderId);
            if (order.status() != BuyOrder.Status.OPEN) {
                throw new DomainException("order.not_open");
            }
            if (!clock.instant().isBefore(order.deadline())) {
                throw new DomainException("contract.expired");
            }
            if (isIssuerSide(tx, order, seller)) {
                throw new DomainException("order.own_order");
            }
            if (tx.queryOne("SELECT 1 FROM buy_order_fills WHERE token = ?", rs -> true, token).isPresent()) {
                throw new DomainException("order.duplicate_fill");
            }
            for (ItemStashService.StashItem item : items) {
                if (!item.material().equals(order.material())) {
                    throw new DomainException("contract.wrong_material");
                }
            }
            if (sold > order.remaining()) {
                throw DomainException.of("contract.too_many_items", "remaining", order.remaining());
            }
            Money payout = order.unitPrice().times(sold);
            long fillId = tx.queryLong("""
                            INSERT INTO buy_order_fills (order_id, seller_uuid, token, quantity, payout, created_at)
                            VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                    orderId, seller, token, sold, payout.ore(), clock.instant());
            Account escrow = economy.findAccount(tx, AccountOwner.buyOrder(orderId), Account.ESCROW).orElseThrow();
            Account sellerAccount = economy.requireAccount(tx, AccountOwner.player(seller));
            TransferReceipt receipt = economy.transfer(tx, new TransferRequest(escrow.id(), sellerAccount.id(), payout,
                    TransactionType.ORDER_PAYOUT, "order-fill:" + fillId, seller, "ORDER", Long.toString(orderId), null));
            tx.update("UPDATE buy_order_fills SET transaction_id = ? WHERE id = ?", receipt.transactionId(), fillId);
            ItemStashService.Owner owner = order.issuerType() == BuyOrder.IssuerType.PLAYER
                    ? ItemStashService.Owner.player(order.issuerPlayer())
                    : ItemStashService.Owner.company(order.issuerCompanyId());
            stash.requireCapacity(tx, owner, items.size());
            stash.deposit(tx, owner, items, "ORDER", Long.toString(orderId));
            boolean complete = order.filled() + sold == order.quantity();
            tx.update("""
                            UPDATE buy_orders SET filled = filled + ?,
                                   status = CASE WHEN ? THEN 'FILLED' ELSE status END,
                                   closed_at = CASE WHEN ? THEN now() ELSE closed_at END
                            WHERE id = ?""", sold, complete, complete, orderId);
            return new FillResult(find(tx, orderId).orElseThrow(), sold, payout);
        });
    }

    public boolean fillRecorded(UUID token) {
        return database.inTransaction(tx -> tx.queryOne("SELECT 1 FROM buy_order_fills WHERE token = ?", rs -> true, token)).isPresent();
    }

    public BuyOrder cancel(UUID actor, long orderId) {
        return database.inTransaction(tx -> {
            BuyOrder order = lock(tx, orderId);
            if (order.status() != BuyOrder.Status.OPEN) {
                throw new DomainException("order.not_open");
            }
            if (order.issuerType() == BuyOrder.IssuerType.PLAYER) {
                if (!actor.equals(order.issuerPlayer())) {
                    throw new DomainException("order.not_issuer");
                }
            } else {
                if (companies.roleOf(tx, order.issuerCompanyId(), actor).isEmpty()) {
                    throw new DomainException("order.not_issuer");
                }
                companies.requireRole(tx, order.issuerCompanyId(), actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            }
            close(tx, order, "CANCELLED");
            return find(tx, orderId).orElseThrow();
        });
    }

    /** Bankruptcy: cancels the company's open buy orders, refunding escrow to the company. */
    public int closeAllForCompany(Tx tx, long companyId) throws SQLException {
        List<Long> ids = tx.queryList("SELECT id FROM buy_orders WHERE issuer_company_id = ? AND status = 'OPEN' ORDER BY id",
                rs -> rs.getLong(1), companyId);
        for (long id : ids) {
            close(tx, lock(tx, id), "CANCELLED");
        }
        return ids.size();
    }

    public List<BuyOrder> expireDue() {
        List<Long> due = database.inTransaction(tx -> tx.queryList(
                "SELECT id FROM buy_orders WHERE status = 'OPEN' AND deadline_at <= ? ORDER BY deadline_at LIMIT 100",
                rs -> rs.getLong(1), clock.instant()));
        List<BuyOrder> expired = new ArrayList<>();
        for (long id : due) {
            Optional<BuyOrder> result = database.inTransaction(tx -> {
                BuyOrder order = lock(tx, id);
                if (order.status() != BuyOrder.Status.OPEN || order.deadline().isAfter(clock.instant())) {
                    return Optional.<BuyOrder>empty();
                }
                close(tx, order, "EXPIRED");
                return find(tx, id);
            });
            result.ifPresent(expired::add);
        }
        return expired;
    }

    private void close(Tx tx, BuyOrder order, String status) throws SQLException {
        Account escrow = economy.findAccount(tx, AccountOwner.buyOrder(order.id()), Account.ESCROW).orElseThrow();
        if (escrow.balance().isPositive()) {
            AccountOwner issuer = order.issuerType() == BuyOrder.IssuerType.PLAYER
                    ? AccountOwner.player(order.issuerPlayer()) : AccountOwner.company(order.issuerCompanyId());
            Account target = economy.requireAccount(tx, issuer);
            economy.transfer(tx, new TransferRequest(escrow.id(), target.id(), escrow.balance(), TransactionType.ORDER_REFUND,
                    "order-refund:" + order.id(), null, "ORDER", Long.toString(order.id()), null));
        }
        tx.update("UPDATE buy_orders SET status = ?, closed_at = now() WHERE id = ?", status, order.id());
    }

    // ------------------------------------------------------------------ queries

    public Optional<BuyOrder> find(long id) {
        return database.inTransaction(tx -> find(tx, id));
    }

    Optional<BuyOrder> find(Tx tx, long id) throws SQLException {
        return tx.queryOne(SELECT + " WHERE o.id = ?", BuyOrderService::map, id);
    }

    /** Open orders, best unit price first; optionally for one material. */
    public List<BuyOrder> board(String material, int limit, int offset) {
        return database.inTransaction(tx -> material == null
                ? tx.queryList(SELECT + " WHERE o.status = 'OPEN' AND o.deadline_at > ? ORDER BY o.unit_price DESC, o.id LIMIT ? OFFSET ?",
                BuyOrderService::map, clock.instant(), Math.clamp(limit, 1, 50), Math.max(0, offset))
                : tx.queryList(SELECT + " WHERE o.status = 'OPEN' AND o.deadline_at > ? AND o.material = ? ORDER BY o.unit_price DESC, o.id LIMIT ? OFFSET ?",
                BuyOrderService::map, clock.instant(), material, Math.clamp(limit, 1, 50), Math.max(0, offset)));
    }

    public List<BuyOrder> issuedBy(UUID player) {
        return database.inTransaction(tx -> tx.queryList(SELECT + """
                         WHERE o.status = 'OPEN' AND (o.issuer_player_uuid = ?
                            OR o.issuer_company_id IN (SELECT company_id FROM company_employees
                                                       WHERE player_uuid = ? AND ended_at IS NULL AND role IN ('OWNER', 'MANAGER')))
                         ORDER BY o.id""", BuyOrderService::map, player, player));
    }

    private boolean isIssuerSide(Tx tx, BuyOrder order, UUID player) throws SQLException {
        if (order.issuerType() == BuyOrder.IssuerType.PLAYER) {
            return player.equals(order.issuerPlayer());
        }
        return companies.roleOf(tx, order.issuerCompanyId(), player)
                .map(r -> r == CompanyRole.OWNER || r == CompanyRole.MANAGER).orElse(false);
    }

    private BuyOrder lock(Tx tx, long id) throws SQLException {
        tx.queryOne("SELECT id FROM buy_orders WHERE id = ? FOR UPDATE", rs -> true, id)
                .orElseThrow(() -> new DomainException("order.not_found"));
        return find(tx, id).orElseThrow();
    }

    private static final String SELECT = """
            SELECT o.*, COALESCE(c.name, p.name) AS issuer_name FROM buy_orders o
            LEFT JOIN companies c ON c.id = o.issuer_company_id
            LEFT JOIN players p ON p.uuid = o.issuer_player_uuid
            """;

    private static BuyOrder map(ResultSet rs) throws SQLException {
        return new BuyOrder(rs.getLong("id"), BuyOrder.IssuerType.valueOf(rs.getString("issuer_type")),
                Tx.uuid(rs, "issuer_player_uuid"), Tx.nullableLong(rs, "issuer_company_id"), rs.getString("issuer_name"),
                rs.getString("material"), rs.getInt("quantity"), rs.getInt("filled"), Money.ofOre(rs.getLong("unit_price")),
                BuyOrder.Status.valueOf(rs.getString("status")), Tx.instant(rs, "deadline_at"));
    }
}
