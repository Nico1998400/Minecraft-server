package se.nordia.swedencore.logistics;

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
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.reputation.ReputationService;
import se.nordia.swedencore.skills.Skill;
import se.nordia.swedencore.skills.SkillService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Logistics: moving goods physically between two places.
 *
 * <p>Trust model: the issuer escrows the reward and hands over real cargo; the carrier escrows collateral when picking
 * the cargo up. Delivering returns the collateral and pays the reward; missing the deadline forfeits the collateral to
 * the issuer as compensation for the lost goods. Stealing cargo therefore costs at least the collateral.
 */
public final class TransportService {

    public record Point(String world, int x, int y, int z) {
        long distanceTo(Point other) {
            double dx = x - other.x;
            double dz = z - other.z;
            return Math.round(Math.sqrt(dx * dx + dz * dz));
        }
    }

    public record Config(int minDistance, int maxDurationHours, int feePercent, long blocksPerXp, long maxXp, int failureReputation) {
        public static Config defaults() {
            return new Config(100, 24 * 7, 2, 10, 2_000, -5);
        }
    }

    public record Transport(long id, IssuerKind issuerType, UUID issuerPlayer, Long issuerCompanyId, String issuerName,
                            String material, int quantity, Money reward, Money collateral, long xpReward, Point pickup,
                            Point destination, Status status, UUID carrier, String carrierName, Instant deadline) {
        public long distance() {
            return pickup.distanceTo(destination);
        }
    }

    public enum IssuerKind {
        PLAYER, COMPANY
    }

    public enum Status {
        OPEN, IN_TRANSIT, DELIVERED, CANCELLED, FAILED
    }

    private final Database database;
    private final EconomyService economy;
    private final CompanyService companies;
    private final ItemStashService stash;
    private final SkillService skills;
    private final ReputationService reputation;
    private final Config config;
    private final Clock clock;

    public TransportService(Database database, EconomyService economy, CompanyService companies, ItemStashService stash,
                            SkillService skills, ReputationService reputation, Config config, Clock clock) {
        this.database = database;
        this.economy = economy;
        this.companies = companies;
        this.stash = stash;
        this.skills = skills;
        this.reputation = reputation;
        this.config = config;
        this.clock = clock;
        companies.addDissolutionCheck((tx, companyId) -> {
            if (tx.queryLong("SELECT count(*) FROM transports WHERE issuer_company_id = ? AND status IN ('OPEN', 'IN_TRANSIT')", companyId) > 0) {
                throw new DomainException("company.dissolve_has_transports");
            }
        });
    }

    public Config config() {
        return config;
    }

    /** Creates a transport job; the cargo is taken from the issuer's inventory (pristine stacks) immediately. */
    public Transport create(UUID actor, Long companyId, String material, int quantity, Money reward, Money collateral,
                            Point pickup, Point destination, int hours, ItemStashService.ItemCodec codec) {
        if (!ItemStashService.MATERIAL.matcher(material).matches() || !codec.isKnownMaterial(material)) {
            throw new DomainException("contract.invalid_material");
        }
        if (quantity < 1 || quantity > 100_000) {
            throw new DomainException("contract.invalid_quantity");
        }
        if (!reward.isPositive() || collateral.isNegative()) {
            throw new DomainException("economy.invalid_amount");
        }
        if (!pickup.world().equals(destination.world()) || pickup.distanceTo(destination) < config.minDistance()) {
            throw DomainException.of("transport.too_close", "min", config.minDistance());
        }
        if (hours < 1 || hours > config.maxDurationHours()) {
            throw DomainException.of("contract.invalid_duration", "max", config.maxDurationHours());
        }
        long distance = pickup.distanceTo(destination);
        long stacks = Math.max(1, (quantity + 63) / 64);
        long xp = Math.min(config.maxXp(), distance / config.blocksPerXp() * Math.min(4, stacks));
        Money fee = Money.ofOre(Math.multiplyExact(reward.ore(), config.feePercent()) / 100);
        return database.inTransaction(tx -> {
            Account payer;
            ItemStashService.Owner cargoOwner;
            if (companyId != null) {
                companies.lockActive(tx, companyId);
                companies.requireRole(tx, companyId, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
                payer = economy.requireAccount(tx, AccountOwner.company(companyId));
                cargoOwner = ItemStashService.Owner.company(companyId);
            } else {
                payer = economy.requireAccount(tx, AccountOwner.player(actor));
                cargoOwner = ItemStashService.Owner.player(actor);
            }
            stash.consumePristine(tx, cargoOwner, Map.of(material, quantity), codec, actor);
            Instant now = clock.instant();
            long id = tx.queryLong("""
                            INSERT INTO transports (issuer_type, issuer_player_uuid, issuer_company_id, created_by, material, quantity, reward,
                                                    collateral, xp_reward, pickup_world, pickup_x, pickup_y, pickup_z, dest_world, dest_x,
                                                    dest_y, dest_z, created_at, deadline_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    companyId != null ? "COMPANY" : "PLAYER", companyId != null ? null : actor, companyId, actor, material, quantity,
                    reward.ore(), collateral.ore(), xp, pickup.world(), pickup.x(), pickup.y(), pickup.z(), destination.world(),
                    destination.x(), destination.y(), destination.z(), now, now.plus(Duration.ofHours(hours)));
            Account escrow = economy.getOrCreateAccount(tx, AccountOwner.transport(id), Account.ESCROW);
            economy.transfer(tx, new TransferRequest(payer.id(), escrow.id(), reward, TransactionType.TRANSPORT_ESCROW,
                    "transport-escrow:" + id, actor, "TRANSPORT", Long.toString(id), null));
            if (fee.isPositive()) {
                economy.burn(tx, payer.id(), fee, TransactionType.TRANSPORT_FEE, "transport-fee:" + id, actor);
            }
            return find(tx, id).orElseThrow();
        });
    }

    /**
     * The carrier picks up the cargo (the Paper layer checked they stand at the pickup point). Collateral is escrowed.
     * On success the caller hands out the cargo; {@link #pickupRecorded} resolves ambiguous failures.
     */
    public Transport pickUp(UUID carrier, long id, UUID token) {
        return database.inTransaction(tx -> {
            Transport t = lock(tx, id);
            if (t.status() != Status.OPEN) {
                throw new DomainException("transport.not_open");
            }
            if (!clock.instant().isBefore(t.deadline())) {
                throw new DomainException("contract.expired");
            }
            if (isIssuerSide(tx, t, carrier)) {
                throw new DomainException("transport.own_transport");
            }
            if (t.collateral().isPositive()) {
                Account from = economy.requireAccount(tx, AccountOwner.player(carrier));
                Account collateral = economy.getOrCreateAccount(tx, AccountOwner.transport(id), "COLLATERAL");
                economy.transfer(tx, new TransferRequest(from.id(), collateral.id(), t.collateral(), TransactionType.TRANSPORT_COLLATERAL,
                        "transport-collateral:" + id, carrier, "TRANSPORT", Long.toString(id), null));
            }
            tx.update("UPDATE transports SET status = 'IN_TRANSIT', carrier_uuid = ?, pickup_token = ?, picked_up_at = ? WHERE id = ?",
                    carrier, token, clock.instant(), id);
            return find(tx, id).orElseThrow();
        });
    }

    public boolean pickupRecorded(UUID token) {
        return database.inTransaction(tx -> tx.queryOne("SELECT 1 FROM transports WHERE pickup_token = ?", rs -> true, token)).isPresent();
    }

    /** Delivers the full cargo (the Paper layer removed exactly these items at the destination). */
    public Transport deliver(UUID carrier, long id, List<ItemStashService.StashItem> items, UUID token) {
        int quantity = items.stream().mapToInt(ItemStashService.StashItem::amount).sum();
        return database.inTransaction(tx -> {
            Transport t = lock(tx, id);
            if (t.status() != Status.IN_TRANSIT || !carrier.equals(t.carrier())) {
                throw new DomainException("transport.not_carrier");
            }
            if (!clock.instant().isBefore(t.deadline())) {
                throw new DomainException("contract.expired");
            }
            for (ItemStashService.StashItem item : items) {
                if (!item.material().equals(t.material())) {
                    throw new DomainException("contract.wrong_material");
                }
            }
            if (quantity != t.quantity()) {
                throw DomainException.of("transport.incomplete_cargo", "required", t.quantity(), "delivered", quantity);
            }
            ItemStashService.Owner owner = t.issuerType() == IssuerKind.PLAYER
                    ? ItemStashService.Owner.player(t.issuerPlayer()) : ItemStashService.Owner.company(t.issuerCompanyId());
            stash.deposit(tx, owner, items, "TRANSPORT", Long.toString(id));
            Account carrierAccount = economy.requireAccount(tx, AccountOwner.player(carrier));
            Account rewardEscrow = economy.findAccount(tx, AccountOwner.transport(id), Account.ESCROW).orElseThrow();
            economy.transfer(tx, new TransferRequest(rewardEscrow.id(), carrierAccount.id(), t.reward(), TransactionType.TRANSPORT_PAYOUT,
                    "transport-payout:" + id, carrier, "TRANSPORT", Long.toString(id), null));
            if (t.collateral().isPositive()) {
                Account collateral = economy.findAccount(tx, AccountOwner.transport(id), "COLLATERAL").orElseThrow();
                economy.transfer(tx, new TransferRequest(collateral.id(), carrierAccount.id(), t.collateral(),
                        TransactionType.TRANSPORT_COLLATERAL_RETURN, "transport-collateral-return:" + id, carrier, "TRANSPORT", Long.toString(id), null));
            }
            if (t.xpReward() > 0) {
                skills.addXp(tx, carrier, Skill.LOGISTICS, t.xpReward());
            }
            tx.update("UPDATE transports SET status = 'DELIVERED', delivery_token = ?, closed_at = now() WHERE id = ?", token, id);
            return find(tx, id).orElseThrow();
        });
    }

    public boolean deliveryRecorded(UUID token) {
        return database.inTransaction(tx -> tx.queryOne("SELECT 1 FROM transports WHERE delivery_token = ?", rs -> true, token)).isPresent();
    }

    /** Issuer cancels an OPEN transport: reward and cargo return (fee is kept). */
    public Transport cancel(UUID actor, long id, ItemStashService.ItemCodec codec) {
        return database.inTransaction(tx -> {
            Transport t = lock(tx, id);
            if (t.status() != Status.OPEN) {
                throw new DomainException("transport.not_open");
            }
            if (t.issuerType() == IssuerKind.PLAYER) {
                if (!actor.equals(t.issuerPlayer())) {
                    throw new DomainException("transport.not_issuer");
                }
            } else {
                companies.requireRole(tx, t.issuerCompanyId(), actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            }
            returnToIssuer(tx, t, codec);
            tx.update("UPDATE transports SET status = 'CANCELLED', closed_at = now() WHERE id = ?", id);
            return find(tx, id).orElseThrow();
        });
    }

    /** OPEN past deadline: cancelled with refunds. IN_TRANSIT past deadline: failed, collateral compensates the issuer. */
    public List<Transport> expireDue(ItemStashService.ItemCodec codec) {
        List<Long> ids = database.inTransaction(tx -> tx.queryList(
                "SELECT id FROM transports WHERE status IN ('OPEN', 'IN_TRANSIT') AND deadline_at <= ? ORDER BY deadline_at LIMIT 100",
                rs -> rs.getLong(1), clock.instant()));
        List<Transport> expired = new ArrayList<>();
        for (long id : ids) {
            Optional<Transport> result = database.inTransaction(tx -> {
                Transport t = lock(tx, id);
                if (t.deadline().isAfter(clock.instant())) {
                    return Optional.<Transport>empty();
                }
                if (t.status() == Status.OPEN) {
                    returnToIssuer(tx, t, codec);
                    tx.update("UPDATE transports SET status = 'CANCELLED', closed_at = now() WHERE id = ?", id);
                } else if (t.status() == Status.IN_TRANSIT) {
                    AccountOwner issuer = issuerAccount(t);
                    Account target = economy.requireAccount(tx, issuer);
                    Account rewardEscrow = economy.findAccount(tx, AccountOwner.transport(id), Account.ESCROW).orElseThrow();
                    economy.transfer(tx, new TransferRequest(rewardEscrow.id(), target.id(), t.reward(), TransactionType.TRANSPORT_REFUND,
                            "transport-refund:" + id, null, "TRANSPORT", Long.toString(id), null));
                    if (t.collateral().isPositive()) {
                        Account collateral = economy.findAccount(tx, AccountOwner.transport(id), "COLLATERAL").orElseThrow();
                        economy.transfer(tx, new TransferRequest(collateral.id(), target.id(), t.collateral(),
                                TransactionType.TRANSPORT_COLLATERAL_FORFEIT, "transport-forfeit:" + id, null, "TRANSPORT", Long.toString(id), null));
                    }
                    reputation.adjust(tx, ReputationService.Subject.player(t.carrier()), config.failureReputation(), "TRANSPORT_FAILED",
                            "TRANSPORT", Long.toString(id), "transport-failed:" + id);
                    tx.update("UPDATE transports SET status = 'FAILED', closed_at = now() WHERE id = ?", id);
                } else {
                    return Optional.<Transport>empty();
                }
                return find(tx, id);
            });
            result.ifPresent(expired::add);
        }
        return expired;
    }

    private void returnToIssuer(Tx tx, Transport t, ItemStashService.ItemCodec codec) throws SQLException {
        Account target = economy.requireAccount(tx, issuerAccount(t));
        Account escrow = economy.findAccount(tx, AccountOwner.transport(t.id()), Account.ESCROW).orElseThrow();
        economy.transfer(tx, new TransferRequest(escrow.id(), target.id(), t.reward(), TransactionType.TRANSPORT_REFUND,
                "transport-refund:" + t.id(), null, "TRANSPORT", Long.toString(t.id()), null));
        ItemStashService.Owner owner = t.issuerType() == IssuerKind.PLAYER
                ? ItemStashService.Owner.player(t.issuerPlayer()) : ItemStashService.Owner.company(t.issuerCompanyId());
        stash.depositPristine(tx, owner, t.material(), t.quantity(), codec, "TRANSPORT_RETURN", Long.toString(t.id()));
    }

    private static AccountOwner issuerAccount(Transport t) {
        return t.issuerType() == IssuerKind.PLAYER ? AccountOwner.player(t.issuerPlayer()) : AccountOwner.company(t.issuerCompanyId());
    }

    // ------------------------------------------------------------------ queries

    public Optional<Transport> find(long id) {
        return database.inTransaction(tx -> find(tx, id));
    }

    Optional<Transport> find(Tx tx, long id) throws SQLException {
        return tx.queryOne(SELECT + " WHERE t.id = ?", TransportService::map, id);
    }

    public List<Transport> open(int limit) {
        return database.inTransaction(tx -> tx.queryList(SELECT + " WHERE t.status = 'OPEN' AND t.deadline_at > ? ORDER BY t.reward DESC LIMIT ?",
                TransportService::map, clock.instant(), Math.clamp(limit, 1, 50)));
    }

    public List<Transport> involving(UUID player) {
        return database.inTransaction(tx -> tx.queryList(SELECT + """
                         WHERE t.status IN ('OPEN', 'IN_TRANSIT') AND (t.carrier_uuid = ? OR t.issuer_player_uuid = ?
                            OR t.issuer_company_id IN (SELECT company_id FROM company_employees
                                                       WHERE player_uuid = ? AND ended_at IS NULL AND role IN ('OWNER', 'MANAGER')))
                         ORDER BY t.deadline_at""", TransportService::map, player, player, player));
    }

    private boolean isIssuerSide(Tx tx, Transport t, UUID player) throws SQLException {
        if (t.issuerType() == IssuerKind.PLAYER) {
            return player.equals(t.issuerPlayer());
        }
        return companies.roleOf(tx, t.issuerCompanyId(), player).isPresent();
    }

    private Transport lock(Tx tx, long id) throws SQLException {
        tx.queryOne("SELECT id FROM transports WHERE id = ? FOR UPDATE", rs -> true, id)
                .orElseThrow(() -> new DomainException("transport.not_found"));
        return find(tx, id).orElseThrow();
    }

    private static final String SELECT = """
            SELECT t.*, COALESCE(c.name, ip.name) AS issuer_name, cp.name AS carrier_name
            FROM transports t
            LEFT JOIN companies c ON c.id = t.issuer_company_id
            LEFT JOIN players ip ON ip.uuid = t.issuer_player_uuid
            LEFT JOIN players cp ON cp.uuid = t.carrier_uuid
            """;

    private static Transport map(ResultSet rs) throws SQLException {
        return new Transport(rs.getLong("id"), IssuerKind.valueOf(rs.getString("issuer_type")), Tx.uuid(rs, "issuer_player_uuid"),
                Tx.nullableLong(rs, "issuer_company_id"), rs.getString("issuer_name"), rs.getString("material"), rs.getInt("quantity"),
                Money.ofOre(rs.getLong("reward")), Money.ofOre(rs.getLong("collateral")), rs.getLong("xp_reward"),
                new Point(rs.getString("pickup_world"), rs.getInt("pickup_x"), rs.getInt("pickup_y"), rs.getInt("pickup_z")),
                new Point(rs.getString("dest_world"), rs.getInt("dest_x"), rs.getInt("dest_y"), rs.getInt("dest_z")),
                Status.valueOf(rs.getString("status")), Tx.uuid(rs, "carrier_uuid"), rs.getString("carrier_name"),
                Tx.instant(rs, "deadline_at"));
    }
}
