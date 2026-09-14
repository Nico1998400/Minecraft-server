package se.nordia.swedencore.inventory;

import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.core.CoreConfig;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.Region;
import se.nordia.swedencore.testing.CoreTest;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StorageCapacityTest extends CoreTest {

    @Override
    protected CoreConfig configure(CoreConfig d) {
        return new CoreConfig(d.database(), d.economy(), d.skills(), d.companies(), d.contracts(), d.properties(), d.shops(),
                d.settlements(), d.orders(), d.production(), d.loans(), d.leases(), new ItemStashService.Capacity(2, 3, 5),
                d.defaultLocale(), d.shutdownOnDatabaseFailure());
    }

    private static List<ItemStashService.StashItem> stacks(int count) {
        return Collections.nCopies(count, new ItemStashService.StashItem("COAL", 64, new byte[]{1}, true));
    }

    @Test
    void warehousesRaiseCapacityAndFullInventoriesRejectVoluntaryInflows() {
        UUID owner = player("Owner");
        grant(owner, 50_000);
        Company company = core.companies().found(owner, "Lager AB");
        var stash = ItemStashService.Owner.company(company.id());
        assertThat(core.stash().usage(stash).capacity()).isEqualTo(3);

        core.stash().giveToCompany(owner, company.id(), stacks(3), UUID.randomUUID());
        assertDomainError(() -> core.stash().giveToCompany(owner, company.id(), stacks(1), UUID.randomUUID()), "stash.full");

        core.companies().deposit(owner, company.id(), Money.ofSek(20_000));
        Property warehouse = core.properties().create("Lagret", Property.Type.WAREHOUSE, Region.of("world", 0, 0, 0, 9, 9, 9), Money.ofSek(5_000));
        core.properties().buy(owner, warehouse.id(), company.id(), null);
        assertThat(core.stash().usage(stash).capacity()).isEqualTo(8);
        core.stash().giveToCompany(owner, company.id(), stacks(5), UUID.randomUUID());
        assertThat(core.stash().usage(stash).free()).isZero();

        // Work output never disappears: it overflows to the worker.
        core.stash().depositWorkOutput(company.id(), owner, stacks(1));
        assertThat(core.stash().usage(ItemStashService.Owner.player(owner)).used()).isEqualTo(1);
    }

    @Test
    void buyOrderFillsCannotOverflowTheIssuerStash() {
        UUID buyer = player("Buyer");
        UUID seller = player("Seller");
        grant(buyer, 10_000);
        var order = core.orders().create(buyer, null, "COAL", 300, Money.ofSek(1), 24);
        Money before = balance(seller);
        assertDomainError(() -> core.orders().fill(seller, order.id(), stacks(3), UUID.randomUUID()), "stash.full");
        assertThat(balance(seller)).isEqualTo(before);
        core.orders().fill(seller, order.id(), stacks(2), UUID.randomUUID());
        assertDomainError(() -> core.orders().fill(seller, order.id(), stacks(1), UUID.randomUUID()), "stash.full");
        assertLedgerHealthy();
    }
}
