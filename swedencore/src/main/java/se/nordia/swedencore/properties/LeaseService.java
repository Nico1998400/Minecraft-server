package se.nordia.swedencore.properties;

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
import se.nordia.swedencore.events.DomainEvent;
import se.nordia.swedencore.events.DomainEvents;
import se.nordia.swedencore.reputation.ReputationService;

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
 * Renting property: landlords earn income from assets, tenants get premises without buying them.
 *
 * <p>Rent is paid in advance per period. When a period ends the next one is charged automatically; if the tenant cannot
 * pay, the lease becomes OVERDUE and after a grace period the tenant is evicted (reputation penalty). While leased the
 * tenant is the property's occupant: exclusive build rights, shops and production.
 */
public final class LeaseService {

    public record Config(int minPeriodHours, int maxPeriodHours, int graceHours, int evictionReputation) {
        public static Config defaults() {
            return new Config(24, 720, 24, -5);
        }
    }

    public record Lease(long id, long propertyId, String propertyName, Money rent, int periodHours, Property.OwnerType tenantType,
                        String tenantId, String tenantName, String status, Instant paidUntil, Instant endsAt) {
    }

    /** What happened to a lease during collection (for notifications). */
    public record Outcome(Lease lease, String result) {
    }

    private final Database database;
    private final EconomyService economy;
    private final CompanyService companies;
    private final PropertyService properties;
    private final ReputationService reputation;
    private final DomainEvents events;
    private final Config config;
    private final Clock clock;

    public LeaseService(Database database, EconomyService economy, CompanyService companies, PropertyService properties,
                        ReputationService reputation, DomainEvents events, Config config, Clock clock) {
        this.database = database;
        this.economy = economy;
        this.companies = companies;
        this.properties = properties;
        this.reputation = reputation;
        this.events = events;
        this.config = config;
        this.clock = clock;
        companies.addDissolutionCheck((tx, companyId) -> {
            if (tx.queryLong("SELECT count(*) FROM property_leases WHERE tenant_type = 'COMPANY' AND tenant_id = ? AND status IN ('ACTIVE', 'OVERDUE')",
                    Long.toString(companyId)) > 0) {
                throw new DomainException("company.dissolve_has_leases");
            }
        });
        // A change of owner ends listings and tenancies (the new owner is not bound by the old owner's leases).
        properties.addOwnershipChangeHook((tx, propertyId) -> {
            tx.update("UPDATE property_leases SET status = 'CANCELLED', ended_at = now() WHERE property_id = ? AND status = 'LISTED'", propertyId);
            tx.update("""
                    UPDATE property_leases SET status = 'ENDED', ended_at = now(), end_reason = 'OWNERSHIP_CHANGE'
                    WHERE property_id = ? AND status IN ('ACTIVE', 'OVERDUE')""", propertyId);
        });
    }

    // ------------------------------------------------------------------ landlord

    public Lease listForRent(UUID actor, long propertyId, Money rent, int periodHours) {
        if (!rent.isPositive() || rent.isGreaterThan(economy.config().maxTransferAmount())) {
            throw new DomainException("economy.invalid_amount");
        }
        if (periodHours < config.minPeriodHours() || periodHours > config.maxPeriodHours()) {
            throw DomainException.of("lease.invalid_period", "min", config.minPeriodHours(), "max", config.maxPeriodHours());
        }
        Lease lease = database.inTransaction(tx -> {
            Property property = properties.lockProperty(tx, propertyId);
            properties.requirePropertyOwner(tx, property, actor, CompanyRole.OWNER);
            if (property.status() == Property.Status.FOR_SALE) {
                throw new DomainException("lease.property_for_sale");
            }
            if (tx.queryOne("SELECT 1 FROM property_leases WHERE property_id = ? AND status IN ('LISTED', 'ACTIVE', 'OVERDUE')",
                    rs -> true, propertyId).isPresent()) {
                throw new DomainException("lease.already_listed");
            }
            long id = tx.queryLong("""
                            INSERT INTO property_leases (property_id, rent, period_hours, listed_by, listed_at)
                            VALUES (?, ?, ?, ?, ?) RETURNING id""", propertyId, rent.ore(), periodHours, actor, clock.instant());
            return find(tx, id).orElseThrow();
        });
        events.publish(new DomainEvent.PropertyChanged(propertyId));
        return lease;
    }

    public void cancelListing(UUID actor, long propertyId) {
        database.inTransactionVoid(tx -> {
            Property property = properties.lockProperty(tx, propertyId);
            properties.requirePropertyOwner(tx, property, actor, CompanyRole.OWNER);
            if (tx.update("UPDATE property_leases SET status = 'CANCELLED', ended_at = now() WHERE property_id = ? AND status = 'LISTED'", propertyId) == 0) {
                throw new DomainException("lease.not_listed");
            }
        });
    }

    /** The landlord ends a tenancy at the end of the period already paid for. */
    public Lease endByOwner(UUID actor, long propertyId) {
        return database.inTransaction(tx -> {
            Property property = properties.lockProperty(tx, propertyId);
            properties.requirePropertyOwner(tx, property, actor, CompanyRole.OWNER);
            Lease lease = openLease(tx, propertyId).filter(l -> !l.status().equals("LISTED"))
                    .orElseThrow(() -> new DomainException("lease.not_leased"));
            tx.update("UPDATE property_leases SET ends_at = paid_until WHERE id = ?", lease.id());
            return find(tx, lease.id()).orElseThrow();
        });
    }

    // ------------------------------------------------------------------ tenant

    /**
     * Rents a listed property, paying the first period now.
     *
     * @param companyId rent for this company (actor must be its owner) or null for personal
     * @param expectedRent the rent the tenant saw (protects against a changed listing)
     */
    public Lease rent(UUID actor, long propertyId, Long companyId, Money expectedRent) {
        Lease lease = database.inTransaction(tx -> {
            if (companyId != null) {
                companies.lockActive(tx, companyId);
                companies.requireRole(tx, companyId, actor, CompanyRole.OWNER);
            }
            Property property = properties.lockProperty(tx, propertyId);
            Lease listing = openLease(tx, propertyId).filter(l -> l.status().equals("LISTED"))
                    .orElseThrow(() -> new DomainException("lease.not_listed"));
            if (expectedRent != null && !expectedRent.equals(listing.rent())) {
                throw DomainException.of("lease.rent_changed", "rent", listing.rent());
            }
            Property.OwnerType tenantType = companyId != null ? Property.OwnerType.COMPANY : Property.OwnerType.PLAYER;
            String tenantId = companyId != null ? companyId.toString() : actor.toString();
            if (property.ownerType() == tenantType && property.ownerId().equals(tenantId)) {
                throw new DomainException("lease.own_property");
            }
            Instant now = clock.instant();
            Instant paidUntil = now.plus(Duration.ofHours(listing.periodHours()));
            // The owner's own shop at the premises closes when a tenant moves in.
            properties.runOccupancyHooks(tx, propertyId);
            tx.update("UPDATE property_leases SET status = 'ACTIVE', tenant_type = ?, tenant_id = ?, started_at = ?, paid_until = ? WHERE id = ?",
                    tenantType, tenantId, now, paidUntil, listing.id());
            chargeRent(tx, find(tx, listing.id()).orElseThrow(), property, now, paidUntil, actor);
            return find(tx, listing.id()).orElseThrow();
        });
        events.publish(new DomainEvent.PropertyChanged(propertyId));
        return lease;
    }

    /** The tenant moves out immediately (no refund of the current period). */
    public void endByTenant(UUID actor, long propertyId) {
        database.inTransactionVoid(tx -> {
            Property property = properties.lockProperty(tx, propertyId);
            Lease lease = openLease(tx, propertyId).filter(l -> !l.status().equals("LISTED"))
                    .orElseThrow(() -> new DomainException("lease.not_leased"));
            if (lease.tenantType() == Property.OwnerType.PLAYER) {
                if (!lease.tenantId().equals(actor.toString())) {
                    throw new DomainException("lease.not_tenant");
                }
            } else {
                companies.requireRole(tx, Long.parseLong(lease.tenantId()), actor, CompanyRole.OWNER);
            }
            end(tx, lease, property, "TENANT");
        });
        events.publish(new DomainEvent.PropertyChanged(propertyId));
    }

    // ------------------------------------------------------------------ collection

    public List<Outcome> collectDue() {
        List<Long> ids = database.inTransaction(tx -> tx.queryList(
                "SELECT id FROM property_leases WHERE status IN ('ACTIVE', 'OVERDUE') AND paid_until <= ? ORDER BY paid_until",
                rs -> rs.getLong(1), clock.instant()));
        List<Outcome> outcomes = new ArrayList<>();
        for (long id : ids) {
            Optional<Outcome> outcome = database.inTransaction(tx -> collect(tx, id));
            outcome.ifPresent(o -> {
                outcomes.add(o);
                events.publish(new DomainEvent.PropertyChanged(o.lease().propertyId()));
            });
        }
        return outcomes;
    }

    private Optional<Outcome> collect(Tx tx, long id) throws SQLException {
        long propertyId = tx.queryOne("SELECT property_id FROM property_leases WHERE id = ?", rs -> rs.getLong(1), id).orElseThrow();
        Property property = properties.lockProperty(tx, propertyId);
        Lease lease = tx.queryOne(SELECT + " WHERE le.id = ? FOR UPDATE OF le", LeaseService::map, id).orElseThrow();
        Instant now = clock.instant();
        if (!(lease.status().equals("ACTIVE") || lease.status().equals("OVERDUE")) || lease.paidUntil().isAfter(now)) {
            return Optional.empty();
        }
        if (lease.endsAt() != null && !lease.endsAt().isAfter(now)) {
            end(tx, lease, property, "OWNER");
            return Optional.of(new Outcome(find(tx, id).orElseThrow(), "ENDED"));
        }
        Instant nextEnd = lease.paidUntil().plus(Duration.ofHours(lease.periodHours()));
        try {
            chargeRent(tx, lease, property, lease.paidUntil(), nextEnd, null);
        } catch (DomainException e) {
            if (!e.code().equals("economy.insufficient_funds") && !e.code().equals("economy.account_frozen")) {
                throw e;
            }
            Instant overdueSince = tx.queryOne("SELECT overdue_since FROM property_leases WHERE id = ?",
                    rs -> Tx.instant(rs, "overdue_since"), id).orElse(null);
            if (overdueSince == null) {
                tx.update("UPDATE property_leases SET status = 'OVERDUE', overdue_since = ? WHERE id = ?", now, id);
                return Optional.of(new Outcome(find(tx, id).orElseThrow(), "OVERDUE"));
            }
            if (Duration.between(overdueSince, now).toHours() >= config.graceHours()) {
                ReputationService.Subject subject = lease.tenantType() == Property.OwnerType.PLAYER
                        ? ReputationService.Subject.player(UUID.fromString(lease.tenantId()))
                        : ReputationService.Subject.company(Long.parseLong(lease.tenantId()));
                reputation.adjust(tx, subject, config.evictionReputation(), "EVICTED", "LEASE", Long.toString(id), "lease-evicted:" + id);
                end(tx, lease, property, "EVICTED");
                return Optional.of(new Outcome(find(tx, id).orElseThrow(), "EVICTED"));
            }
            return Optional.empty();
        }
        tx.update("UPDATE property_leases SET status = 'ACTIVE', overdue_since = NULL, paid_until = ? WHERE id = ?", nextEnd, id);
        return Optional.of(new Outcome(find(tx, id).orElseThrow(), "PAID"));
    }

    /** Bankruptcy of a tenant company ends its leases. */
    public List<Long> endAllForTenantCompany(Tx tx, long companyId) throws SQLException {
        List<Long> propertyIds = tx.queryList("""
                        SELECT property_id FROM property_leases WHERE tenant_type = 'COMPANY' AND tenant_id = ? AND status IN ('ACTIVE', 'OVERDUE')
                        ORDER BY property_id""",
                rs -> rs.getLong(1), Long.toString(companyId));
        for (long propertyId : propertyIds) {
            Property property = properties.lockProperty(tx, propertyId);
            Lease lease = openLease(tx, propertyId).orElseThrow();
            end(tx, lease, property, "TENANT_BANKRUPT");
        }
        return propertyIds;
    }

    private void chargeRent(Tx tx, Lease lease, Property property, Instant periodStart, Instant periodEnd, UUID actor) throws SQLException {
        AccountOwner tenant = lease.tenantType() == Property.OwnerType.PLAYER
                ? AccountOwner.player(UUID.fromString(lease.tenantId())) : AccountOwner.company(Long.parseLong(lease.tenantId()));
        AccountOwner landlord = property.ownerType() == Property.OwnerType.PLAYER
                ? AccountOwner.player(UUID.fromString(property.ownerId())) : AccountOwner.company(Long.parseLong(property.ownerId()));
        Account from = economy.requireAccount(tx, tenant);
        Account to = economy.requireAccount(tx, landlord);
        TransferReceipt receipt = economy.transfer(tx, new TransferRequest(from.id(), to.id(), lease.rent(), TransactionType.RENT,
                "rent:" + lease.id() + ":" + periodStart.toEpochMilli(), actor, "LEASE", Long.toString(lease.id()), null));
        tx.update("INSERT INTO lease_payments (lease_id, amount, period_start, period_end, transaction_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                lease.id(), lease.rent().ore(), periodStart, periodEnd, receipt.transactionId(), clock.instant());
    }

    private void end(Tx tx, Lease lease, Property property, String reason) throws SQLException {
        // Occupancy changes: the tenant's shop at the property closes.
        properties.runOccupancyHooks(tx, property.id());
        tx.update("UPDATE property_leases SET status = 'ENDED', ended_at = now(), end_reason = ? WHERE id = ?", reason, lease.id());
    }

    // ------------------------------------------------------------------ queries

    public Optional<Lease> openLease(long propertyId) {
        return database.inTransaction(tx -> openLease(tx, propertyId));
    }

    private Optional<Lease> openLease(Tx tx, long propertyId) throws SQLException {
        return tx.queryOne(SELECT + " WHERE le.property_id = ? AND le.status IN ('LISTED', 'ACTIVE', 'OVERDUE')", LeaseService::map, propertyId);
    }

    private Optional<Lease> find(Tx tx, long id) throws SQLException {
        return tx.queryOne(SELECT + " WHERE le.id = ?", LeaseService::map, id);
    }

    public List<Lease> listings(int limit) {
        return database.inTransaction(tx -> tx.queryList(SELECT + " WHERE le.status = 'LISTED' ORDER BY le.rent, le.id LIMIT ?",
                LeaseService::map, Math.clamp(limit, 1, 50)));
    }

    /** Leases where the player is the tenant, personally or via a company they own. */
    public List<Lease> tenancies(UUID player) {
        return database.inTransaction(tx -> tx.queryList(SELECT + """
                         WHERE le.status IN ('ACTIVE', 'OVERDUE') AND ((le.tenant_type = 'PLAYER' AND le.tenant_id = ?::text)
                            OR (le.tenant_type = 'COMPANY' AND le.tenant_id IN (SELECT id::text FROM companies WHERE owner_uuid = ? AND status = 'ACTIVE')))
                         ORDER BY le.id""", LeaseService::map, player, player));
    }

    private static final String SELECT = """
            SELECT le.*, p.name AS property_name, COALESCE(tp.name, tc.name) AS tenant_name
            FROM property_leases le
            JOIN properties p ON p.id = le.property_id
            LEFT JOIN players tp ON le.tenant_type = 'PLAYER' AND tp.uuid::text = le.tenant_id
            LEFT JOIN companies tc ON le.tenant_type = 'COMPANY' AND tc.id::text = le.tenant_id
            """;

    private static Lease map(ResultSet rs) throws SQLException {
        String tenantType = rs.getString("tenant_type");
        return new Lease(rs.getLong("id"), rs.getLong("property_id"), rs.getString("property_name"), Money.ofOre(rs.getLong("rent")),
                rs.getInt("period_hours"), tenantType == null ? null : Property.OwnerType.valueOf(tenantType), rs.getString("tenant_id"),
                rs.getString("tenant_name"), rs.getString("status"), Tx.instant(rs, "paid_until"), Tx.instant(rs, "ends_at"));
    }
}
