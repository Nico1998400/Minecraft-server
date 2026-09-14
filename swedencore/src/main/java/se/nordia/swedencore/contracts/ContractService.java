package se.nordia.swedencore.contracts;

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
import se.nordia.swedencore.reputation.ReputationService;
import se.nordia.swedencore.skills.Skill;
import se.nordia.swedencore.skills.SkillService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Contracts connect players and companies: "deliver 5 000 iron ore for 80 000 SEK", "build a house for 250 000 SEK".
 *
 * <p>Trust model:
 * <ul>
 *   <li>The full reward is moved into a per-contract <b>escrow</b> account at creation — issuers can never fail to pay.</li>
 *   <li>Item deliveries pay proportionally from escrow as goods arrive; goods go to the issuer's stash.</li>
 *   <li>Service contracts are confirmed by the issuer (only the owner for company contracts).</li>
 *   <li>Open contracts can be cancelled (refund); in-progress contracts can be abandoned by the contractor
 *       (reputation penalty) or expire at the deadline (remaining escrow refunded).</li>
 * </ul>
 *
 * <p>State transitions lock the contract row first, then reputation subjects, then accounts (see DATABASE.md).
 */
public final class ContractService {

    private static final Pattern TITLE = Pattern.compile("[\\p{IsLatin}\\p{N} &.,:'()/+!?%#_\\-]{3,60}");

    public record CreateRequest(Contract.Type type, String title, String material, Integer quantity, Money reward,
                                int durationHours, Skill requiredSkill, int requiredLevel) {
    }

    public record DeliveryResult(Contract contract, int accepted, Money payout, boolean completed) {
    }

    private final Database database;
    private final EconomyService economy;
    private final CompanyService companies;
    private final SkillService skills;
    private final ReputationService reputation;
    private final ItemStashService stash;
    private final ContractConfig config;
    private final Clock clock;

    public ContractService(Database database, EconomyService economy, CompanyService companies, SkillService skills,
                           ReputationService reputation, ItemStashService stash, ContractConfig config, Clock clock) {
        this.database = database;
        this.economy = economy;
        this.companies = companies;
        this.skills = skills;
        this.reputation = reputation;
        this.stash = stash;
        this.config = config;
        this.clock = clock;
        companies.addDissolutionCheck((tx, companyId) -> {
            long active = tx.queryLong("SELECT count(*) FROM contracts WHERE issuer_company_id = ? AND status IN ('OPEN', 'IN_PROGRESS')", companyId);
            if (active > 0) {
                throw new DomainException("company.dissolve_has_contracts");
            }
        });
    }

    public ContractConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ creation

    /**
     * Creates a contract and escrows the reward. With {@code companyId} the company is the issuer and pays; only its
     * owner may commit company funds to contracts.
     */
    public Contract create(UUID actor, Long companyId, CreateRequest request) {
        String title = request.title() == null ? "" : request.title().trim();
        if (!TITLE.matcher(title).matches()) {
            throw new DomainException("contract.invalid_title");
        }
        if (request.type() == Contract.Type.ITEM_DELIVERY) {
            if (request.material() == null || !ItemStashService.MATERIAL.matcher(request.material()).matches()) {
                throw new DomainException("contract.invalid_material");
            }
            if (request.quantity() == null || request.quantity() < 1 || request.quantity() > 1_000_000) {
                throw new DomainException("contract.invalid_quantity");
            }
        }
        if (!request.reward().isPositive()) {
            throw new DomainException("economy.invalid_amount");
        }
        if (request.durationHours() < 1 || request.durationHours() > config.maxDurationHours()) {
            throw DomainException.of("contract.invalid_duration", "max", config.maxDurationHours());
        }
        int maxLevel = skills.curve().maxLevel();
        if (request.requiredLevel() < 1 || request.requiredLevel() > maxLevel
                || (request.requiredSkill() == null && request.requiredLevel() != 1)) {
            throw DomainException.of("job.invalid_level", "max", maxLevel);
        }
        long xp = request.requiredSkill() == null ? 0
                : Math.min(config.maxXpPerContract(), request.reward().ore() / Money.ORE_PER_SEK / config.sekPerXp());
        Money fee = Money.ofOre(Math.multiplyExact(request.reward().ore(), config.feePercent()) / 100);

        return database.inTransaction(tx -> {
            Account payer;
            if (companyId != null) {
                companies.lockActive(tx, companyId);
                companies.requireRole(tx, companyId, actor, CompanyRole.OWNER);
                payer = economy.requireAccount(tx, AccountOwner.company(companyId));
                long active = tx.queryLong("SELECT count(*) FROM contracts WHERE issuer_company_id = ? AND status IN ('OPEN', 'IN_PROGRESS')", companyId);
                if (active >= config.maxActivePerIssuer()) {
                    throw DomainException.of("contract.too_many_active", "max", config.maxActivePerIssuer());
                }
            } else {
                tx.queryOne("SELECT uuid FROM players WHERE uuid = ? FOR UPDATE", rs -> true, actor)
                        .orElseThrow(() -> new DomainException("player.unknown"));
                payer = economy.requireAccount(tx, AccountOwner.player(actor));
                long active = tx.queryLong("SELECT count(*) FROM contracts WHERE issuer_player_uuid = ? AND status IN ('OPEN', 'IN_PROGRESS')", actor);
                if (active >= config.maxActivePerIssuer()) {
                    throw DomainException.of("contract.too_many_active", "max", config.maxActivePerIssuer());
                }
            }
            Instant deadline = clock.instant().plus(Duration.ofHours(request.durationHours()));
            long id = tx.queryLong("""
                            INSERT INTO contracts (issuer_type, issuer_player_uuid, issuer_company_id, created_by, type, title,
                                                   material, quantity, reward, required_skill, required_level, xp_reward, deadline_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    companyId != null ? "COMPANY" : "PLAYER", companyId != null ? null : actor, companyId, actor,
                    request.type(), title,
                    request.type() == Contract.Type.ITEM_DELIVERY ? request.material() : null,
                    request.type() == Contract.Type.ITEM_DELIVERY ? request.quantity() : null,
                    request.reward().ore(), request.requiredSkill(), request.requiredLevel(), xp, deadline);
            Account escrow = economy.getOrCreateAccount(tx, AccountOwner.contract(id), Account.ESCROW);
            economy.transfer(tx, new TransferRequest(payer.id(), escrow.id(), request.reward(), TransactionType.CONTRACT_ESCROW,
                    "contract-escrow:" + id, actor, "CONTRACT", Long.toString(id), null));
            if (fee.isPositive()) {
                economy.burn(tx, payer.id(), fee, TransactionType.CONTRACT_FEE, "contract-fee:" + id, actor);
            }
            return find(tx, id).orElseThrow();
        });
    }

    // ------------------------------------------------------------------ lifecycle

    public Contract accept(UUID contractor, long contractId) {
        return database.inTransaction(tx -> {
            Contract contract = lock(tx, contractId);
            if (contract.status() != Contract.Status.OPEN) {
                throw new DomainException("contract.not_open");
            }
            requireBeforeDeadline(contract);
            if (isIssuerSide(tx, contract, contractor)) {
                throw new DomainException("contract.own_contract");
            }
            if (contract.requiredSkill() != null && contract.requiredLevel() > 1) {
                int level = skills.level(tx, contractor, contract.requiredSkill());
                if (level < contract.requiredLevel()) {
                    throw DomainException.of("job.skill_too_low", "level", contract.requiredLevel(), "current", level,
                            "skill", contract.requiredSkill());
                }
            }
            tx.queryOne("SELECT uuid FROM players WHERE uuid = ? FOR UPDATE", rs -> true, contractor)
                    .orElseThrow(() -> new DomainException("player.unknown"));
            long active = tx.queryLong("SELECT count(*) FROM contracts WHERE contractor_uuid = ? AND status = 'IN_PROGRESS'", contractor);
            if (active >= config.maxActivePerContractor()) {
                throw DomainException.of("contract.too_many_taken", "max", config.maxActivePerContractor());
            }
            tx.update("UPDATE contracts SET status = 'IN_PROGRESS', contractor_uuid = ?, accepted_at = now() WHERE id = ?",
                    contractor, contractId);
            return find(tx, contractId).orElseThrow();
        });
    }

    /**
     * Records delivered items. The Paper layer has already removed exactly these items from the contractor's
     * inventory; on any exception it must give them back.
     */
    public DeliveryResult deliver(UUID contractor, long contractId, List<ItemStashService.StashItem> items) {
        return deliver(contractor, contractId, items, UUID.randomUUID());
    }

    /** True if a delivery with this token was committed. Used to resolve ambiguous failures before returning items. */
    public boolean deliveryRecorded(UUID token) {
        return database.inTransaction(tx -> tx.queryOne("SELECT 1 FROM contract_deliveries WHERE token = ?", rs -> true, token)).isPresent();
    }

    /**
     * @param token unique per delivery attempt, generated by the caller before removing items from the inventory
     */
    public DeliveryResult deliver(UUID contractor, long contractId, List<ItemStashService.StashItem> items, UUID token) {
        if (items == null || items.isEmpty()) {
            throw new DomainException("contract.nothing_to_deliver");
        }
        int quantity = 0;
        for (ItemStashService.StashItem item : items) {
            quantity = Math.addExact(quantity, item.amount());
        }
        final int delivered = quantity;
        return database.inTransaction(tx -> {
            Contract contract = lock(tx, contractId);
            if (contract.type() != Contract.Type.ITEM_DELIVERY) {
                throw new DomainException("contract.wrong_type");
            }
            if (contract.status() != Contract.Status.IN_PROGRESS || !contractor.equals(contract.contractor())) {
                throw new DomainException("contract.not_contractor");
            }
            requireBeforeDeadline(contract);
            for (ItemStashService.StashItem item : items) {
                if (!item.material().equals(contract.material())) {
                    throw new DomainException("contract.wrong_material");
                }
            }
            if (delivered > contract.remaining()) {
                throw DomainException.of("contract.too_many_items", "remaining", contract.remaining());
            }
            int newDelivered = contract.delivered() + delivered;
            boolean completed = newDelivered == contract.quantity();
            long targetPaid = completed ? contract.reward().ore()
                    : Math.multiplyExact(contract.reward().ore(), (long) newDelivered) / contract.quantity();
            Money payout = Money.ofOre(targetPaid - contract.paidOut().ore());

            long deliveryId = tx.queryLong("INSERT INTO contract_deliveries (contract_id, contractor_uuid, token, quantity, payout) VALUES (?, ?, ?, ?, ?) RETURNING id",
                    contractId, contractor, token, delivered, payout.ore());
            if (completed) {
                rewardCompletion(tx, contract, contractor);
            }
            if (payout.isPositive()) {
                TransferReceipt receipt = payFromEscrow(tx, contract, AccountOwner.player(contractor), payout,
                        TransactionType.CONTRACT_PAYOUT, "contract-delivery:" + deliveryId);
                tx.update("UPDATE contract_deliveries SET transaction_id = ? WHERE id = ?", receipt.transactionId(), deliveryId);
            }
            stash.deposit(tx, issuerStashOwner(contract), items, "CONTRACT", Long.toString(contractId));
            tx.update("""
                            UPDATE contracts SET delivered = ?, paid_out = ?,
                                   status = CASE WHEN ? THEN 'COMPLETED' ELSE status END,
                                   closed_at = CASE WHEN ? THEN now() ELSE closed_at END
                            WHERE id = ?""",
                    newDelivered, targetPaid, completed, completed, contractId);
            return new DeliveryResult(find(tx, contractId).orElseThrow(), delivered, payout, completed);
        });
    }

    /**
     * Sets or clears the skill requirement of an OPEN contract. Contract XP is always derived from the reward, never
     * chosen by the issuer, so this cannot be used to mint XP.
     */
    public Contract setSkillRequirement(UUID actor, long contractId, Skill skill, int level) {
        int maxLevel = skills.curve().maxLevel();
        if (level < 1 || level > maxLevel || (skill == null && level != 1)) {
            throw DomainException.of("job.invalid_level", "max", maxLevel);
        }
        return database.inTransaction(tx -> {
            Contract contract = lock(tx, contractId);
            if (contract.status() != Contract.Status.OPEN) {
                throw new DomainException("contract.not_open");
            }
            requireIssuer(tx, contract, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            long xp = skill == null ? 0 : Math.min(config.maxXpPerContract(), contract.reward().ore() / Money.ORE_PER_SEK / config.sekPerXp());
            tx.update("UPDATE contracts SET required_skill = ?, required_level = ?, xp_reward = ? WHERE id = ?", skill, level, xp, contractId);
            return find(tx, contractId).orElseThrow();
        });
    }

    /** Issuer confirms a service contract; the remaining escrow is paid to the contractor. */
    public Contract complete(UUID actor, long contractId) {
        return database.inTransaction(tx -> {
            Contract contract = lock(tx, contractId);
            if (contract.type() != Contract.Type.SERVICE) {
                throw new DomainException("contract.wrong_type");
            }
            if (contract.status() != Contract.Status.IN_PROGRESS) {
                throw new DomainException("contract.not_in_progress");
            }
            requireIssuer(tx, contract, actor, CompanyRole.OWNER);
            rewardCompletion(tx, contract, contract.contractor());
            Money remaining = contract.escrowRemaining();
            if (remaining.isPositive()) {
                payFromEscrow(tx, contract, AccountOwner.player(contract.contractor()), remaining,
                        TransactionType.CONTRACT_PAYOUT, "contract-complete:" + contractId);
            }
            tx.update("UPDATE contracts SET paid_out = reward, status = 'COMPLETED', closed_at = now() WHERE id = ?", contractId);
            return find(tx, contractId).orElseThrow();
        });
    }

    /** Issuer cancels an OPEN contract and gets the escrow back. The fee is not refunded. */
    public Contract cancel(UUID actor, long contractId) {
        return database.inTransaction(tx -> {
            Contract contract = lock(tx, contractId);
            if (contract.status() != Contract.Status.OPEN) {
                throw new DomainException("contract.cannot_cancel");
            }
            requireIssuer(tx, contract, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            refund(tx, contract);
            tx.update("UPDATE contracts SET status = 'CANCELLED', closed_at = now() WHERE id = ?", contractId);
            return find(tx, contractId).orElseThrow();
        });
    }

    /** Contractor gives up: the contract reopens (partial deliveries stay paid) and the contractor loses reputation. */
    public Contract abandon(UUID contractor, long contractId) {
        return database.inTransaction(tx -> {
            Contract contract = lock(tx, contractId);
            if (contract.status() != Contract.Status.IN_PROGRESS || !contractor.equals(contract.contractor())) {
                throw new DomainException("contract.not_contractor");
            }
            Instant acceptedAt = tx.queryOne("SELECT accepted_at FROM contracts WHERE id = ?", rs -> Tx.instant(rs, "accepted_at"), contractId)
                    .orElseThrow();
            reputation.adjust(tx, ReputationService.Subject.player(contractor), config.abandonReputation(), "CONTRACT_ABANDONED",
                    "CONTRACT", Long.toString(contractId), "contract-abandon:" + contractId + ":" + acceptedAt.toEpochMilli());
            tx.update("UPDATE contracts SET status = 'OPEN', contractor_uuid = NULL, accepted_at = NULL WHERE id = ?", contractId);
            return find(tx, contractId).orElseThrow();
        });
    }

    /** Bankruptcy: cancels all active contracts issued by the company and refunds their escrow to the company. */
    public int closeAllForCompany(Tx tx, long companyId) throws SQLException {
        List<Long> ids = tx.queryList("SELECT id FROM contracts WHERE issuer_company_id = ? AND status IN ('OPEN', 'IN_PROGRESS') ORDER BY id",
                rs -> rs.getLong(1), companyId);
        for (long id : ids) {
            Contract contract = lock(tx, id);
            refund(tx, contract);
            tx.update("UPDATE contracts SET status = 'CANCELLED', closed_at = now() WHERE id = ?", id);
        }
        return ids.size();
    }

    /** Expires active contracts past their deadline. Returns the contracts that expired (for notifications). */
    public List<Contract> expireDue() {
        List<Long> due = database.inTransaction(tx -> tx.queryList(
                "SELECT id FROM contracts WHERE status IN ('OPEN', 'IN_PROGRESS') AND deadline_at <= ? ORDER BY deadline_at LIMIT 100",
                rs -> rs.getLong(1), clock.instant()));
        List<Contract> expired = new ArrayList<>();
        for (long id : due) {
            // TxWork may be retried, so the result is collected outside the transaction lambda.
            Optional<Contract> result = database.inTransaction(tx -> {
                Contract contract = lock(tx, id);
                if (!contract.status().active() || contract.deadline().isAfter(clock.instant())) {
                    return Optional.<Contract>empty();
                }
                if (contract.status() == Contract.Status.IN_PROGRESS) {
                    reputation.adjust(tx, ReputationService.Subject.player(contract.contractor()), config.expiryReputation(),
                            "CONTRACT_EXPIRED", "CONTRACT", Long.toString(id), "contract-expire:" + id);
                }
                refund(tx, contract);
                tx.update("UPDATE contracts SET status = 'EXPIRED', closed_at = now() WHERE id = ?", id);
                return find(tx, id);
            });
            result.ifPresent(expired::add);
        }
        return expired;
    }

    // ------------------------------------------------------------------ queries

    public Optional<Contract> find(long id) {
        return database.inTransaction(tx -> find(tx, id));
    }

    public Optional<Contract> find(Tx tx, long id) throws SQLException {
        return tx.queryOne(SELECT + " WHERE k.id = ?", ContractService::map, id);
    }

    public List<Contract> openContracts(int limit, int offset) {
        return database.inTransaction(tx -> tx.queryList(SELECT + " WHERE k.status = 'OPEN' AND k.deadline_at > ? ORDER BY k.reward DESC, k.id LIMIT ? OFFSET ?",
                ContractService::map, clock.instant(), Math.clamp(limit, 1, 50), Math.max(0, offset)));
    }

    /** Active contracts the player takes part in: issued personally, issued by companies they own/manage, or taken. */
    public List<Contract> involving(UUID player) {
        return database.inTransaction(tx -> tx.queryList(SELECT + """
                         WHERE k.status IN ('OPEN', 'IN_PROGRESS')
                           AND (k.issuer_player_uuid = ? OR k.contractor_uuid = ?
                                OR k.issuer_company_id IN (SELECT company_id FROM company_employees
                                                           WHERE player_uuid = ? AND ended_at IS NULL AND role IN ('OWNER', 'MANAGER')))
                         ORDER BY k.deadline_at""",
                ContractService::map, player, player, player));
    }

    // ------------------------------------------------------------------ helpers

    private Contract lock(Tx tx, long id) throws SQLException {
        tx.queryOne("SELECT id FROM contracts WHERE id = ? FOR UPDATE", rs -> true, id)
                .orElseThrow(() -> new DomainException("contract.not_found"));
        return find(tx, id).orElseThrow();
    }

    private void requireBeforeDeadline(Contract contract) {
        if (!clock.instant().isBefore(contract.deadline())) {
            throw new DomainException("contract.expired");
        }
    }

    private boolean isIssuerSide(Tx tx, Contract contract, UUID player) throws SQLException {
        if (contract.issuerType() == Contract.IssuerType.PLAYER) {
            return player.equals(contract.issuerPlayer());
        }
        return companies.roleOf(tx, contract.issuerCompanyId(), player)
                .map(role -> role == CompanyRole.OWNER || role == CompanyRole.MANAGER)
                .orElse(false);
    }

    private void requireIssuer(Tx tx, Contract contract, UUID actor, CompanyRole... companyRoles) throws SQLException {
        if (contract.issuerType() == Contract.IssuerType.PLAYER) {
            if (!actor.equals(contract.issuerPlayer())) {
                throw new DomainException("contract.not_issuer");
            }
            return;
        }
        if (companies.roleOf(tx, contract.issuerCompanyId(), actor).isEmpty()) {
            throw new DomainException("contract.not_issuer");
        }
        companies.requireRole(tx, contract.issuerCompanyId(), actor, companyRoles);
    }

    private void rewardCompletion(Tx tx, Contract contract, UUID contractor) throws SQLException {
        if (!contract.reward().isLessThan(config.minRewardForReputation())) {
            String day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
            String issuerKey = contract.issuerType() == Contract.IssuerType.PLAYER
                    ? "P" + contract.issuerPlayer() : "C" + contract.issuerCompanyId();
            reputation.adjust(tx, ReputationService.Subject.player(contractor), config.contractorCompletionReputation(),
                    "CONTRACT_COMPLETED", "CONTRACT", Long.toString(contract.id()),
                    "contract-rep:" + contractor + ":" + issuerKey + ":" + day);
            ReputationService.Subject issuer = contract.issuerType() == Contract.IssuerType.PLAYER
                    ? ReputationService.Subject.player(contract.issuerPlayer())
                    : ReputationService.Subject.company(contract.issuerCompanyId());
            reputation.adjust(tx, issuer, config.issuerCompletionReputation(), "CONTRACT_ISSUED_COMPLETED",
                    "CONTRACT", Long.toString(contract.id()), "contract-rep-issuer:" + issuerKey + ":" + contractor + ":" + day);
        }
        if (contract.requiredSkill() != null && contract.xpReward() > 0) {
            skills.addXp(tx, contractor, contract.requiredSkill(), contract.xpReward());
        }
    }

    private TransferReceipt payFromEscrow(Tx tx, Contract contract, AccountOwner to, Money amount, TransactionType type, String key)
            throws SQLException {
        Account escrow = economy.findAccount(tx, AccountOwner.contract(contract.id()), Account.ESCROW).orElseThrow();
        Account target = economy.requireAccount(tx, to);
        return economy.transfer(tx, new TransferRequest(escrow.id(), target.id(), amount, type, key, null,
                "CONTRACT", Long.toString(contract.id()), null));
    }

    private void refund(Tx tx, Contract contract) throws SQLException {
        Money remaining = contract.escrowRemaining();
        if (!remaining.isPositive()) {
            return;
        }
        AccountOwner issuer = contract.issuerType() == Contract.IssuerType.PLAYER
                ? AccountOwner.player(contract.issuerPlayer()) : AccountOwner.company(contract.issuerCompanyId());
        payFromEscrow(tx, contract, issuer, remaining, TransactionType.CONTRACT_REFUND, "contract-refund:" + contract.id());
    }

    private static ItemStashService.Owner issuerStashOwner(Contract contract) {
        return contract.issuerType() == Contract.IssuerType.PLAYER
                ? ItemStashService.Owner.player(contract.issuerPlayer())
                : ItemStashService.Owner.company(contract.issuerCompanyId());
    }

    private static final String SELECT = """
            SELECT k.*, COALESCE(c.name, ip.name) AS issuer_name, cp.name AS contractor_name
            FROM contracts k
            LEFT JOIN companies c ON c.id = k.issuer_company_id
            LEFT JOIN players ip ON ip.uuid = k.issuer_player_uuid
            LEFT JOIN players cp ON cp.uuid = k.contractor_uuid
            """;

    private static Contract map(ResultSet rs) throws SQLException {
        String skill = rs.getString("required_skill");
        return new Contract(rs.getLong("id"), Contract.IssuerType.valueOf(rs.getString("issuer_type")),
                Tx.uuid(rs, "issuer_player_uuid"), Tx.nullableLong(rs, "issuer_company_id"), rs.getString("issuer_name"),
                Contract.Type.valueOf(rs.getString("type")), rs.getString("title"), rs.getString("material"),
                Tx.nullableInt(rs, "quantity"), rs.getInt("delivered"), Money.ofOre(rs.getLong("reward")),
                Money.ofOre(rs.getLong("paid_out")), skill == null ? null : Skill.valueOf(skill), rs.getInt("required_level"),
                rs.getLong("xp_reward"), Contract.Status.valueOf(rs.getString("status")), Tx.uuid(rs, "contractor_uuid"),
                rs.getString("contractor_name"), Tx.instant(rs, "created_at"), Tx.instant(rs, "deadline_at"));
    }
}
