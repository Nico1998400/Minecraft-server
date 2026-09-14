package se.nordia.swedencore.properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.reputation.ReputationService;
import se.nordia.swedencore.testing.CoreTest;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeaseServiceTest extends CoreTest {

    private LeaseService leases;
    private UUID landlord;
    private UUID tenant;
    private Property shopProperty;

    @BeforeEach
    void setUp() {
        leases = core.leases();
        landlord = player("Landlord");
        tenant = player("Tenant");
        grant(landlord, 50_000);
        grant(tenant, 5_000);
        shopProperty = core.properties().create("Hyresbutiken", Property.Type.SHOP, Region.of("world", 0, 0, 0, 9, 9, 9), Money.ofSek(20_000));
        core.properties().buy(landlord, shopProperty.id(), null, null);
    }

    private Property property() {
        return core.properties().find(shopProperty.id()).orElseThrow();
    }

    @Test
    void rentingGivesTenantExclusiveUseAndPaysLandlord() {
        UUID friend = player("Friend");
        core.properties().trust(landlord, shopProperty.id(), friend);
        assertDomainError(() -> leases.listForRent(tenant, shopProperty.id(), Money.ofSek(1_000), 24), "property.not_owner");
        leases.listForRent(landlord, shopProperty.id(), Money.ofSek(1_000), 24);
        assertDomainError(() -> leases.rent(landlord, shopProperty.id(), null, null), "lease.own_property");
        assertDomainError(() -> leases.rent(tenant, shopProperty.id(), null, Money.ofSek(900)), "lease.rent_changed");

        leases.rent(tenant, shopProperty.id(), null, Money.ofSek(1_000));
        assertThat(balance(tenant)).isEqualTo(Money.ofSek(5_000));
        assertThat(balance(landlord)).isEqualTo(Money.ofSek(51_000 - 20_000 + 1_000));
        assertThat(property().occupantName()).isEqualTo("Tenant");
        assertThat(core.properties().access(shopProperty.id()).orElseThrow().allowed()).containsExactly(tenant);
        assertDomainError(() -> core.properties().listForSale(landlord, shopProperty.id(), Money.ofSek(1)), "property.leased");

        // The tenant runs a shop; revenue goes to the tenant.
        var shop = core.shops().create(tenant, shopProperty.id(), "Hyresgästens butik");
        assertDomainError(() -> core.shops().create(landlord, shopProperty.id(), "Nope"), "property.not_owner");
        var listing = core.shops().list(tenant, shop.id(), "world", 1, 1, 1, "BREAD", new byte[]{1}, 1, Money.ofSek(10));
        UUID customer = player("Customer");
        core.shops().recordPurchase(customer, listing.id(), 2, Money.ofSek(10), UUID.randomUUID());
        assertThat(balance(tenant)).isEqualTo(Money.ofSek(5_020));
        assertLedgerHealthy();
    }

    @Test
    void rentIsCollectedEachPeriodAndTenantIsEvictedWhenUnable() {
        leases.listForRent(landlord, shopProperty.id(), Money.ofSek(2_000), 24);
        leases.rent(tenant, shopProperty.id(), null, null);
        clock.advance(Duration.ofHours(24));
        assertThat(leases.collectDue()).extracting(LeaseService.Outcome::result).containsExactly("PAID");
        assertThat(balance(tenant)).isEqualTo(Money.ofSek(2_000));
        clock.advance(Duration.ofHours(24));
        leases.collectDue();
        assertThat(balance(tenant)).isEqualTo(Money.ZERO);
        clock.advance(Duration.ofHours(24));
        assertThat(leases.collectDue()).extracting(LeaseService.Outcome::result).containsExactly("OVERDUE");
        assertThat(property().leased()).isTrue();
        clock.advance(Duration.ofHours(25));
        assertThat(leases.collectDue()).extracting(LeaseService.Outcome::result).containsExactly("EVICTED");
        assertThat(property().leased()).isFalse();
        assertThat(core.reputation().score(ReputationService.Subject.player(tenant))).isEqualTo(-5);
        assertThat(core.properties().access(shopProperty.id()).orElseThrow().allowed()).containsExactly(landlord);
        assertLedgerHealthy();
    }

    @Test
    void endingLeasesClosesTheTenantsShop() {
        leases.listForRent(landlord, shopProperty.id(), Money.ofSek(500), 24);
        leases.rent(tenant, shopProperty.id(), null, null);
        var shop = core.shops().create(tenant, shopProperty.id(), "Kortlivad");
        assertDomainError(() -> leases.endByTenant(landlord, shopProperty.id()), "lease.not_tenant");
        leases.endByTenant(tenant, shopProperty.id());
        assertThat(core.shops().find(shop.id()).orElseThrow().open()).isFalse();

        leases.listForRent(landlord, shopProperty.id(), Money.ofSek(500), 24);
        leases.rent(tenant, shopProperty.id(), null, null);
        leases.endByOwner(landlord, shopProperty.id());
        clock.advance(Duration.ofHours(24));
        assertThat(leases.collectDue()).extracting(LeaseService.Outcome::result).containsExactly("ENDED");
        assertThat(balance(tenant)).as("no charge after owner ended the lease").isEqualTo(Money.ofSek(5_000));
    }

    @Test
    void companyTenantsAndBankruptcy() {
        UUID owner = player("Owner");
        grant(owner, 20_000);
        Company company = core.companies().found(owner, "Hyresgäst AB");
        core.companies().deposit(owner, company.id(), Money.ofSek(10_000));
        leases.listForRent(landlord, shopProperty.id(), Money.ofSek(1_000), 24);
        leases.rent(owner, shopProperty.id(), company.id(), null);
        assertThat(property().occupantName()).isEqualTo("Hyresgäst AB");
        assertDomainError(() -> core.companies().dissolve(owner, company.id()), "company.dissolve_has_leases");

        var loan = core.loans().offer(landlord, se.nordia.swedencore.finance.LoanService.Party.player(landlord),
                se.nordia.swedencore.finance.LoanService.Party.company(company.id()), Money.ofSek(100), 0, 1, 24);
        core.loans().accept(owner, loan.id());
        core.bankruptcy().declare(company.id(), se.nordia.swedencore.finance.BankruptcyService.Reason.VOLUNTARY, owner);
        assertThat(property().leased()).isFalse();
        assertThat(property().ownerName()).isEqualTo("Landlord");
        assertLedgerHealthy();
    }
}
