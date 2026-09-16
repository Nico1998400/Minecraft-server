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
 *   <li>Companies may hold shares of <em>other</em> companies. A company that has outside shareholders cannot
 *       buy above {@code maxInvestmentBookMultiple} × book value per share, or sell below book / multiple, so the
 *       owner cannot extract minority capital by trading with an accomplice at a fake price.</li>
 *   <li>Lock order: involved company rows (ascending id) → offer/bid row → holdings → accounts.</li>
 * </ul>
 */
public final class ShareService {

    public record Config(long initialShares, long maxTotalShares, int feePercent, int maxOfferHours, int maxOpenOffers,
                         int maxInvestmentBookMultiple) {
        public Config {
            if (initialShares < 1 || maxTotalShares < initialShares || feePercent < 0 || feePercent > 50
                    || maxOfferHours < 1 || maxOpenOffers < 1
                    || maxInvestmentBookMultiple < 1 || maxInvestmentBookMultiple > 100) {
                throw new IllegalArgumentException("Invalid shares configuration");
            }
        }

        public static Config defaults() {
            return new Config(1_000, 1_000_000, 1, 24 * 14, 10, 3);
        }
    }

    public enum HolderType {
        PLAYER, TREASURY, COMPANY
    }

    public record Holder(HolderType type, String id) {
        public static Holder player(UUID uuid) {
            return new Holder(HolderType.PLAYER, uuid.toString());
        }

        public static Holder treasury(long companyId) {
            return new Holder(HolderType.TREASURY, Long.toString(companyId));
        }

        public static Holder company(long companyId) {
            return new Holder(HolderType.COMPANY, Long.toString(companyId));
        }

        AccountOwner account() {
            return type == HolderType.PLAYER ? AccountOwner.player(UUID.fromString(id)) : AccountOwner.company(Long.parseLong(id));
        }

        long companyId() {
            if (type == HolderType.PLAYER) {
                throw new IllegalStateException("player holder");
            }
            return Long.parseLong(id);
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

    /** A trade against an offer or a bid ({@code sourceId} is that offer's or bid's id). */
    public record Trade(long sourceId, long quantity, Money total, Money fee) {
    }

    public record Bid(long id, long companyId, String companyName, UUID buyer, String buyerName, Long buyerCompanyId,
                      long remaining, Money pricePerShare, Instant expiresAt, String status) {
        public Holder buyerHolder() {
            return buyerCompanyId != null ? Holder.company(buyerCompanyId) : Holder.player(buyer);
        }
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
        companies.addPreCloseHook(this::closeMarketForCompany);
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
        Holder seller = fromTreasury ? Holder.treasury(companyId) : Holder.player(actor);
        return offer(actor, companyId, seller, quantity, pricePerShare, buyer, hours);
    }

    /** The actor's company lists its holdings of {@code companyId}. Proceeds go to the holding company. */
    public Offer offerFromCompany(UUID actor, long sellerCompanyId, long companyId, long quantity, Money pricePerShare,
                                  UUID buyer, int hours) {
        return offer(actor, companyId, Holder.company(sellerCompanyId), quantity, pricePerShare, buyer, hours);
    }

    private Offer offer(UUID actor, long companyId, Holder seller, long quantity, Money pricePerShare, UUID buyer, int hours) {
        if (quantity < 1) {
            throw new DomainException("shares.invalid_quantity");
        }
        if (!pricePerShare.isPositive()) {
            throw new DomainException("economy.invalid_amount");
        }
        if (hours < 1 || hours > config.maxOfferHours()) {
            throw DomainException.of("contract.invalid_duration", "max", config.maxOfferHours());
        }
        total(pricePerShare, quantity);
        if (seller.type() == HolderType.PLAYER && actor.equals(buyer)) {
            throw new DomainException("shares.own_offer");
        }
        if (seller.type() == HolderType.COMPANY && seller.companyId() == companyId) {
            throw new DomainException("shares.own_company");
        }
        return database.inTransaction(tx -> {
            List<Long> lockIds = new ArrayList<>();
            lockIds.add(companyId);
            if (seller.type() == HolderType.COMPANY) {
                lockIds.add(seller.companyId());
            }
            lockActiveAscending(tx, lockIds);
            if (seller.type() == HolderType.TREASURY) {
                companies.requireRole(tx, companyId, actor, CompanyRole.OWNER);
            } else if (seller.type() == HolderType.COMPANY) {
                companies.requireRole(tx, seller.companyId(), actor, CompanyRole.OWNER);
                guardCompanyPrice(tx, seller.companyId(), companyId, pricePerShare, false);
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
        return buy(buyer, offerId, quantity, null);
    }

    /** The actor's company buys with company money; the shares go into the company holding. */
    public Trade buyForCompany(UUID actor, long buyerCompanyId, long offerId, long quantity) {
        return buy(actor, offerId, quantity, buyerCompanyId);
    }

    private Trade buy(UUID actor, long offerId, long quantity, Long buyerCompanyId) {
        if (quantity < 1) {
            throw new DomainException("shares.invalid_quantity");
        }
        return database.inTransaction(tx -> {
            record OfferMeta(long companyId, String sellerType, String sellerId) {
            }
            OfferMeta meta = tx.queryOne("SELECT company_id, seller_type, seller_id FROM share_offers WHERE id = ?",
                            rs -> new OfferMeta(rs.getLong(1), rs.getString(2), rs.getString(3)), offerId)
                    .orElseThrow(() -> new DomainException("shares.offer_not_found"));
            List<Long> lockIds = new ArrayList<>();
            lockIds.add(meta.companyId());
            if (buyerCompanyId != null) {
                lockIds.add(buyerCompanyId);
            }
            if ("COMPANY".equals(meta.sellerType())) {
                lockIds.add(Long.parseLong(meta.sellerId()));
            }
            lockActiveAscending(tx, lockIds);
            Offer offer = lockOffer(tx, offerId);
            if (!"OPEN".equals(offer.status()) || !clock.instant().isBefore(offer.expiresAt())) {
                throw new DomainException("shares.offer_not_open");
            }
            if (buyerCompanyId != null && offer.buyer() != null) {
                throw new DomainException("shares.offer_private");
            }
            if (offer.buyer() != null && !offer.buyer().equals(actor)) {
                throw new DomainException("shares.offer_private");
            }
            Holder seller = new Holder(offer.sellerType(), offer.sellerId());
            Holder buyer = buyerCompanyId != null ? Holder.company(buyerCompanyId) : Holder.player(actor);
            if (seller.equals(buyer)) {
                throw new DomainException("shares.own_offer");
            }
            if (buyerCompanyId != null && buyerCompanyId == meta.companyId()) {
                throw new DomainException("shares.own_company");
            }
            if (quantity > offer.remaining()) {
                throw DomainException.of("shares.not_enough_offered", "available", offer.remaining());
            }
            if (buyerCompanyId != null) {
                companies.requireRole(tx, buyerCompanyId, actor, CompanyRole.OWNER);
                requireNoArrears(tx, buyerCompanyId);
                guardCompanyPrice(tx, buyerCompanyId, meta.companyId(), offer.pricePerShare(), true);
            }
            if (seller.type() == HolderType.COMPANY) {
                guardCompanyPrice(tx, seller.companyId(), meta.companyId(), offer.pricePerShare(), false);
            }
            Money total = offer.pricePerShare().times(quantity);
            Money fee = Money.ofOre(Math.multiplyExact(total.ore(), config.feePercent()) / 100);
            Account from = economy.requireAccount(tx, buyer.account());
            Account to = economy.requireAccount(tx, seller.account());
            economy.transfer(tx, new TransferRequest(from.id(), to.id(), total, TransactionType.SHARE_PURCHASE, null, actor,
                    "SHARE_OFFER", Long.toString(offerId), null));
            if (fee.isPositive()) {
                economy.burn(tx, to.id(), fee, TransactionType.SHARE_FEE, null, actor);
            }
            addShares(tx, meta.companyId(), buyer, quantity);
            long remaining = offer.remaining() - quantity;
            tx.update("UPDATE share_offers SET remaining = ?, status = ?, closed_at = ? WHERE id = ?",
                    remaining, remaining == 0 ? "SOLD" : "OPEN", remaining == 0 ? clock.instant() : null, offerId);
            tx.update("""
                            INSERT INTO share_trades (company_id, offer_id, seller_type, seller_id, buyer_uuid, buyer_company_id, quantity, price_per_share, total, fee, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    meta.companyId(), offerId, seller.type(), seller.id(), actor, buyerCompanyId, quantity, offer.pricePerShare().ore(),
                    total.ore(), fee.ore(), clock.instant());
            return new Trade(offerId, quantity, total, fee);
        });
    }

    /** The seller (or the owner, for treasury and company offers) cancels; unsold shares return. */
    public Offer cancel(UUID actor, long offerId) {
        return database.inTransaction(tx -> {
            record OfferMeta(long companyId, String sellerType, String sellerId) {
            }
            OfferMeta meta = tx.queryOne("SELECT company_id, seller_type, seller_id FROM share_offers WHERE id = ?",
                            rs -> new OfferMeta(rs.getLong(1), rs.getString(2), rs.getString(3)), offerId)
                    .orElseThrow(() -> new DomainException("shares.offer_not_found"));
            List<Long> lockIds = new ArrayList<>();
            lockIds.add(meta.companyId());
            if ("COMPANY".equals(meta.sellerType())) {
                lockIds.add(Long.parseLong(meta.sellerId()));
            }
            for (long id : lockIds.stream().distinct().sorted().toList()) {
                lockCompany(tx, id);
            }
            Offer offer = lockOffer(tx, offerId);
            if (!"OPEN".equals(offer.status())) {
                throw new DomainException("shares.offer_not_open");
            }
            if (offer.sellerType() == HolderType.TREASURY) {
                companies.requireRole(tx, meta.companyId(), actor, CompanyRole.OWNER);
            } else if (offer.sellerType() == HolderType.COMPANY) {
                companies.requireRole(tx, Long.parseLong(offer.sellerId()), actor, CompanyRole.OWNER);
            } else if (!offer.sellerId().equals(actor.toString())) {
                throw new DomainException("shares.not_seller");
            }
            closeOffer(tx, offer, "CANCELLED");
            return findOffer(tx, offerId).orElseThrow();
        });
    }

    // ------------------------------------------------------------------ bids (buy side)

    /** A player bids for shares; price × quantity is escrowed until filled, cancelled or expired. */
    public Bid bid(UUID buyer, long companyId, long quantity, Money pricePerShare, int hours) {
        return bid(buyer, companyId, quantity, pricePerShare, hours, null);
    }

    /** The actor's company bids with company money; filled shares go into the company holding. */
    public Bid bidForCompany(UUID actor, long buyerCompanyId, long companyId, long quantity, Money pricePerShare, int hours) {
        return bid(actor, companyId, quantity, pricePerShare, hours, buyerCompanyId);
    }

    private Bid bid(UUID actor, long companyId, long quantity, Money pricePerShare, int hours, Long buyerCompanyId) {
        if (quantity < 1) {
            throw new DomainException("shares.invalid_quantity");
        }
        if (!pricePerShare.isPositive()) {
            throw new DomainException("economy.invalid_amount");
        }
        if (hours < 1 || hours > config.maxOfferHours()) {
            throw DomainException.of("contract.invalid_duration", "max", config.maxOfferHours());
        }
        if (buyerCompanyId != null && buyerCompanyId == companyId) {
            throw new DomainException("shares.own_company");
        }
        Money budget = total(pricePerShare, quantity);
        return database.inTransaction(tx -> {
            List<Long> lockIds = new ArrayList<>();
            lockIds.add(companyId);
            if (buyerCompanyId != null) {
                lockIds.add(buyerCompanyId);
            }
            lockActiveAscending(tx, lockIds);
            if (buyerCompanyId != null) {
                companies.requireRole(tx, buyerCompanyId, actor, CompanyRole.OWNER);
                requireNoArrears(tx, buyerCompanyId);
                guardCompanyPrice(tx, buyerCompanyId, companyId, pricePerShare, true);
            }
            String openSql = buyerCompanyId != null
                    ? "SELECT count(*) FROM share_bids WHERE buyer_company_id = ? AND status = 'OPEN'"
                    : "SELECT count(*) FROM share_bids WHERE buyer_uuid = ? AND buyer_company_id IS NULL AND status = 'OPEN'";
            Object openKey = buyerCompanyId != null ? buyerCompanyId : actor;
            long open = tx.queryLong(openSql, openKey);
            if (open >= config.maxOpenOffers()) {
                throw DomainException.of("shares.too_many_offers", "max", config.maxOpenOffers());
            }
            Instant now = clock.instant();
            long id = tx.queryLong("""
                            INSERT INTO share_bids (company_id, buyer_uuid, buyer_company_id, quantity, remaining, price_per_share, created_at, expires_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    companyId, actor, buyerCompanyId, quantity, quantity, pricePerShare.ore(), now, now.plus(Duration.ofHours(hours)));
            Account from = economy.requireAccount(tx, buyerCompanyId != null
                    ? AccountOwner.company(buyerCompanyId) : AccountOwner.player(actor));
            Account escrow = economy.getOrCreateAccount(tx, AccountOwner.shareBid(id), Account.ESCROW);
            economy.transfer(tx, new TransferRequest(from.id(), escrow.id(), budget, TransactionType.SHARE_BID_ESCROW,
                    "share-bid-escrow:" + id, actor, "SHARE_BID", Long.toString(id), null));
            return findBid(tx, id).orElseThrow();
        });
    }

    /**
     * Sells free shares into a bid and is paid from its escrow (minus the fee). {@code fromTreasury} sells treasury
     * shares (owner only; proceeds to the company account).
     */
    public Trade sellToBid(UUID actor, long bidId, long quantity, boolean fromTreasury) {
        return sellToBid(actor, bidId, quantity, fromTreasury ? Holder.treasury(-1) : Holder.player(actor), fromTreasury);
    }

    /** The actor's company sells its holdings of the bid's company into the bid. */
    public Trade sellToBidFromCompany(UUID actor, long sellerCompanyId, long bidId, long quantity) {
        return sellToBid(actor, bidId, quantity, Holder.company(sellerCompanyId), false);
    }

    private Trade sellToBid(UUID actor, long bidId, long quantity, Holder sellerHint, boolean fromTreasury) {
        if (quantity < 1) {
            throw new DomainException("shares.invalid_quantity");
        }
        return database.inTransaction(tx -> {
            record BidMeta(long companyId, Long buyerCompanyId) {
            }
            BidMeta meta = tx.queryOne("SELECT company_id, buyer_company_id FROM share_bids WHERE id = ?",
                            rs -> {
                                long company = rs.getLong(1);
                                long buyerCompany = rs.getLong(2);
                                return new BidMeta(company, rs.wasNull() ? null : buyerCompany);
                            }, bidId)
                    .orElseThrow(() -> new DomainException("shares.bid_not_found"));
            Holder seller;
            if (fromTreasury) {
                seller = Holder.treasury(meta.companyId());
            } else {
                seller = sellerHint;
            }
            List<Long> lockIds = new ArrayList<>();
            lockIds.add(meta.companyId());
            if (meta.buyerCompanyId() != null) {
                lockIds.add(meta.buyerCompanyId());
            }
            if (seller.type() == HolderType.COMPANY) {
                lockIds.add(seller.companyId());
            }
            lockActiveAscending(tx, lockIds);
            Bid bid = lockBid(tx, bidId);
            if (!"OPEN".equals(bid.status()) || !clock.instant().isBefore(bid.expiresAt())) {
                throw new DomainException("shares.bid_not_open");
            }
            if (fromTreasury) {
                companies.requireRole(tx, meta.companyId(), actor, CompanyRole.OWNER);
            } else if (seller.type() == HolderType.COMPANY) {
                companies.requireRole(tx, seller.companyId(), actor, CompanyRole.OWNER);
            } else if (actor.equals(bid.buyer()) && bid.buyerCompanyId() == null) {
                throw new DomainException("shares.own_offer");
            }
            if (seller.equals(bid.buyerHolder())) {
                throw new DomainException("shares.own_offer");
            }
            if (quantity > bid.remaining()) {
                throw DomainException.of("shares.not_enough_offered", "available", bid.remaining());
            }
            if (bid.buyerCompanyId() != null) {
                guardCompanyPrice(tx, bid.buyerCompanyId(), meta.companyId(), bid.pricePerShare(), true);
            }
            if (seller.type() == HolderType.COMPANY) {
                guardCompanyPrice(tx, seller.companyId(), meta.companyId(), bid.pricePerShare(), false);
            }
            removeShares(tx, meta.companyId(), seller, quantity);
            Money total = bid.pricePerShare().times(quantity);
            Money fee = Money.ofOre(Math.multiplyExact(total.ore(), config.feePercent()) / 100);
            Account escrow = economy.findAccount(tx, AccountOwner.shareBid(bidId), Account.ESCROW).orElseThrow();
            Account to = economy.requireAccount(tx, seller.account());
            economy.transfer(tx, new TransferRequest(escrow.id(), to.id(), total, TransactionType.SHARE_PURCHASE, null, actor,
                    "SHARE_BID", Long.toString(bidId), null));
            if (fee.isPositive()) {
                economy.burn(tx, to.id(), fee, TransactionType.SHARE_FEE, null, actor);
            }
            addShares(tx, meta.companyId(), bid.buyerHolder(), quantity);
            long remaining = bid.remaining() - quantity;
            tx.update("UPDATE share_bids SET remaining = ?, status = ?, closed_at = ? WHERE id = ?",
                    remaining, remaining == 0 ? "FILLED" : "OPEN", remaining == 0 ? clock.instant() : null, bidId);
            tx.update("""
                            INSERT INTO share_trades (company_id, bid_id, seller_type, seller_id, buyer_uuid, buyer_company_id, quantity, price_per_share, total, fee, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    meta.companyId(), bidId, seller.type(), seller.id(), bid.buyer(), bid.buyerCompanyId(), quantity,
                    bid.pricePerShare().ore(), total.ore(), fee.ore(), clock.instant());
            return new Trade(bidId, quantity, total, fee);
        });
    }

    public Bid cancelBid(UUID actor, long bidId) {
        return database.inTransaction(tx -> {
            record BidMeta(long companyId, Long buyerCompanyId) {
            }
            BidMeta meta = tx.queryOne("SELECT company_id, buyer_company_id FROM share_bids WHERE id = ?",
                            rs -> {
                                long company = rs.getLong(1);
                                long buyerCompany = rs.getLong(2);
                                return new BidMeta(company, rs.wasNull() ? null : buyerCompany);
                            }, bidId)
                    .orElseThrow(() -> new DomainException("shares.bid_not_found"));
            List<Long> lockIds = new ArrayList<>();
            lockIds.add(meta.companyId());
            if (meta.buyerCompanyId() != null) {
                lockIds.add(meta.buyerCompanyId());
            }
            for (long id : lockIds.stream().distinct().sorted().toList()) {
                lockCompany(tx, id);
            }
            Bid bid = lockBid(tx, bidId);
            if (!"OPEN".equals(bid.status())) {
                throw new DomainException("shares.bid_not_open");
            }
            if (bid.buyerCompanyId() != null) {
                companies.requireRole(tx, bid.buyerCompanyId(), actor, CompanyRole.OWNER);
            } else if (!bid.buyer().equals(actor)) {
                throw new DomainException("shares.not_seller");
            }
            closeBid(tx, bid, "CANCELLED");
            return findBid(tx, bidId).orElseThrow();
        });
    }

    private void closeBid(Tx tx, Bid bid, String status) throws SQLException {
        if (bid.remaining() > 0) {
            Account escrow = economy.findAccount(tx, AccountOwner.shareBid(bid.id()), Account.ESCROW).orElseThrow();
            Account buyer = economy.requireAccount(tx, bid.buyerHolder().account());
            economy.transfer(tx, new TransferRequest(escrow.id(), buyer.id(), bid.pricePerShare().times(bid.remaining()),
                    TransactionType.SHARE_BID_REFUND, "share-bid-refund:" + bid.id(), null, "SHARE_BID", Long.toString(bid.id()), null));
        }
        tx.update("UPDATE share_bids SET remaining = 0, status = ?, closed_at = ? WHERE id = ?", status, clock.instant(), bid.id());
    }

    public Optional<Bid> findBid(long id) {
        return database.inTransaction(tx -> findBid(tx, id));
    }

    /** Open bids, highest price first; optionally for one company. */
    public List<Bid> openBids(Long companyId, int limit) {
        return database.inTransaction(tx -> tx.queryList(BID_SELECT + """
                         WHERE b.status = 'OPEN' AND b.expires_at > ? AND c.status = 'ACTIVE' AND (?::bigint IS NULL OR b.company_id = ?)
                         ORDER BY b.price_per_share DESC, b.id LIMIT ?""",
                ShareService::mapBid, clock.instant(), companyId, companyId, Math.clamp(limit, 1, 50)));
    }

    private Optional<Bid> findBid(Tx tx, long id) throws SQLException {
        return tx.queryOne(BID_SELECT + " WHERE b.id = ?", ShareService::mapBid, id);
    }

    private Bid lockBid(Tx tx, long id) throws SQLException {
        tx.queryOne("SELECT id FROM share_bids WHERE id = ? FOR UPDATE", rs -> true, id)
                .orElseThrow(() -> new DomainException("shares.bid_not_found"));
        return findBid(tx, id).orElseThrow();
    }

    private Money total(Money pricePerShare, long quantity) {
        Money total;
        try {
            total = pricePerShare.times(quantity);
        } catch (ArithmeticException e) {
            throw DomainException.of("economy.amount_too_large", "max", economy.config().maxTransferAmount());
        }
        if (total.isGreaterThan(economy.config().maxTransferAmount())) {
            throw DomainException.of("economy.amount_too_large", "max", economy.config().maxTransferAmount());
        }
        return total;
    }

    // ------------------------------------------------------------------ expiry

    public int expireDue() {
        int expired = expireOffers();
        List<Long> bidIds = database.inTransaction(tx -> tx.queryList(
                "SELECT id FROM share_bids WHERE status = 'OPEN' AND expires_at <= ? ORDER BY expires_at LIMIT 100",
                rs -> rs.getLong(1), clock.instant()));
        for (long id : bidIds) {
            boolean done = database.inTransaction(tx -> {
                long companyId = tx.queryLong("SELECT company_id FROM share_bids WHERE id = ?", id);
                lockCompany(tx, companyId);
                Bid bid = lockBid(tx, id);
                if (!"OPEN".equals(bid.status()) || bid.expiresAt().isAfter(clock.instant())) {
                    return false;
                }
                closeBid(tx, bid, "EXPIRED");
                return true;
            });
            expired += done ? 1 : 0;
        }
        return expired;
    }

    private int expireOffers() {
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
                Account to = economy.requireAccount(tx, holderAccount(holder));
                economy.transfer(tx, new TransferRequest(account.id(), to.id(), Money.ofOre(Math.multiplyExact(perShare, holder.total())),
                        TransactionType.DIVIDEND, "dividend:" + id + ":" + holder.holderType() + ":" + holder.holderId(), actor,
                        "DIVIDEND", Long.toString(id), null));
            }
            return new Dividend(id, Money.ofOre(perShare), outstanding, total, holders.size());
        });
    }

    /**
     * Company closes (dissolution or solvent bankruptcy): open offers and bids are cancelled (including this company's
     * investments in others), remaining holdings of other companies are split pro rata, and the residual cash is split
     * pro rata by shares outside the treasury. Rounding dust, or everything if nobody holds shares, goes to the owner.
     */
    private void closeEquity(Tx tx, Company company, Money residual, UUID actor) throws SQLException {
        long companyId = company.id();
        closeMarketForCompany(tx, companyId);
        distributeInvestments(tx, company);
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
                companies.payFromCompany(tx, companyId, holderAccount(holder), Money.ofOre(part), actor,
                        "equity:" + companyId + ":" + holder.holderType() + ":" + holder.holderId());
                paid += part;
            }
        }
        companies.payFromCompany(tx, companyId, AccountOwner.player(company.ownerUuid()), Money.ofOre(residual.ore() - paid), actor,
                "equity-rest:" + companyId);
    }

    /** Cancels this company's share market activity so escrowed money and listed shares return before residual cash is read. */
    void closeMarketForCompany(Tx tx, long companyId) throws SQLException {
        for (Long offerId : tx.queryList("""
                        SELECT id FROM share_offers
                        WHERE status = 'OPEN' AND (company_id = ? OR (seller_type = 'COMPANY' AND seller_id = ?))
                        ORDER BY id""", rs -> rs.getLong(1), companyId, Long.toString(companyId))) {
            closeOffer(tx, lockOffer(tx, offerId), "CANCELLED");
        }
        for (Long bidId : tx.queryList("""
                        SELECT id FROM share_bids
                        WHERE status = 'OPEN' AND (company_id = ? OR buyer_company_id = ?)
                        ORDER BY id""", rs -> rs.getLong(1), companyId, companyId)) {
            closeBid(tx, lockBid(tx, bidId), "CANCELLED");
        }
    }

    private void distributeInvestments(Tx tx, Company company) throws SQLException {
        long companyId = company.id();
        List<long[]> holdings = tx.queryList("""
                        SELECT company_id, quantity FROM share_holdings
                        WHERE holder_type = 'COMPANY' AND holder_id = ? AND company_id <> ?
                        ORDER BY company_id""",
                rs -> new long[]{rs.getLong(1), rs.getLong(2)}, Long.toString(companyId), companyId);
        if (holdings.isEmpty()) {
            return;
        }
        List<Position> holders = shareholders(tx, companyId);
        long outstanding = holders.stream().mapToLong(Position::total).sum();
        for (long[] row : holdings) {
            long targetId = row[0];
            long qty = row[1];
            removeShares(tx, targetId, Holder.company(companyId), qty);
            long assigned = 0;
            if (outstanding > 0) {
                for (Position holder : holders) {
                    long part = Math.multiplyExact(qty, holder.total()) / outstanding;
                    if (part > 0) {
                        addShares(tx, targetId, toHolder(holder), part);
                        assigned += part;
                    }
                }
            }
            long rest = qty - assigned;
            if (rest > 0) {
                addShares(tx, targetId, Holder.player(company.ownerUuid()), rest);
            }
        }
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

    /** One row of the share market overview. {@code bestAsk} counts public offers only. */
    public record Listing(long companyId, String companyName, Money bestAsk, long askQuantity, Money bestBid, Money lastPrice,
                          long volume30d, Money turnover30d) {
    }

    /**
     * Share market overview: active companies with public offers or recent trades, busiest first. There is no central
     * order book or market maker — this only aggregates player offers and trade history.
     */
    public List<Listing> market(int limit) {
        return database.inTransaction(tx -> {
            Instant now = clock.instant();
            Instant since = now.minus(Duration.ofDays(30));
            return tx.queryList("""
                            WITH asks AS (
                                SELECT company_id, MIN(price_per_share) AS best FROM share_offers
                                WHERE status = 'OPEN' AND buyer_uuid IS NULL AND expires_at > ? GROUP BY company_id),
                            bids AS (
                                SELECT company_id, MAX(price_per_share) AS best FROM share_bids
                                WHERE status = 'OPEN' AND expires_at > ? GROUP BY company_id),
                            recent AS (
                                SELECT company_id, SUM(quantity) AS volume, SUM(total) AS turnover FROM share_trades
                                WHERE created_at >= ? GROUP BY company_id)
                            SELECT c.id, c.name, a.best, bd.best AS best_bid,
                                   (SELECT COALESCE(SUM(o.remaining), 0) FROM share_offers o
                                    WHERE o.company_id = c.id AND o.status = 'OPEN' AND o.buyer_uuid IS NULL AND o.expires_at > ?
                                      AND o.price_per_share = a.best) AS ask_quantity,
                                   (SELECT t.price_per_share FROM share_trades t WHERE t.company_id = c.id
                                    ORDER BY t.created_at DESC, t.id DESC LIMIT 1) AS last_price,
                                   COALESCE(r.volume, 0) AS volume, COALESCE(r.turnover, 0) AS turnover
                            FROM companies c
                            LEFT JOIN asks a ON a.company_id = c.id
                            LEFT JOIN bids bd ON bd.company_id = c.id
                            LEFT JOIN recent r ON r.company_id = c.id
                            WHERE c.status = 'ACTIVE' AND (a.best IS NOT NULL OR bd.best IS NOT NULL OR r.volume IS NOT NULL)
                            ORDER BY turnover DESC, c.name LIMIT ?""",
                    rs -> {
                        long best = rs.getLong("best");
                        Money bestAsk = rs.wasNull() ? null : Money.ofOre(best);
                        long bid = rs.getLong("best_bid");
                        Money bestBid = rs.wasNull() ? null : Money.ofOre(bid);
                        long last = rs.getLong("last_price");
                        Money lastPrice = rs.wasNull() ? null : Money.ofOre(last);
                        return new Listing(rs.getLong("id"), rs.getString("name"), bestAsk, rs.getLong("ask_quantity"), bestBid, lastPrice,
                                rs.getLong("volume"), Money.ofOre(rs.getLong("turnover")));
                    },
                    now, now, since, now, Math.clamp(limit, 1, 30));
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

    public List<Position> companyPortfolio(long companyId) {
        return database.inTransaction(tx -> tx.queryList(POSITIONS + """
                         WHERE p.holder_type = 'COMPANY' AND p.holder_id = ? AND c.status = 'ACTIVE'
                         ORDER BY p.quantity + p.listed DESC""", ShareService::mapPosition, Long.toString(companyId)));
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

    /** Shareholders with free and listed shares (the treasury is never a shareholder). */
    private List<Position> shareholders(Tx tx, long companyId) throws SQLException {
        return tx.queryList(POSITIONS + " WHERE p.company_id = ? AND p.holder_type IN ('PLAYER', 'COMPANY') ORDER BY p.holder_type, p.holder_id",
                ShareService::mapPosition, companyId);
    }

    private boolean hasOutsideShareholders(Tx tx, long companyId) throws SQLException {
        return tx.queryOne("""
                SELECT 1 FROM companies c
                WHERE c.id = ? AND (
                    EXISTS (SELECT 1 FROM share_holdings h WHERE h.company_id = c.id AND h.holder_type = 'PLAYER' AND h.holder_id <> c.owner_uuid::text)
                 OR EXISTS (SELECT 1 FROM share_holdings h WHERE h.company_id = c.id AND h.holder_type = 'COMPANY')
                 OR EXISTS (SELECT 1 FROM share_offers o WHERE o.company_id = c.id AND o.status = 'OPEN' AND o.seller_type = 'PLAYER'
                                                       AND o.seller_id <> c.owner_uuid::text)
                 OR EXISTS (SELECT 1 FROM share_offers o WHERE o.company_id = c.id AND o.status = 'OPEN' AND o.seller_type = 'COMPANY'))""",
                rs -> true, companyId).isPresent();
    }

    private void addShares(Tx tx, long companyId, Holder holder, long quantity) throws SQLException {
        if (holder.type() == HolderType.COMPANY && holder.id().equals(Long.toString(companyId))) {
            holder = Holder.treasury(companyId);
        }
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

    private void lockActiveAscending(Tx tx, List<Long> companyIds) throws SQLException {
        List<Long> ordered = companyIds.stream().distinct().sorted().toList();
        for (long id : ordered) {
            companies.lockActive(tx, id);
        }
    }

    private void requireNoArrears(Tx tx, long companyId) throws SQLException {
        if (payroll.arrears(tx, companyId).isPositive()) {
            throw DomainException.of("company.withdraw_blocked_by_arrears", "arrears", payroll.arrears(tx, companyId));
        }
    }

    /**
     * If {@code actingCompanyId} has outside shareholders, its trades in {@code targetCompanyId} cannot be used to
     * extract capital: buys are capped at {@code multiple × book}, sales cannot go below {@code book / multiple}.
     */
    private void guardCompanyPrice(Tx tx, long actingCompanyId, long targetCompanyId, Money pricePerShare, boolean buying)
            throws SQLException {
        if (!hasOutsideShareholders(tx, actingCompanyId)) {
            return;
        }
        Money book = bookValuePerShare(tx, targetCompanyId);
        int multiple = config.maxInvestmentBookMultiple();
        if (buying) {
            if (book.ore() < 1) {
                throw new DomainException("shares.price_not_justified");
            }
            long max = Math.multiplyExact(book.ore(), multiple);
            if (pricePerShare.ore() > max) {
                throw DomainException.of("shares.price_above_book", "max", Money.ofOre(max));
            }
        } else if (book.ore() > 0) {
            long min = book.ore() / multiple;
            if (pricePerShare.ore() < min) {
                throw DomainException.of("shares.price_below_book", "min", Money.ofOre(min));
            }
        }
    }

    private Money bookValuePerShare(Tx tx, long companyId) throws SQLException {
        long total = totalShares(tx, companyId);
        long treasury = tx.queryLong("""
                SELECT COALESCE((SELECT quantity FROM share_holdings WHERE company_id = ? AND holder_type = 'TREASURY'), 0)
                     + COALESCE((SELECT SUM(remaining) FROM share_offers WHERE company_id = ? AND seller_type = 'TREASURY' AND status = 'OPEN'), 0)""",
                companyId, companyId);
        long outstanding = total - treasury;
        if (outstanding < 1) {
            return Money.ZERO;
        }
        long equity = finance.balance(tx, companyId).equity().ore();
        if (equity < 1) {
            return Money.ZERO;
        }
        return Money.ofOre(equity / outstanding);
    }

    private static AccountOwner holderAccount(Position holder) {
        return toHolder(holder).account();
    }

    private static Holder toHolder(Position position) {
        return new Holder(position.holderType(), position.holderId());
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
                   CASE WHEN p.holder_type = 'PLAYER' THEN pl.name ELSE COALESCE(hc.name, c.name) END AS holder_name
            FROM (SELECT company_id, holder_type, holder_id, SUM(quantity) AS quantity, SUM(listed) AS listed
                  FROM (SELECT company_id, holder_type, holder_id, quantity, 0 AS listed FROM share_holdings
                        UNION ALL
                        SELECT company_id, seller_type, seller_id, 0, remaining FROM share_offers WHERE status = 'OPEN') u
                  GROUP BY company_id, holder_type, holder_id) p
            JOIN companies c ON c.id = p.company_id
            LEFT JOIN players pl ON p.holder_type = 'PLAYER' AND pl.uuid::text = p.holder_id
            LEFT JOIN companies hc ON p.holder_type IN ('COMPANY', 'TREASURY') AND hc.id::text = p.holder_id
            """;

    private static final String OFFER_SELECT = """
            SELECT o.*, c.name AS company_name,
                   CASE WHEN o.seller_type = 'PLAYER' THEN sp.name ELSE COALESCE(sc.name, c.name) END AS seller_name,
                   bp.name AS buyer_name
            FROM share_offers o
            JOIN companies c ON c.id = o.company_id
            LEFT JOIN players sp ON o.seller_type = 'PLAYER' AND sp.uuid::text = o.seller_id
            LEFT JOIN companies sc ON o.seller_type IN ('COMPANY', 'TREASURY') AND sc.id::text = o.seller_id
            LEFT JOIN players bp ON bp.uuid = o.buyer_uuid
            """;

    private static final String BID_SELECT = """
            SELECT b.*, c.name AS company_name, CASE WHEN b.buyer_company_id IS NOT NULL THEN bc.name ELSE p.name END AS buyer_name
            FROM share_bids b
            JOIN companies c ON c.id = b.company_id
            JOIN players p ON p.uuid = b.buyer_uuid
            LEFT JOIN companies bc ON bc.id = b.buyer_company_id
            """;

    private static Bid mapBid(ResultSet rs) throws SQLException {
        long buyerCompany = rs.getLong("buyer_company_id");
        Long buyerCompanyId = rs.wasNull() ? null : buyerCompany;
        return new Bid(rs.getLong("id"), rs.getLong("company_id"), rs.getString("company_name"), Tx.uuid(rs, "buyer_uuid"),
                rs.getString("buyer_name"), buyerCompanyId, rs.getLong("remaining"), Money.ofOre(rs.getLong("price_per_share")),
                Tx.instant(rs, "expires_at"), rs.getString("status"));
    }

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
