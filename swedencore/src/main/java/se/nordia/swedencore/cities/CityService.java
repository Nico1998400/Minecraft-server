package se.nordia.swedencore.cities;

import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;
import se.nordia.swedencore.economy.Account;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.economy.Money;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Predefined cities (Stockholm, Göteborg, Helsingborg …) created by administrators. Each city has a treasury account
 * that receives the proceeds of selling city-owned property — the first step towards city budgets and taxes (P5).
 */
public final class CityService {

    private static final Pattern NAME = Pattern.compile("[A-Za-zÅÄÖåäöÉéÜü][A-Za-zÅÄÖåäöÉéÜü \\-]{1,31}");

    public record Stats(City city, Money treasury, int properties, int ownedProperties, int residents, int businesses) {
    }

    private final Database database;
    private final EconomyService economy;

    public CityService(Database database, EconomyService economy) {
        this.database = database;
        this.economy = economy;
    }

    /** Lets other modules (settlements) veto city placement. */
    @FunctionalInterface
    public interface PlacementCheck {
        void verify(Tx tx, String world, int centerX, int centerZ, int radius) throws SQLException;
    }

    private final List<PlacementCheck> placementChecks = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addPlacementCheck(PlacementCheck check) {
        placementChecks.add(check);
    }

    public City create(String rawName, String world, int centerX, int centerZ, int radius) {
        String name = rawName == null ? "" : rawName.trim();
        if (!NAME.matcher(name).matches()) {
            throw new DomainException("city.invalid_name");
        }
        if (radius < 16 || radius > 10_000) {
            throw new DomainException("city.invalid_radius");
        }
        return database.inTransaction(tx -> {
            tx.queryOne("SELECT pg_advisory_xact_lock(hashtext('cities'))", rs -> true);
            if (tx.queryOne("SELECT 1 FROM cities WHERE lower(name) = lower(?)", rs -> true, name).isPresent()) {
                throw new DomainException("city.name_taken");
            }
            City candidate = new City(0, name, world, centerX, centerZ, radius, null);
            for (City other : all(tx)) {
                if (other.area().intersects(candidate.area())) {
                    throw DomainException.of("city.overlaps", "city", other.name());
                }
            }
            for (PlacementCheck check : placementChecks) {
                check.verify(tx, world, centerX, centerZ, radius);
            }
            long id = tx.queryLong("INSERT INTO cities (name, world, center_x, center_z, radius) VALUES (?, ?, ?, ?, ?) RETURNING id",
                    name, world, centerX, centerZ, radius);
            economy.getOrCreateAccount(tx, AccountOwner.city(id), Account.MAIN);
            return find(tx, id).orElseThrow();
        });
    }

    public List<City> all() {
        return database.inTransaction(this::all);
    }

    public List<City> all(Tx tx) throws SQLException {
        return tx.queryList("SELECT * FROM cities ORDER BY id", CityService::map);
    }

    public Optional<City> find(Tx tx, long id) throws SQLException {
        return tx.queryOne("SELECT * FROM cities WHERE id = ?", CityService::map, id);
    }

    public City requireByName(String name) {
        return database.inTransaction(tx -> tx.queryOne("SELECT * FROM cities WHERE lower(name) = lower(?)", CityService::map, name == null ? "" : name.trim()))
                .orElseThrow(() -> new DomainException("city.not_found"));
    }

    public Optional<City> cityAt(Tx tx, String world, int x, int z) throws SQLException {
        return all(tx).stream().filter(c -> c.contains(world, x, z)).findFirst();
    }

    public Stats stats(long cityId) {
        return database.inTransaction(tx -> {
            City city = find(tx, cityId).orElseThrow(() -> new DomainException("city.not_found"));
            Money treasury = economy.findAccount(tx, AccountOwner.city(cityId), Account.MAIN).map(Account::balance).orElse(Money.ZERO);
            int total = (int) tx.queryLong("SELECT count(*) FROM properties WHERE city_id = ?", cityId);
            int owned = (int) tx.queryLong("SELECT count(*) FROM properties WHERE city_id = ? AND status <> 'AVAILABLE'", cityId);
            int residents = (int) tx.queryLong("""
                    SELECT count(DISTINCT owner_id) FROM properties
                    WHERE city_id = ? AND owner_type = 'PLAYER' AND type IN ('APARTMENT', 'HOUSE')""", cityId);
            int businesses = (int) tx.queryLong("SELECT count(DISTINCT owner_id) FROM properties WHERE city_id = ? AND owner_type = 'COMPANY'", cityId);
            return new Stats(city, treasury, total, owned, residents, businesses);
        });
    }

    static City map(ResultSet rs) throws SQLException {
        return new City(rs.getLong("id"), rs.getString("name"), rs.getString("world"), rs.getInt("center_x"),
                rs.getInt("center_z"), rs.getInt("radius"), Tx.instant(rs, "founded_at"));
    }
}
