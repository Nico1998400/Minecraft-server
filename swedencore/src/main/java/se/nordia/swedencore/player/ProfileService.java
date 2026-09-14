package se.nordia.swedencore.player;

import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;
import se.nordia.swedencore.economy.Account;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.skills.SkillProgress;
import se.nordia.swedencore.skills.SkillService;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A player's persistent identity and story: where they started, what they built, how they are trusted.
 * Derived entirely from existing records — the foundation for player history (and later news).
 */
public final class ProfileService {

    public record Profile(
            NordiaPlayer player,
            Money balance,
            List<SkillProgress> topSkills,
            List<String> companies,
            String settlement,
            String firstJob,
            String firstJobCompany,
            int companiesFounded,
            int propertiesOwned,
            int contractsCompleted,
            int shopsRun,
            List<String> shareholdings,
            Instant lastSeen
    ) {
    }

    private final Database database;
    private final EconomyService economy;
    private final SkillService skills;

    public ProfileService(Database database, EconomyService economy, SkillService skills) {
        this.database = database;
        this.economy = economy;
        this.skills = skills;
    }

    public Profile profile(UUID uuid) {
        return database.inTransaction(tx -> {
            NordiaPlayer player = tx.queryOne("SELECT * FROM players WHERE uuid = ?", PlayerService::map, uuid)
                    .orElseThrow(() -> new DomainException("player.unknown"));
            Money balance = economy.findAccount(tx, AccountOwner.player(uuid), Account.MAIN).map(Account::balance).orElse(Money.ZERO);
            List<SkillProgress> top = skills.load(tx, uuid).values().stream()
                    .filter(p -> p.xp() > 0)
                    .sorted(Comparator.comparingLong(SkillProgress::xp).reversed())
                    .limit(3)
                    .toList();
            List<String> companies = tx.queryList("""
                    SELECT c.name FROM company_employees e JOIN companies c ON c.id = e.company_id
                    WHERE e.player_uuid = ? AND e.ended_at IS NULL AND c.status = 'ACTIVE' ORDER BY e.hired_at""",
                    rs -> rs.getString(1), uuid);
            String settlement = tx.queryOne("""
                    SELECT s.name FROM settlement_members m JOIN settlements s ON s.id = m.settlement_id
                    WHERE m.player_uuid = ? AND m.left_at IS NULL AND s.status = 'ACTIVE'""", rs -> rs.getString(1), uuid).orElse(null);
            record FirstJob(String title, String company) {
            }
            FirstJob first = tx.queryOne("""
                    SELECT p.title, c.name FROM company_employees e
                    JOIN job_positions p ON p.id = e.position_id JOIN companies c ON c.id = e.company_id
                    WHERE e.player_uuid = ? ORDER BY e.hired_at, e.id LIMIT 1""",
                    rs -> new FirstJob(rs.getString(1), rs.getString(2)), uuid).orElse(null);
            int founded = count(tx, "SELECT count(*) FROM companies WHERE owner_uuid = ?", uuid);
            int properties = count(tx, """
                    SELECT count(*) FROM properties WHERE (owner_type = 'PLAYER' AND owner_id = ?::text)
                       OR (owner_type = 'COMPANY' AND owner_id IN (SELECT id::text FROM companies WHERE owner_uuid = ? AND status = 'ACTIVE'))""",
                    uuid, uuid);
            int contracts = count(tx, "SELECT count(*) FROM contracts WHERE contractor_uuid = ? AND status = 'COMPLETED'", uuid);
            int shops = count(tx, """
                    SELECT count(*) FROM shops s JOIN properties p ON p.id = s.property_id
                    WHERE s.status = 'OPEN' AND ((p.owner_type = 'PLAYER' AND p.owner_id = ?::text)
                       OR (p.owner_type = 'COMPANY' AND p.owner_id IN (SELECT id::text FROM companies WHERE owner_uuid = ? AND status = 'ACTIVE')))""",
                    uuid, uuid);
            // Share registers are public (see /shares info): list companies the player holds shares in, largest first.
            List<String> holdings = tx.queryList("""
                    SELECT c.name FROM (SELECT company_id, SUM(q) AS q FROM (
                            SELECT company_id, quantity AS q FROM share_holdings WHERE holder_type = 'PLAYER' AND holder_id = ?::text
                            UNION ALL
                            SELECT company_id, remaining FROM share_offers WHERE status = 'OPEN' AND seller_type = 'PLAYER' AND seller_id = ?::text) u
                        GROUP BY company_id) h
                    JOIN companies c ON c.id = h.company_id
                    WHERE c.status = 'ACTIVE' ORDER BY h.q DESC, c.name LIMIT 5""", rs -> rs.getString(1), uuid, uuid);
            return new Profile(player, balance, top, companies, settlement,
                    first == null ? null : first.title(), first == null ? null : first.company(),
                    founded, properties, contracts, shops, holdings, player.lastSeen());
        });
    }

    private static int count(Tx tx, String sql, Object... params) throws java.sql.SQLException {
        return (int) tx.queryLong(sql, params);
    }
}
