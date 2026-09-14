package se.nordia.swedencore.shares;

import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.companies.CompanyFinanceService;
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
import se.nordia.swedencore.economy.TransferRequest;
import se.nordia.swedencore.jobs.PayrollService;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Company shares (P4): ownership of a company's equity, separate from control (the OWNER role).
 *
 * <p>Invariants and protections:
 * <ul>
 *   <li>No money is created. Issuing shares only creates treasury shares; selling them moves real money from a buyer
 *       into the company account. Share sales carry a fee that is destroyed, which makes wash trading cost money.</li>
 *   <li>Listed shares are escrowed (removed from the holding) until sold, cancelled or expired, so they cannot be sold
 *       twice.</li>
 *   <li>Dividends and closing equity (dissolution, solvent bankruptcy) are paid pro rata to all shares outside the
 *       treasury. While anyone other than the owner holds shares, the owner cannot withdraw company money.</li>
 *   <li>Lock order: company row → offer row → holdings → accounts.</li>
 * </ul>
 */
public final class ShareService {

    public record Config(long initialShares, long maxTotalShares, int feePercent, int maxOfferHours, int maxOpenOffers) {
        public Config {
            if (initialShares < 1 || maxTotalShares < initialShares || feePercent < 0 || feePercent > 50
                    || maxOfferHours < 1 || maxOpenOffers < 1) {
                throw new IllegalArgumentException("Invalid shares configuration");
            }
        }

        public static Config defaults() {
            return new Config(1_000, 1_000_000, 1, 24 * 14, 10);
        }
    }

    public enum HolderType {
        PLAYER, TREASURY
    }

    public record Holder(HolderType type, String id) {
        public static Holder player(UUID uuid) {
            return new Holder(HolderType.PLAYER, uuid.toString());
        }

        public static Holder treasury(long companyId) {
            return new Holder(HolderType.TREASURY, Long.toString(companyId));
        }

        AccountOwner account() {
            return type == HolderType.PLAYER ? AccountOwner.player(UUID.fromString(id)) : AccountOwner.company(Long.parseLong(id));
        }
    }

    /** A holder's position: {@code quantity} free shares plus {@code listed} shares escrowed in open offers. */
    public record Position(long companyId, String companyName, HolderType holderType, String holderId, String holderName,
                           long quantity, long listed) {
        public long total() {
            return quantity + listed;
        }
    }

    public record Offer(long id, long companyId, String companyName, HolderType sellerType, String sellerId, String sellerName,
                        long remaining, Money pricePerShare, UUID buyer, String buyerName, Instant expiresAt, String status) {
    }

    public record Valuation(Company company, long totalShares, long treasuryShares, Money equity, Money bookValuePerShare,
                            Money lastPrice, Money averagePrice30d, long volume30d, Money marketCap, Money dividends30d) {
        public long outstanding() {
            return totalShares - treasuryShares;
        }
    }

    public record Trade(long offerId, long quantity, Money total, Money fee) {
    }

    public record Dividend(long id, Money perShare, long shares, Money total, int recipients) {
    }

    private final Database database;
    private final EconomyService economy;
    private final CompanyService companies;
    private final CompanyFinanceService finance;
    private final PayrollService payroll;
    private final Config config;
    private final Clock clock;

    public ShareService(Database database, EconomyService economy, CompanyService companies, CompanyFinanceService finance,
                        PayrollService payroll, Config config, Clock clock) {
        this.database = database;
        this.economy = economy;
        this.companies = companies;
        this.finance = finance;
        this.payroll = payroll;
        this.config = config;
        this.clock = clock;
        companies.addFoundingHook((tx, company) -> addShares(tx, company.id(), Holder.player(company.ownerUuid()), config.initialShares()));
        companies.addWithdrawalCheck((tx, companyId) -> {
            if (hasOutsideShareholders(tx, companyId)) {
                throw new DomainException("company.withdraw_blocked_by_shareholders");
            }
        });
        companies.setEquityCloser((svc, tx, company, residual, actor) -> closeEquity(tx, company, residual, actor));
    }

    public Config config() {
        return config;
    }

    // ------------------------------------------------------------------ issuing

    /** The owner creates new shares in the company treasury (dilution). They can then be sold to raise capital. */
    public long issue(UUID actor, long companyId, long quantity) {
        if (quantity < 1) {
            throw new DomainException("shares.invalid_quantity");
        }
        return database.inTransaction(tx -> {
            companies.lockActive(tx, companyId);
            companies.requireRole(tx, companyId, actor, CompanyRole.OWNER);
            long total = totalShares(tx, companyId);
            if (quantity > config.maxTotalShares() - total) {
                throw DomainException.of("shares.too_many", "max", config.maxTotalShares());
            }
            addShares(tx, companyId, Holder.treasury(companyId), quantity);
            return totalShares(tx, companyId);
        });
    }

    // ------------------------------------------------------------------ offers

    /**
     * Lists shares for sale. {@code fromTreasury} sells the company's treasury shares (owner only; proceeds go to the
     * company account), otherwise the actor's own shares. {@code buyer} restricts the offer to one player.
     */
    public Offer offer(UUID actor, long companyId, boolean fromTreasury, long quantity, Money pricePerShare, UUID buyer, int hours) {
        if (quantity < 1) {
            throw new DomainException("shares.invalid_quantity");
        }
        if (!pricePerShare.isPositive()) {
            throw new DomainException("economy.invalid_amount");
        }
        if (hours < 1 || hours > config.maxOfferHours()) {
            throw DomainException.of("contract.invalid_duration", "max", config.maxOfferHours());
        }
        Money total;
        try {
            total = pricePerShare.times(quantity);
        } catch (ArithmeticException e) {
            throw DomainException.of("economy.amount_too_large", "max", economy.config().maxTransferAmount());
        }
        if (total.isGreaterThan(economy.config().maxTransferAmount())) {
            throw DomainException.of("economy.amount_too_large", "max", economy.config().maxTransferAmount());
        }
        if (actor.equals(buyer) && !fromTreasury) {
            throw new DomainException("shares.own_offer");
        }
        return database.inTransaction(tx -> {
            companies.lockActive(tx, companyId);
            Holder seller;
            if (fromTreasury) {
                companies.requireRole(tx, companyId, actor, CompanyRole.OWNER);
                seller = Holder.treasury(companyId);
            } else {
                seller = Holder.player(actor);
            }
            long open = tx.queryLong("SELECT count(*) FROM share_offers WHERE seller_type = ? AND seller_id = ? AND status = 'OPEN'",
                    seller.type(), seller.id());
            if (open >= config.maxOpenOffers()) {
                throw DomainException.of("shares.too_many_offers", "max", config.maxOpenOffers());
            }
            removeShares(tx, companyId, seller, quantity);
            Instant now = clock.instant();
            long id = tx.queryLong("""
                            INSERT INTO share_offers (company_id, seller_type, seller_id, created_by, quantity, remaining, price_per_share,
                                                      buyer_uuid, created_at, expires_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    companyId, seller.type(), seller.id(), actor, quantity, quantity, pricePerShare.ore(), buyer, now,
                    now.plus(Duration.ofHours(hours)));
            return findOffer(tx, id).orElseThrow();
        });
    }

    /** Buys {@code quantity} shares from an open offer. The seller pays the fee out of the proceeds. */
    public Trade buy(UUID buyer, long offerId, long quantity) {
        if (quantity < 1) {
            throw new DomainException("shares.invalid_quantity");
        }
        return database.inTransaction(tx -> {
            long companyId = tx.queryOne("SELECT company_id FROM share_offers WHERE id = ?", rs -> rs.getLong(1), offerId)
                    .orElseThrow(() -> new DomainException("shares.offer_not_found"));
            companies.lockActive(tx, companyId);
            Offer offer = lockOffer(tx, offerId);
            if (!"OPEN".equals(offer.status()) || !clock.instant().isBefore(offer.expiresAt())) {
                throw new DomainException("shares.offer_not_open");
            }
            if (offer.buyer() != null && !offer.buyer().equals(buyer)) {
                throw new DomainException("shares.offer_private");
            }
            Holder seller = new Holder(offer.sellerType(), offer.sellerId());
            if (seller.equals(Holder.player(buyer))) {
                throw new DomainException("shares.own_offer");
            }
            if (quantity > offer.remaining()) {
                throw DomainException.of("shares.not_enough_offered", "available", offer.remaining());
            }
            Money total = offer.pricePerShare().times(quantity);
            Money fee = Money.ofOre(Math.multiplyExact(total.ore(), config.feePercent()) / 100);
            Account from = economy.requireAccount(tx, AccountOwner.player(buyer));
            Account to = economy.requireAccount(tx, seller.account());
            economy.transfer(tx, new TransferRequest(from.id(), to.id(), total, TransactionType.SHARE_PURCHASE, null, buyer,
                    "SHARE_OFFER", Long.toString(offerId), null));
            if (fee.isPositive()) {
                economy.burn(tx, to.id(), fee, TransactionType.SHARE_FEE, null, buyer);
            }
            addShares(tx, companyId, Holder.player(buyer), quantity);
            long remaining = offer.remaining() - quantity;
            tx.update("UPDATE share_offers SET remaining = ?, status = ?, closed_at = ? WHERE id = ?",
                    remaining, remaining == 0 ? "SOLD" : "OPEN", remaining == 0 ? clock.instant() : null, offerId);
            tx.update("""
                            INSERT INTO share_trades (company_id, offer_id, seller_type, seller_id, buyer_uuid, quantity, price_per_share, total, fee, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    companyId, offerId, seller.type(), seller.id(), buyer, quantity, offer.pricePerShare().ore(), total.ore(), fee.ore(),
                    clock.instant());
            return new Trade(offerId, quantity, total, fee);
        });
    }

    /** The seller (or the owner, for treasury offers) cancels; unsold shares return. */
    public Offer cancel(UUID actor, long offerId) {
        return database.inTransaction(tx -> {
            long companyId = tx.queryOne("SELECT company_id FROM share_offers WHERE id = ?", rs -> rs.getLong(1), offerId)
                    .orElseThrow(() -> new DomainException("shares.offer_not_found"));
            lockCompany(tx, companyId);
            Offer offer = lockOffer(tx, offerId);
            if (!"OPEN".equals(offer.status())) {
                throw new DomainException("shares.offer_not_open");
            }
            if (offer.sellerType() == HolderType.TREASURY) {
                companies.requireRole(tx, companyId, actor, CompanyRole.OWNER);
            } else if (!offer.sellerId().equals(actor.toString())) {
                throw new DomainException("shares.not_seller");
            }
            closeOffer(tx, offer, "CANCELLED");
            return findOffer(tx, offerId).orElseThrow();
        });
    }

    public int expireDue() {
        List<Long> ids = database.inTransaction(tx -> tx.queryList(
                "SELECT id FROM share_offers WHERE status = 'OPEN' AND expires_at <= ? ORDER BY expires_at LIMIT 100",
                rs -> rs.getLong(1), clock.instant()));
        int expired = 0;
        for (long id : ids) {
            boolean done = database.inTransaction(tx -> {
                long companyId = tx.queryLong("SELECT company_id FROM share_offers WHERE id = ?", id);
                lockCompany(tx, companyId);
                Offer offer = lockOffer(tx, id);
                if (!"OPEN".equals(offer.status()) || offer.expiresAt().isAfter(clock.instant())) {
                    return false;
                }
                closeOffer(tx, offer, "EXPIRED");
                return true;
            });
            expired += done ? 1 : 0;
        }
        return expired;
    }

    private void closeOffer(Tx tx, Offer offer, String status) throws SQLException {
        if (offer.remaining() > 0) {
            addShares(tx, offer.companyId(), new Holder(offer.sellerType(), offer.sellerId()), offer.remaining());
        }
        tx.update("UPDATE share_offers SET remaining = 0, status = ?, closed_at = ? WHERE id = ?", status, clock.instant(), offer.id());
    }

    // ------------------------------------------------------------------ dividends and closing

    /**
     * The owner pays {@code amount} from the company account, split equally per share among all shares outside the
     * treasury (listed shares included). The undivisible remainder stays in the company.
     */
    public Dividend declareDividend(UUID actor, long companyId, Money amount) {
        if (!amount.isPositive()) {
            throw new DomainException("economy.invalid_amount");
        }
        return database.inTransaction(tx -> {
            companies.lockActive(tx, companyId);
            companies.requireRole(tx, companyId, actor, CompanyRole.OWNER);
            if (payroll.arrears(tx, companyId).isPositive()) {
                throw DomainException.of("company.withdraw_blocked_by_arrears", "arrears", payroll.arrears(tx, companyId));
            }
            List<Position> holders = shareholders(tx, companyId);
            long outstanding = holders.stream().mapToLong(Position::total).sum();
            if (outstanding == 0) {
                throw new DomainException("shares.no_shareholders");
            }
            long perShare = amount.ore() / outstanding;
            if (perShare < 1) {
                throw DomainException.of("shares.dividend_too_small", "min", Money.ofOre(outstanding));
            }
            Money total = Money.ofOre(Math.multiplyExact(perShare, outstanding));
            Account account = economy.requireAccount(tx, AccountOwner.company(companyId));
            if (account.balance().isLessThan(total)) {
                throw DomainException.of("economy.insufficient_funds", "balance", account.balance());
            }
            long id = tx.queryLong("INSERT INTO dividends (company_id, declared_by, per_share, shares, total, created_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                    companyId, actor, perShare, outstanding, total.ore(), clock.instant());
            for (Position holder : holders) {
                Account to = economy.requireAccount(tx, AccountOwner.player(UUID.fromString(holder.holderId())));
                economy.transfer(tx, new TransferRequest(account.id(), to.id(), Money.ofOre(Math.multiplyExact(perShare, holder.total())),
                        TransactionType.DIVIDEND, "dividend:" + id + ":" + holder.holderId(), actor, "DIVIDEND", Long.toString(id), null));
            }
            return new Dividend(id, Money.ofOre(perShare), outstanding, total, holders.size());
        });
    }

    /**
     * Company closes (dissolution or solvent bankruptcy): open offers are cancelled and the residual is split pro rata
     * by shares outside the treasury. Rounding dust, or everything if nobody holds shares, goes to the owner.
     */
    private void closeEquity(Tx tx, Company company, Money residual, UUID actor) throws SQLException {
        long companyId = company.id();
        for (Long offerId : tx.queryList("SELECT id FROM share_offers WHERE company_id = ? AND status = 'OPEN' ORDER BY id",
                rs -> rs.getLong(1), companyId)) {
            closeOffer(tx, lockOffer(tx, offerId), "CANCELLED");
        }
        if (!residual.isPositive()) {
            return;
        }
        List<Position> holders = shareholders(tx, companyId);
        long outstanding = holders.stream().mapToLong(Position::total).sum();
        long paid = 0;
        if (outstanding > 0) {
            BigInteger pool = BigInteger.valueOf(residual.ore());
            for (Position holder : holders) {
                long part = pool.multiply(BigInteger.valueOf(holder.total())).divide(BigInteger.valueOf(outstanding)).longValueExact();
                companies.payFromCompany(tx, companyId, AccountOwner.player(UUID.fromString(holder.holderId())), Money.ofOre(part), actor,
                        "equity:" + companyId + ":" + holder.holderId());
                paid += part;
            }
        }
        companies.payFromCompany(tx, companyId, AccountOwner.player(company.ownerUuid()), Money.ofOre(residual.ore() - paid), actor,
                "equity-rest:" + companyId);
    }

    // ------------------------------------------------------------------ queries

    public Valuation valuation(long companyId) {
        return database.inTransaction(tx -> {
            Company company = companies.find(tx, companyId).orElseThrow(() -> new DomainException("company.not_found"));
            long total = totalShares(tx, companyId);
            long treasury = tx.queryLong("""
                    SELECT COALESCE((SELECT quantity FROM share_holdings WHERE company_id = ? AND holder_type = 'TREASURY'), 0)
                         + COALESCE((SELECT SUM(remaining) FROM share_offers WHERE company_id = ? AND seller_type = 'TREASURY' AND status = 'OPEN'), 0)""",
                    companyId, companyId);
            Money equity = finance.balance(tx, companyId).equity();
            long outstanding = total - treasury;
            Money book = outstanding > 0 ? Money.ofOre(equity.ore() / outstanding) : Money.ZERO;
            Instant since = clock.instant().minus(Duration.ofDays(30));
            Optional<Long> last = tx.queryOne("SELECT price_per_share FROM share_trades WHERE company_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                    rs -> rs.getLong(1), companyId);
            long volume = tx.queryLong("SELECT COALESCE(SUM(quantity), 0) FROM share_trades WHERE company_id = ? AND created_at >= ?", companyId, since);
            long value = tx.queryLong("SELECT COALESCE(SUM(total), 0) FROM share_trades WHERE company_id = ? AND created_at >= ?", companyId, since);
            long dividends = tx.queryLong("SELECT COALESCE(SUM(total), 0) FROM dividends WHERE company_id = ? AND created_at >= ?", companyId, since);
            Money lastPrice = last.map(Money::ofOre).orElse(null);
            Money average = volume > 0 ? Money.ofOre(value / volume) : null;
            Money marketCap = null;
            if (lastPrice != null) {
                try {
                    marketCap = lastPrice.times(outstanding);
                } catch (ArithmeticException e) {
                    marketCap = null;
                }
            }
            return new Valuation(company, total, treasury, equity, book, lastPrice, average, volume, marketCap, Money.ofOre(dividends));
        });
    }

    /** Largest shareholders of an active company, treasury included. */
    public List<Position> capTable(long companyId, int limit) {
        return database.inTransaction(tx -> tx.queryList(POSITIONS + " WHERE p.company_id = ? ORDER BY p.quantity + p.listed DESC LIMIT ?",
                ShareService::mapPosition, companyId, Math.clamp(limit, 1, 50)));
    }

    public List<Position> portfolio(UUID player) {
        return database.inTransaction(tx -> tx.queryList(POSITIONS + """
                         WHERE p.holder_type = 'PLAYER' AND p.holder_id = ? AND c.status = 'ACTIVE'
                         ORDER BY p.quantity + p.listed DESC""", ShareService::mapPosition, player.toString()));
    }

    /** Open offers, cheapest first; optionally for one company. Private offers are only shown to their buyer and seller. */
    public List<Offer> openOffers(UUID viewer, Long companyId, int limit) {
        return database.inTransaction(tx -> tx.queryList(OFFER_SELECT + """
                         WHERE o.status = 'OPEN' AND o.expires_at > ? AND c.status = 'ACTIVE' AND (?::bigint IS NULL OR o.company_id = ?)
                           AND (o.buyer_uuid IS NULL OR o.buyer_uuid = ? OR o.created_by = ?)
                         ORDER BY o.price_per_share, o.id LIMIT ?""",
                ShareService::mapOffer, clock.instant(), companyId, companyId, viewer, viewer, Math.clamp(limit, 1, 50)));
    }

    public Optional<Offer> findOffer(long id) {
        return database.inTransaction(tx -> findOffer(tx, id));
    }

    long totalShares(Tx tx, long companyId) throws SQLException {
        return tx.queryLong("""
                SELECT COALESCE((SELECT SUM(quantity) FROM share_holdings WHERE company_id = ?), 0)
                     + COALESCE((SELECT SUM(remaining) FROM share_offers WHERE company_id = ? AND status = 'OPEN'), 0)""", companyId, companyId);
    }

    /** Player shareholders with free and listed shares (the treasury is never a shareholder). */
    private List<Position> shareholders(Tx tx, long companyId) throws SQLException {
        return tx.queryList(POSITIONS + " WHERE p.company_id = ? AND p.holder_type = 'PLAYER' ORDER BY p.holder_id",
                ShareService::mapPosition, companyId);
    }

    private boolean hasOutsideShareholders(Tx tx, long companyId) throws SQLException {
        return tx.queryOne("""
                SELECT 1 FROM companies c
                WHERE c.id = ? AND (
                    EXISTS (SELECT 1 FROM share_holdings h WHERE h.company_id = c.id AND h.holder_type = 'PLAYER' AND h.holder_id <> c.owner_uuid::text)
                 OR EXISTS (SELECT 1 FROM share_offers o WHERE o.company_id = c.id AND o.status = 'OPEN' AND o.seller_type = 'PLAYER'
                                                       AND o.seller_id <> c.owner_uuid::text))""", rs -> true, companyId).isPresent();
    }

    private void addShares(Tx tx, long companyId, Holder holder, long quantity) throws SQLException {
        tx.update("""
                INSERT INTO share_holdings (company_id, holder_type, holder_id, quantity) VALUES (?, ?, ?, ?)
                ON CONFLICT (company_id, holder_type, holder_id) DO UPDATE SET quantity = share_holdings.quantity + EXCLUDED.quantity""",
                companyId, holder.type(), holder.id(), quantity);
    }

    private void removeShares(Tx tx, long companyId, Holder holder, long quantity) throws SQLException {
        long held = tx.queryOne("SELECT quantity FROM share_holdings WHERE company_id = ? AND holder_type = ? AND holder_id = ? FOR UPDATE",
                rs -> rs.getLong(1), companyId, holder.type(), holder.id()).orElse(0L);
        if (held < quantity) {
            throw DomainException.of("shares.insufficient", "held", held);
        }
        if (held == quantity) {
            tx.update("DELETE FROM share_holdings WHERE company_id = ? AND holder_type = ? AND holder_id = ?", companyId, holder.type(), holder.id());
        } else {
            tx.update("UPDATE share_holdings SET quantity = quantity - ? WHERE company_id = ? AND holder_type = ? AND holder_id = ?",
                    quantity, companyId, holder.type(), holder.id());
        }
    }

    private static void lockCompany(Tx tx, long companyId) throws SQLException {
        tx.queryOne("SELECT id FROM companies WHERE id = ? FOR UPDATE", rs -> true, companyId)
                .orElseThrow(() -> new DomainException("company.not_found"));
    }

    private Offer lockOffer(Tx tx, long id) throws SQLException {
        tx.queryOne("SELECT id FROM share_offers WHERE id = ? FOR UPDATE", rs -> true, id)
                .orElseThrow(() -> new DomainException("shares.offer_not_found"));
        return findOffer(tx, id).orElseThrow();
    }

    private Optional<Offer> findOffer(Tx tx, long id) throws SQLException {
        return tx.queryOne(OFFER_SELECT + " WHERE o.id = ?", ShareService::mapOffer, id);
    }

    /** Free holdings merged with shares listed in open offers, per holder. */
    private static final String POSITIONS = """
            SELECT p.company_id, c.name AS company_name, p.holder_type, p.holder_id, p.quantity, p.listed,
                   CASE WHEN p.holder_type = 'PLAYER' THEN pl.name ELSE c.name END AS holder_name
            FROM (SELECT company_id, holder_type, holder_id, SUM(quantity) AS quantity, SUM(listed) AS listed
                  FROM (SELECT company_id, holder_type, holder_id, quantity, 0 AS listed FROM share_holdings
                        UNION ALL
                        SELECT company_id, seller_type, seller_id, 0, remaining FROM share_offers WHERE status = 'OPEN') u
                  GROUP BY company_id, holder_type, holder_id) p
            JOIN companies c ON c.id = p.company_id
            LEFT JOIN players pl ON p.holder_type = 'PLAYER' AND pl.uuid::text = p.holder_id
            """;

    private static final String OFFER_SELECT = """
            SELECT o.*, c.name AS company_name, CASE WHEN o.seller_type = 'PLAYER' THEN sp.name ELSE c.name END AS seller_name,
                   bp.name AS buyer_name
            FROM share_offers o
            JOIN companies c ON c.id = o.company_id
            LEFT JOIN players sp ON o.seller_type = 'PLAYER' AND sp.uuid::text = o.seller_id
            LEFT JOIN players bp ON bp.uuid = o.buyer_uuid
            """;

    private static Position mapPosition(ResultSet rs) throws SQLException {
        return new Position(rs.getLong("company_id"), rs.getString("company_name"), HolderType.valueOf(rs.getString("holder_type")),
                rs.getString("holder_id"), rs.getString("holder_name"), rs.getLong("quantity"), rs.getLong("listed"));
    }

    private static Offer mapOffer(ResultSet rs) throws SQLException {
        return new Offer(rs.getLong("id"), rs.getLong("company_id"), rs.getString("company_name"), HolderType.valueOf(rs.getString("seller_type")),
                rs.getString("seller_id"), rs.getString("seller_name"), rs.getLong("remaining"), Money.ofOre(rs.getLong("price_per_share")),
                Tx.uuid(rs, "buyer_uuid"), rs.getString("buyer_name"), Tx.instant(rs, "expires_at"), rs.getString("status"));
    }
}
