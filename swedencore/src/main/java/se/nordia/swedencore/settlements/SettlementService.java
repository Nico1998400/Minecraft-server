package se.nordia.swedencore.settlements;

import se.nordia.swedencore.cities.City;
import se.nordia.swedencore.cities.CityService;
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
import se.nordia.swedencore.events.DomainEvent;
import se.nordia.swedencore.events.DomainEvents;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Player-founded settlements in the wilderness and their growth from outpost to city.
 *
 * <p>Growth is earned, not bought: every tier needs residents, a treasury, time and a trusted leader; the upgrade cost
 * is spent (sink) as public investment. Placement reserves room for the largest tier so settlements never grow into
 * cities or each other.
 */
public final class SettlementService {

    private static final Pattern NAME = Pattern.compile("[A-Za-zÅÄÖåäöÉéÜü][A-Za-zÅÄÖåäöÉéÜü0-9 '\\-]{2,31}");

    public record Member(UUID uuid, String name, Settlement.Role role, Instant joinedAt) {
    }

    public record Membership(Settlement settlement, Settlement.Role role) {
    }

    /** Status of each requirement for reaching {@code target}. */
    public record Progress(Settlement.Tier target, SettlementConfig.Requirements requirements, int members, Money treasury,
                           long ageDays, int leaderReputation) {
        public boolean membersMet() {
            return members >= requirements.minMembers();
        }

        public boolean treasuryMet() {
            return !treasury.isLessThan(requirements.minTreasury()) && !treasury.isLessThan(requirements.cost());
        }

        public boolean ageMet() {
            return ageDays >= requirements.minAgeDays();
        }

        public boolean reputationMet() {
            return leaderReputation >= requirements.minLeaderReputation();
        }

        public boolean ready() {
            return membersMet() && treasuryMet() && ageMet() && reputationMet();
        }
    }

    public record Summary(Settlement settlement, List<Member> members, Money treasury, Progress next) {
    }

    private final Database database;
    private final EconomyService economy;
    private final CityService cities;
    private final DomainEvents events;
    private final SettlementConfig config;
    private final Clock clock;

    public SettlementService(Database database, EconomyService economy, CityService cities, DomainEvents events,
                             SettlementConfig config, Clock clock) {
        this.database = database;
        this.economy = economy;
        this.cities = cities;
        this.events = events;
        this.config = config;
        this.clock = clock;
        // Administrators may not place a city on top of a settlement's reserved area either.
        cities.addPlacementCheck((tx, world, x, z, radius) -> {
            for (Settlement s : active(tx, world)) {
                if (chebyshev(s.centerX(), s.centerZ(), x, z) < radius + config.maxRadius() + config.spacingBuffer()) {
                    throw DomainException.of("city.too_close_to_settlement", "settlement", s.name());
                }
            }
        });
    }

    public SettlementConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ founding

    public Settlement found(UUID founder, String rawName, String world, int x, int z) {
        String name = rawName == null ? "" : rawName.trim();
        if (!NAME.matcher(name).matches() || name.contains("  ")) {
            throw new DomainException("settlement.invalid_name");
        }
        SettlementConfig.Requirements outpost = config.requirements(Settlement.Tier.OUTPOST);
        Settlement settlement = database.inTransaction(tx -> {
            int reputation = tx.queryOne("SELECT reputation FROM players WHERE uuid = ? FOR UPDATE", rs -> rs.getInt(1), founder)
                    .orElseThrow(() -> new DomainException("player.unknown"));
            if (memberOf(tx, founder).isPresent()) {
                throw new DomainException("settlement.already_member");
            }
            if (reputation < outpost.minLeaderReputation()) {
                throw DomainException.of("settlement.reputation_too_low", "required", outpost.minLeaderReputation());
            }
            tx.queryOne("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> true, "settlements:" + world);
            if (tx.queryOne("SELECT 1 FROM settlements WHERE lower(name) = lower(?) AND status = 'ACTIVE'", rs -> true, name).isPresent()) {
                throw new DomainException("settlement.name_taken");
            }
            int reserved = config.maxRadius();
            for (Settlement other : active(tx, world)) {
                if (chebyshev(other.centerX(), other.centerZ(), x, z) < 2L * reserved + config.spacingBuffer()) {
                    throw DomainException.of("settlement.too_close", "settlement", other.name());
                }
            }
            for (City city : cities.all(tx)) {
                if (city.world().equals(world) && chebyshev(city.centerX(), city.centerZ(), x, z) < (long) city.radius() + reserved + config.spacingBuffer()) {
                    throw DomainException.of("settlement.too_close_to_city", "city", city.name());
                }
            }
            if (outpost.cost().isPositive()) {
                Account account = economy.requireAccount(tx, AccountOwner.player(founder));
                economy.burn(tx, account.id(), outpost.cost(), TransactionType.SETTLEMENT_FEE, null, founder);
            }
            // Age requirements are measured with the service clock, so the founding time must come from it too.
            long id = tx.queryLong("""
                            INSERT INTO settlements (name, world, center_x, center_z, leader_uuid, founded_at)
                            VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                    name, world, x, z, founder, clock.instant());
            economy.getOrCreateAccount(tx, AccountOwner.settlement(id), Account.MAIN);
            tx.update("INSERT INTO settlement_members (settlement_id, player_uuid, role) VALUES (?, ?, 'LEADER')", id, founder);
            tx.update("DELETE FROM settlement_invites WHERE player_uuid = ?", founder);
            tx.update("INSERT INTO settlement_tier_history (settlement_id, tier, members, treasury) VALUES (?, 'OUTPOST', 1, 0)", id);
            return find(tx, id).orElseThrow();
        });
        events.publish(new DomainEvent.SettlementChanged(settlement.id()));
        return settlement;
    }

    // ------------------------------------------------------------------ membership

    public void invite(UUID actor, long settlementId, UUID target) {
        database.inTransactionVoid(tx -> {
            lockActive(tx, settlementId);
            requireRole(tx, settlementId, actor, Settlement.Role.LEADER, Settlement.Role.OFFICER);
            if (memberOf(tx, target).isPresent()) {
                throw new DomainException("settlement.target_already_member");
            }
            if (tx.queryLong("SELECT count(*) FROM settlement_invites WHERE settlement_id = ?", settlementId) >= config.maxInvites()) {
                throw DomainException.of("settlement.too_many_invites", "max", config.maxInvites());
            }
            tx.update("INSERT INTO settlement_invites (settlement_id, player_uuid, invited_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    settlementId, target, actor);
        });
    }

    public Settlement join(UUID player, String settlementName) {
        Settlement joined = database.inTransaction(tx -> {
            Settlement settlement = findActiveByName(tx, settlementName).orElseThrow(() -> new DomainException("settlement.not_found"));
            lockActive(tx, settlement.id());
            tx.queryOne("SELECT uuid FROM players WHERE uuid = ? FOR UPDATE", rs -> true, player)
                    .orElseThrow(() -> new DomainException("player.unknown"));
            if (memberOf(tx, player).isPresent()) {
                throw new DomainException("settlement.already_member");
            }
            if (tx.queryOne("SELECT 1 FROM settlement_invites WHERE settlement_id = ? AND player_uuid = ?", rs -> true, settlement.id(), player).isEmpty()) {
                throw new DomainException("settlement.not_invited");
            }
            tx.update("INSERT INTO settlement_members (settlement_id, player_uuid, role) VALUES (?, ?, 'RESIDENT')", settlement.id(), player);
            tx.update("DELETE FROM settlement_invites WHERE player_uuid = ?", player);
            return settlement;
        });
        events.publish(new DomainEvent.SettlementChanged(joined.id()));
        return joined;
    }

    public void leave(UUID player) {
        long settlementId = database.inTransaction(tx -> {
            Membership membership = memberOf(tx, player).orElseThrow(() -> new DomainException("settlement.not_member"));
            lockActive(tx, membership.settlement().id());
            if (membership.role() == Settlement.Role.LEADER) {
                throw new DomainException("settlement.leader_cannot_leave");
            }
            endMembership(tx, membership.settlement().id(), player);
            return membership.settlement().id();
        });
        events.publish(new DomainEvent.SettlementChanged(settlementId));
    }

    /** Leader removes anyone; officers remove residents. */
    public void kick(UUID actor, long settlementId, UUID target) {
        database.inTransactionVoid(tx -> {
            lockActive(tx, settlementId);
            Settlement.Role actorRole = requireRole(tx, settlementId, actor, Settlement.Role.LEADER, Settlement.Role.OFFICER);
            Settlement.Role targetRole = roleOf(tx, settlementId, target).orElseThrow(() -> new DomainException("settlement.target_not_member"));
            if (targetRole == Settlement.Role.LEADER || (actorRole == Settlement.Role.OFFICER && targetRole != Settlement.Role.RESIDENT)) {
                throw new DomainException("settlement.no_permission");
            }
            endMembership(tx, settlementId, target);
        });
        events.publish(new DomainEvent.SettlementChanged(settlementId));
    }

    public void setRole(UUID actor, long settlementId, UUID target, Settlement.Role role) {
        if (role == Settlement.Role.LEADER) {
            throw new DomainException("settlement.no_permission");
        }
        database.inTransactionVoid(tx -> {
            lockActive(tx, settlementId);
            requireRole(tx, settlementId, actor, Settlement.Role.LEADER);
            Settlement.Role targetRole = roleOf(tx, settlementId, target).orElseThrow(() -> new DomainException("settlement.target_not_member"));
            if (targetRole == Settlement.Role.LEADER) {
                throw new DomainException("settlement.no_permission");
            }
            tx.update("UPDATE settlement_members SET role = ? WHERE settlement_id = ? AND player_uuid = ? AND left_at IS NULL", role, settlementId, target);
        });
    }

    public void transferLeadership(UUID actor, long settlementId, UUID target) {
        database.inTransactionVoid(tx -> {
            lockActive(tx, settlementId);
            requireRole(tx, settlementId, actor, Settlement.Role.LEADER);
            if (roleOf(tx, settlementId, target).isEmpty() || actor.equals(target)) {
                throw new DomainException("settlement.target_not_member");
            }
            tx.update("UPDATE settlement_members SET role = 'OFFICER' WHERE settlement_id = ? AND player_uuid = ? AND left_at IS NULL", settlementId, actor);
            tx.update("UPDATE settlement_members SET role = 'LEADER' WHERE settlement_id = ? AND player_uuid = ? AND left_at IS NULL", settlementId, target);
            tx.update("UPDATE settlements SET leader_uuid = ? WHERE id = ?", target, settlementId);
        });
        events.publish(new DomainEvent.SettlementChanged(settlementId));
    }

    // ------------------------------------------------------------------ treasury

    public TransferReceipt deposit(UUID actor, long settlementId, Money amount) {
        return database.inTransaction(tx -> {
            lockActive(tx, settlementId);
            requireRole(tx, settlementId, actor, Settlement.Role.values());
            Account from = economy.requireAccount(tx, AccountOwner.player(actor));
            Account to = economy.requireAccount(tx, AccountOwner.settlement(settlementId));
            return economy.transfer(tx, TransferRequest.of(from.id(), to.id(), amount, TransactionType.SETTLEMENT_DEPOSIT)
                    .withActor(actor).withReference("SETTLEMENT", Long.toString(settlementId)));
        });
    }

    /** Only the leader controls the treasury; every withdrawal is in the public ledger. */
    public TransferReceipt withdraw(UUID actor, long settlementId, Money amount) {
        return database.inTransaction(tx -> {
            lockActive(tx, settlementId);
            requireRole(tx, settlementId, actor, Settlement.Role.LEADER);
            Account from = economy.requireAccount(tx, AccountOwner.settlement(settlementId));
            Account to = economy.requireAccount(tx, AccountOwner.player(actor));
            return economy.transfer(tx, TransferRequest.of(from.id(), to.id(), amount, TransactionType.SETTLEMENT_WITHDRAWAL)
                    .withActor(actor).withReference("SETTLEMENT", Long.toString(settlementId)));
        });
    }

    // ------------------------------------------------------------------ growth

    public Settlement upgrade(UUID actor, long settlementId) {
        Settlement upgraded = database.inTransaction(tx -> {
            Settlement settlement = lockActive(tx, settlementId);
            requireRole(tx, settlementId, actor, Settlement.Role.LEADER);
            Progress progress = progress(tx, settlement)
                    .orElseThrow(() -> new DomainException("settlement.max_tier"));
            if (!progress.ready()) {
                throw new DomainException("settlement.requirements_not_met");
            }
            Account treasury = economy.requireAccount(tx, AccountOwner.settlement(settlementId));
            if (progress.requirements().cost().isPositive()) {
                economy.burn(tx, treasury.id(), progress.requirements().cost(), TransactionType.SETTLEMENT_FEE,
                        "settlement-upgrade:" + settlementId + ":" + progress.target(), actor);
            }
            tx.update("UPDATE settlements SET tier = ? WHERE id = ?", progress.target(), settlementId);
            tx.update("INSERT INTO settlement_tier_history (settlement_id, tier, members, treasury) VALUES (?, ?, ?, ?)",
                    settlementId, progress.target(), progress.members(),
                    progress.treasury().minus(progress.requirements().cost()).ore());
            return find(tx, settlementId).orElseThrow();
        });
        events.publish(new DomainEvent.SettlementChanged(settlementId));
        return upgraded;
    }

    /** Requirements for the next tier, or empty at the top tier. */
    public Optional<Progress> progress(Tx tx, Settlement settlement) throws SQLException {
        Settlement.Tier next = settlement.tier().next();
        if (next == null) {
            return Optional.empty();
        }
        int members = (int) tx.queryLong("SELECT count(*) FROM settlement_members WHERE settlement_id = ? AND left_at IS NULL", settlement.id());
        Money treasury = economy.findAccount(tx, AccountOwner.settlement(settlement.id()), Account.MAIN).map(Account::balance).orElse(Money.ZERO);
        long ageDays = Duration.between(settlement.foundedAt(), clock.instant()).toDays();
        int reputation = tx.queryOne("SELECT reputation FROM players WHERE uuid = ?", rs -> rs.getInt(1), settlement.leader()).orElse(0);
        return Optional.of(new Progress(next, config.requirements(next), members, treasury, ageDays, reputation));
    }

    /** Disbanding requires that everyone else has left; the treasury goes to the leader. */
    public Money disband(UUID actor, long settlementId) {
        Money payout = database.inTransaction(tx -> {
            lockActive(tx, settlementId);
            requireRole(tx, settlementId, actor, Settlement.Role.LEADER);
            if (tx.queryLong("SELECT count(*) FROM settlement_members WHERE settlement_id = ? AND left_at IS NULL AND role <> 'LEADER'", settlementId) > 0) {
                throw new DomainException("settlement.disband_has_members");
            }
            Account treasury = economy.requireAccount(tx, AccountOwner.settlement(settlementId));
            Money remaining = treasury.balance();
            if (remaining.isPositive()) {
                Account leader = economy.requireAccount(tx, AccountOwner.player(actor));
                economy.transfer(tx, TransferRequest.of(treasury.id(), leader.id(), remaining, TransactionType.DISSOLUTION_PAYOUT)
                        .withActor(actor).withReference("SETTLEMENT", Long.toString(settlementId)));
            }
            endMembership(tx, settlementId, actor);
            tx.update("DELETE FROM settlement_invites WHERE settlement_id = ?", settlementId);
            tx.update("UPDATE settlements SET status = 'DISBANDED', disbanded_at = now() WHERE id = ?", settlementId);
            return remaining;
        });
        events.publish(new DomainEvent.SettlementChanged(settlementId));
        return payout;
    }

    // ------------------------------------------------------------------ queries

    public List<Settlement> allActive() {
        return database.inTransaction(tx -> tx.queryList(SELECT + " WHERE s.status = 'ACTIVE' ORDER BY s.id", this::map));
    }

    public Optional<Settlement> find(long id) {
        return database.inTransaction(tx -> find(tx, id));
    }

    public Optional<Settlement> find(Tx tx, long id) throws SQLException {
        return tx.queryOne(SELECT + " WHERE s.id = ?", this::map, id);
    }

    public Settlement requireActiveByName(String name) {
        return database.inTransaction(tx -> findActiveByName(tx, name)).orElseThrow(() -> new DomainException("settlement.not_found"));
    }

    public Optional<Membership> memberOf(UUID player) {
        return database.inTransaction(tx -> memberOf(tx, player));
    }

    public Optional<Membership> memberOf(Tx tx, UUID player) throws SQLException {
        return tx.queryOne("""
                        SELECT s.*, p.name AS leader_name, m.role AS member_role FROM settlements s
                        JOIN players p ON p.uuid = s.leader_uuid
                        JOIN settlement_members m ON m.settlement_id = s.id
                        WHERE m.player_uuid = ? AND m.left_at IS NULL AND s.status = 'ACTIVE'""",
                rs -> new Membership(map(rs), Settlement.Role.valueOf(rs.getString("member_role"))), player);
    }

    public List<UUID> memberIds(long settlementId) {
        return database.inTransaction(tx -> tx.queryList("SELECT player_uuid FROM settlement_members WHERE settlement_id = ? AND left_at IS NULL",
                rs -> Tx.uuid(rs, "player_uuid"), settlementId));
    }

    public List<String> pendingInvitesFor(UUID player) {
        return database.inTransaction(tx -> tx.queryList("""
                SELECT s.name FROM settlement_invites i JOIN settlements s ON s.id = i.settlement_id
                WHERE i.player_uuid = ? AND s.status = 'ACTIVE' ORDER BY i.created_at""", rs -> rs.getString(1), player));
    }

    public Summary summary(long settlementId) {
        return database.inTransaction(tx -> {
            Settlement settlement = find(tx, settlementId).orElseThrow(() -> new DomainException("settlement.not_found"));
            List<Member> members = tx.queryList("""
                            SELECT m.player_uuid, p.name, m.role, m.joined_at FROM settlement_members m
                            JOIN players p ON p.uuid = m.player_uuid
                            WHERE m.settlement_id = ? AND m.left_at IS NULL ORDER BY m.role, m.joined_at""",
                    rs -> new Member(Tx.uuid(rs, "player_uuid"), rs.getString("name"), Settlement.Role.valueOf(rs.getString("role")),
                            Tx.instant(rs, "joined_at")), settlementId);
            Money treasury = economy.findAccount(tx, AccountOwner.settlement(settlementId), Account.MAIN).map(Account::balance).orElse(Money.ZERO);
            return new Summary(settlement, members, treasury, progress(tx, settlement).orElse(null));
        });
    }

    // ------------------------------------------------------------------ helpers

    private List<Settlement> active(Tx tx, String world) throws SQLException {
        return tx.queryList(SELECT + " WHERE s.status = 'ACTIVE' AND s.world = ?", this::map, world);
    }

    private Optional<Settlement> findActiveByName(Tx tx, String name) throws SQLException {
        return tx.queryOne(SELECT + " WHERE lower(s.name) = lower(?) AND s.status = 'ACTIVE'", this::map, name == null ? "" : name.trim());
    }

    private Settlement lockActive(Tx tx, long id) throws SQLException {
        String status = tx.queryOne("SELECT status FROM settlements WHERE id = ? FOR UPDATE", rs -> rs.getString(1), id)
                .orElseThrow(() -> new DomainException("settlement.not_found"));
        if (!"ACTIVE".equals(status)) {
            throw new DomainException("settlement.not_found");
        }
        return find(tx, id).orElseThrow();
    }

    private Optional<Settlement.Role> roleOf(Tx tx, long settlementId, UUID player) throws SQLException {
        return tx.queryOne("SELECT role FROM settlement_members WHERE settlement_id = ? AND player_uuid = ? AND left_at IS NULL",
                rs -> Settlement.Role.valueOf(rs.getString(1)), settlementId, player);
    }

    private Settlement.Role requireRole(Tx tx, long settlementId, UUID actor, Settlement.Role... allowed) throws SQLException {
        Settlement.Role role = roleOf(tx, settlementId, actor).orElseThrow(() -> new DomainException("settlement.not_member"));
        if (!Arrays.asList(allowed).contains(role)) {
            throw new DomainException("settlement.no_permission");
        }
        return role;
    }

    private static void endMembership(Tx tx, long settlementId, UUID player) throws SQLException {
        tx.update("UPDATE settlement_members SET left_at = now() WHERE settlement_id = ? AND player_uuid = ? AND left_at IS NULL", settlementId, player);
    }

    private static long chebyshev(int x1, int z1, int x2, int z2) {
        return Math.max(Math.abs((long) x1 - x2), Math.abs((long) z1 - z2));
    }

    private static final String SELECT = """
            SELECT s.*, p.name AS leader_name FROM settlements s
            JOIN players p ON p.uuid = s.leader_uuid
            """;

    private Settlement map(ResultSet rs) throws SQLException {
        Settlement.Tier tier = Settlement.Tier.valueOf(rs.getString("tier"));
        return new Settlement(rs.getLong("id"), rs.getString("name"), rs.getString("world"), rs.getInt("center_x"),
                rs.getInt("center_z"), tier, Tx.uuid(rs, "leader_uuid"), rs.getString("leader_name"),
                Tx.instant(rs, "founded_at"), config.requirements(tier).radius());
    }
}
