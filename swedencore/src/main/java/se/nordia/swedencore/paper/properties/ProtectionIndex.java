package se.nordia.swedencore.paper.properties;

import se.nordia.swedencore.cities.City;
import se.nordia.swedencore.core.NordiaCore;
import se.nordia.swedencore.events.DomainEvent;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.PropertyService;
import se.nordia.swedencore.properties.RegionIndex;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * In-memory view of property regions, their allowed builders and city areas, used for block protection.
 *
 * <p>Loaded asynchronously at startup and refreshed from {@link DomainEvent}s. Mutations happen on the main thread.
 * Until the initial load finishes, the index reports {@link #ready()} false and protection denies building inside
 * cities (fail closed) — the load takes milliseconds.
 */
public final class ProtectionIndex {

    private final NordiaCore core;
    private final Tasks tasks;
    private final RegionIndex<PropertyService.Access> properties =
            new RegionIndex<>(a -> a.property().region(), a -> a.property().id());
    private volatile List<City> cities = List.of();
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
        tasks.async(() -> new Snapshot(core.properties().accessList(), core.cities().all())).whenComplete((snapshot, error) -> {
            if (error != null) {
                core.logger().log(Level.SEVERE, "Failed to load property protection index", Tasks.unwrap(error));
                return;
            }
            tasks.sync(() -> {
                properties.clear();
                snapshot.access().forEach(properties::put);
                cities = snapshot.cities();
                ready = true;
                core.logger().info("Protection index loaded: " + properties.size() + " properties, " + cities.size() + " cities");
            });
        });
    }

    private record Snapshot(List<PropertyService.Access> access, List<City> cities) {
    }

    private void onEvent(DomainEvent event) {
        switch (event) {
            case DomainEvent.PropertyChanged changed -> refreshProperty(changed.propertyId());
            case DomainEvent.CompanyMembershipChanged membership -> refreshCompany(membership.companyId());
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

    public enum Decision {
        ALLOWED, DENIED_PROPERTY, DENIED_UNOWNED, DENIED_CITY_LAND, DENIED_LOADING
    }

    /**
     * Build rule: inside a property only allowed players; unowned property nobody; city land outside properties is
     * public space (nobody); wilderness is free.
     */
    public Decision canBuild(UUID player, String world, int x, int y, int z, boolean protectCityLand) {
        Optional<PropertyService.Access> access = properties.at(world, x, y, z);
        if (access.isPresent()) {
            if (access.get().property().status() == Property.Status.AVAILABLE) {
                return Decision.DENIED_UNOWNED;
            }
            return access.get().allowed().contains(player) ? Decision.ALLOWED : Decision.DENIED_PROPERTY;
        }
        if (!protectCityLand) {
            return Decision.ALLOWED;
        }
        if (!ready) {
            return Decision.DENIED_LOADING;
        }
        return cityAt(world, x, z).isPresent() ? Decision.DENIED_CITY_LAND : Decision.ALLOWED;
    }

    /** Identity of the protection area at a point: property id, negative city id for city land, 0 for wilderness. */
    public long areaKey(String world, int x, int y, int z) {
        Optional<PropertyService.Access> access = properties.at(world, x, y, z);
        if (access.isPresent()) {
            return access.get().property().id();
        }
        return cityAt(world, x, z).map(c -> -c.id()).orElse(0L);
    }
}
