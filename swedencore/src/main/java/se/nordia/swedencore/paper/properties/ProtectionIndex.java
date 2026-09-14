package se.nordia.swedencore.paper.properties;

import se.nordia.swedencore.cities.City;
import se.nordia.swedencore.core.NordiaCore;
import se.nordia.swedencore.events.DomainEvent;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.PropertyService;
import se.nordia.swedencore.properties.RegionIndex;
import se.nordia.swedencore.settlements.Settlement;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * In-memory view of property regions, city areas and settlement areas used for block protection.
 *
 * <p>Loaded asynchronously at startup and refreshed from {@link DomainEvent}s. Mutations happen on the main thread.
 * Until the initial load finishes, building inside cities is denied (fail closed) — the load takes milliseconds.
 *
 * <p>Precedence: property → settlement land (members only) → city land (public, nobody) → wilderness (free).
 */
public final class ProtectionIndex {

    /** Area keys: properties are positive ids, cities {@code -id}, settlements below {@link #SETTLEMENT_KEY_BASE}. */
    private static final long SETTLEMENT_KEY_BASE = -1_000_000_000_000L;

    public record SettlementArea(Settlement settlement, Set<UUID> members) {
    }

    private final NordiaCore core;
    private final Tasks tasks;
    private final RegionIndex<PropertyService.Access> properties =
            new RegionIndex<>(a -> a.property().region(), a -> a.property().id());
    private volatile List<City> cities = List.of();
    private volatile List<SettlementArea> settlements = List.of();
    private volatile boolean ready;

    public ProtectionIndex(NordiaCore core, Tasks tasks) {
        this.core = core;
        this.tasks = tasks;
        core.events().subscribe(this::onEvent);
    }

    public boolean ready() {
        return ready;
    }

    public void reloadAll() {
        tasks.async(() -> new Snapshot(core.properties().accessList(), core.cities().all(), loadSettlements()))
                .whenComplete((snapshot, error) -> {
                    if (error != null) {
                        core.logger().log(Level.SEVERE, "Failed to load protection index", Tasks.unwrap(error));
                        return;
                    }
                    tasks.sync(() -> {
                        properties.clear();
                        snapshot.access().forEach(properties::put);
                        cities = snapshot.cities();
                        settlements = snapshot.settlements();
                        ready = true;
                        core.logger().info("Protection index loaded: " + properties.size() + " properties, "
                                + cities.size() + " cities, " + settlements.size() + " settlements");
                    });
                });
    }

    private List<SettlementArea> loadSettlements() {
        return core.settlements().allActive().stream()
                .map(s -> new SettlementArea(s, new HashSet<>(core.settlements().memberIds(s.id()))))
                .toList();
    }

    private record Snapshot(List<PropertyService.Access> access, List<City> cities, List<SettlementArea> settlements) {
    }

    private void onEvent(DomainEvent event) {
        switch (event) {
            case DomainEvent.PropertyChanged changed -> refreshProperty(changed.propertyId());
            case DomainEvent.CompanyMembershipChanged membership -> refreshCompany(membership.companyId());
            case DomainEvent.SettlementChanged ignored -> reloadSettlements();
            default -> {
            }
        }
    }

    private void refreshProperty(long propertyId) {
        tasks.async(() -> core.properties().access(propertyId)).whenComplete((access, error) -> {
            if (error != null) {
                core.logger().log(Level.WARNING, "Failed to refresh property " + propertyId, Tasks.unwrap(error));
                return;
            }
            tasks.sync(() -> {
                if (access.isPresent()) {
                    properties.put(access.get());
                } else {
                    properties.remove(propertyId);
                }
            });
        });
    }

    private void refreshCompany(long companyId) {
        tasks.async(() -> core.properties().accessForCompany(companyId)).whenComplete((list, error) -> {
            if (error == null) {
                tasks.sync(() -> list.forEach(properties::put));
            }
        });
    }

    private void reloadSettlements() {
        tasks.async(this::loadSettlements).whenComplete((list, error) -> {
            if (error == null) {
                settlements = list;
            } else {
                core.logger().log(Level.WARNING, "Failed to refresh settlements", Tasks.unwrap(error));
            }
        });
    }

    /** Called after an administrator creates a city (cities are rare; reload them wholesale). */
    public void reloadCities() {
        tasks.async(() -> core.cities().all()).whenComplete((list, error) -> {
            if (error == null) {
                cities = list;
            }
        });
    }

    public Optional<PropertyService.Access> propertyAt(String world, int x, int y, int z) {
        return properties.at(world, x, y, z);
    }

    public Optional<City> cityAt(String world, int x, int z) {
        for (City city : cities) {
            if (city.contains(world, x, z)) {
                return Optional.of(city);
            }
        }
        return Optional.empty();
    }

    public Optional<SettlementArea> settlementAt(String world, int x, int z) {
        for (SettlementArea area : settlements) {
            if (area.settlement().contains(world, x, z)) {
                return Optional.of(area);
            }
        }
        return Optional.empty();
    }

    public enum Decision {
        ALLOWED, DENIED_PROPERTY, DENIED_UNOWNED, DENIED_SETTLEMENT, DENIED_CITY_LAND, DENIED_LOADING
    }

    public Decision canBuild(UUID player, String world, int x, int y, int z, boolean protectCityLand) {
        Optional<PropertyService.Access> access = properties.at(world, x, y, z);
        if (access.isPresent()) {
            if (access.get().property().status() == Property.Status.AVAILABLE) {
                return Decision.DENIED_UNOWNED;
            }
            return access.get().allowed().contains(player) ? Decision.ALLOWED : Decision.DENIED_PROPERTY;
        }
        if (!ready) {
            return Decision.DENIED_LOADING;
        }
        Optional<SettlementArea> settlement = settlementAt(world, x, z);
        if (settlement.isPresent()) {
            return settlement.get().members().contains(player) ? Decision.ALLOWED : Decision.DENIED_SETTLEMENT;
        }
        if (protectCityLand && cityAt(world, x, z).isPresent()) {
            return Decision.DENIED_CITY_LAND;
        }
        return Decision.ALLOWED;
    }

    /** Identity of the protection area at a point (see key scheme above); 0 for wilderness. */
    public long areaKey(String world, int x, int y, int z) {
        Optional<PropertyService.Access> access = properties.at(world, x, y, z);
        if (access.isPresent()) {
            return access.get().property().id();
        }
        Optional<SettlementArea> settlement = settlementAt(world, x, z);
        if (settlement.isPresent()) {
            return SETTLEMENT_KEY_BASE - settlement.get().settlement().id();
        }
        return cityAt(world, x, z).map(c -> -c.id()).orElse(0L);
    }

    public static boolean isCityKey(long key) {
        return key < 0 && key > SETTLEMENT_KEY_BASE;
    }
}
