package se.nordia.swedencore.properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.cities.City;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.events.DomainEvent;
import se.nordia.swedencore.testing.CoreTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyServiceTest extends CoreTest {

    private PropertyService properties;
    private City stockholm;
    private UUID buyer;
    private final List<DomainEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        properties = core.properties();
        core.events().subscribe(events::add);
        stockholm = core.cities().create("Stockholm", "world", 0, 0, 500);
        buyer = player("Buyer");
        grant(buyer, 500_000);
    }

    private Property house(String name, int x) {
        return properties.create(name, Property.Type.HOUSE, Region.of("world", x, 60, 0, x + 15, 80, 15), Money.ofSek(100_000));
    }

    @Test
    void citiesCannotOverlapOrShareNames() {
        assertDomainError(() -> core.cities().create("Stockholm", "world", 5_000, 5_000, 100), "city.name_taken");
        assertDomainError(() -> core.cities().create("Solna", "world", 400, 400, 200), "city.overlaps");
        City goteborg = core.cities().create("Göteborg", "world", 5_000, 0, 400);
        assertThat(goteborg.contains("world", 5_300, 100)).isTrue();
        assertThat(goteborg.contains("world_nether", 5_300, 100)).isFalse();
    }

    @Test
    void propertiesCannotOverlapAndAreAssignedToCities() {
        Property a = house("Storgatan 1", 0);
        assertThat(a.cityId()).isEqualTo(stockholm.id());
        assertDomainError(() -> properties.create("Storgatan 2", Property.Type.HOUSE, Region.of("world", 15, 80, 15, 30, 90, 30), Money.ofSek(1)),
                "property.overlaps");
        Property wilderness = properties.create("Stuga i skogen", Property.Type.HOUSE, Region.of("world", 2_000, 60, 2_000, 2_010, 70, 2_010), Money.ofSek(1_000));
        assertThat(wilderness.cityId()).isNull();
        assertDomainError(() -> properties.create("<b>x</b>", Property.Type.HOUSE, Region.of("world", 900, 0, 900, 901, 1, 901), Money.ofSek(1)),
                "property.invalid_name");
        assertDomainError(() -> properties.create("Enormous", Property.Type.MINE, Region.of("world", -5_000, -64, -5_000, 5_000, 300, 5_000), Money.ofSek(1)),
                "property.too_large");
    }

    @Test
    void buyingCityPropertyPaysTheTreasury() {
        Property house = house("Storgatan 1", 0);
        Property bought = properties.buy(buyer, house.id(), null, Money.ofSek(100_000));
        assertThat(bought.status()).isEqualTo(Property.Status.OWNED);
        assertThat(bought.ownerName()).isEqualTo("Buyer");
        assertThat(balance(buyer)).isEqualTo(Money.ofSek(401_000));
        assertThat(core.economy().balance(AccountOwner.city(stockholm.id()))).isEqualTo(Money.ofSek(100_000));
        assertThat(core.cities().stats(stockholm.id()).residents()).isEqualTo(1);
        assertThat(events).anyMatch(e -> e instanceof DomainEvent.PropertySold);
        assertDomainError(() -> properties.buy(buyer, house.id(), null, null), "property.not_for_sale");
        assertLedgerHealthy();
    }

    @Test
    void wildernessPropertyPurchaseIsASink() {
        Property cabin = properties.create("Stuga", Property.Type.HOUSE, Region.of("world", 3_000, 60, 3_000, 3_010, 70, 3_010), Money.ofSek(2_000));
        Money supplyBefore = core.economy().moneySupply();
        properties.buy(buyer, cabin.id(), null, null);
        assertThat(core.economy().moneySupply()).isEqualTo(supplyBefore.minus(Money.ofSek(2_000)));
    }

    @Test
    void resalePaysPreviousOwnerAndClearsTrust() {
        Property house = house("Storgatan 1", 0);
        properties.buy(buyer, house.id(), null, null);
        UUID friend = player("Friend");
        properties.trust(buyer, house.id(), friend);
        assertThat(properties.access(house.id()).orElseThrow().allowed()).containsExactlyInAnyOrder(buyer, friend);

        UUID second = player("Second");
        grant(second, 300_000);
        assertDomainError(() -> properties.buy(second, house.id(), null, null), "property.not_for_sale");
        assertDomainError(() -> properties.listForSale(second, house.id(), Money.ofSek(1)), "property.not_owner");
        properties.listForSale(buyer, house.id(), Money.ofSek(250_000));
        assertDomainError(() -> properties.buy(second, house.id(), null, Money.ofSek(100_000)), "property.price_changed");
        properties.buy(second, house.id(), null, Money.ofSek(250_000));

        assertThat(balance(buyer)).isEqualTo(Money.ofSek(401_000 + 250_000));
        Property after = properties.find(house.id()).orElseThrow();
        assertThat(after.ownerName()).isEqualTo("Second");
        assertThat(after.marketValue()).isEqualTo(Money.ofSek(250_000));
        assertThat(properties.access(house.id()).orElseThrow().allowed()).containsExactly(second);
        assertLedgerHealthy();
    }

    @Test
    void companyPropertiesAreOpenToAllStaffAndBlockDissolution() {
        Company company = core.companies().found(buyer, "Gruvbolaget AB");
        core.companies().deposit(buyer, company.id(), Money.ofSek(200_000));
        Property mine = properties.create("Norra gruvan", Property.Type.MINE, Region.of("world", 100, 0, 100, 150, 60, 150), Money.ofSek(150_000));
        UUID worker = player("Worker");
        assertDomainError(() -> properties.buy(worker, mine.id(), company.id(), null), "company.not_member");
        properties.buy(buyer, mine.id(), company.id(), null);
        assertThat(core.companies().balance(company.id())).isEqualTo(Money.ofSek(50_000));

        var position = core.jobs().createPosition(buyer, company.id(), "MINER", "Gruvarbetare", 1, Money.ofSek(100), 5);
        core.jobs().accept(buyer, core.jobs().apply(worker, position.id(), null).id());
        assertThat(events).anyMatch(e -> e instanceof DomainEvent.CompanyMembershipChanged c && c.companyId() == company.id());
        assertThat(properties.accessForCompany(company.id())).singleElement()
                .satisfies(a -> assertThat(a.allowed()).containsExactlyInAnyOrder(buyer, worker));

        core.companies().terminate(buyer, company.id(), worker);
        assertThat(properties.accessForCompany(company.id()).getFirst().allowed()).containsExactly(buyer);
        assertDomainError(() -> core.companies().dissolve(buyer, company.id()), "company.dissolve_has_properties");
        assertThat(properties.ownedBy(buyer)).extracting(Property::id).containsExactly(mine.id());
    }

    @Test
    void ownershipLimitAndDeletionRules() {
        grant(buyer, 5_000_000);
        for (int i = 0; i < 10; i++) {
            properties.buy(buyer, house("Hus " + i, 1_000 + i * 20).id(), null, null);
        }
        Property eleventh = house("Hus elva", 900);
        assertDomainError(() -> properties.buy(buyer, eleventh.id(), null, null), "property.too_many_owned");
        Property owned = properties.ownedBy(buyer).getFirst();
        assertDomainError(() -> properties.delete(owned.id()), "property.owned");
        properties.delete(eleventh.id());
        assertThat(properties.find(eleventh.id())).isEmpty();
    }

    @Test
    void concurrentBuyersOnlyOneWins() throws Exception {
        Property house = house("Eftertraktad", 0);
        List<UUID> buyers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID b = player("Bidder" + i);
            grant(b, 200_000);
            buyers.add(b);
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (UUID b : buyers) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    properties.buy(b, house.id(), null, null);
                    return true;
                } catch (DomainException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int wins = 0;
        for (Future<Boolean> f : futures) {
            wins += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(wins).isEqualTo(1);
        assertThat(core.economy().balance(AccountOwner.city(stockholm.id()))).isEqualTo(Money.ofSek(100_000));
        assertLedgerHealthy();
    }
}
