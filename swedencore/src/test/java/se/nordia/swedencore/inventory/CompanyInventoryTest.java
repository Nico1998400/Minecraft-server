package se.nordia.swedencore.inventory;

import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.testing.CoreTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyInventoryTest extends CoreTest {

    private static List<ItemStashService.StashItem> ore(int amount) {
        return List.of(new ItemStashService.StashItem("IRON_ORE", amount, new byte[]{7}));
    }

    @Test
    void membersCanGiveItemsAndManagersClaimThem() {
        UUID owner = player("Owner");
        UUID miner = player("Miner");
        UUID outsider = player("Outsider");
        grant(owner, 10_000);
        Company company = core.companies().found(owner, "Gruvan AB");
        var position = core.jobs().createPosition(owner, company.id(), "MINER", "Miner", 1, Money.ZERO, 1);
        core.jobs().accept(owner, core.jobs().apply(miner, position.id(), null).id());

        UUID token = UUID.randomUUID();
        core.stash().giveToCompany(miner, company.id(), ore(32), token);
        assertThat(core.stash().depositRecorded(token)).isTrue();
        assertDomainError(() -> core.stash().giveToCompany(outsider, company.id(), ore(1), UUID.randomUUID()), "company.not_member");
        var companyStash = ItemStashService.Owner.company(company.id());
        assertDomainError(() -> core.stash().claim(miner, companyStash, 5), "company.no_permission");
        assertThat(core.stash().claim(owner, companyStash, 5)).singleElement().satisfies(e -> assertThat(e.amount()).isEqualTo(32));
    }

    @Test
    void workOutputGoesToEmployerOrBackToWorkerAfterTermination() {
        UUID owner = player("Owner");
        UUID miner = player("Miner");
        grant(owner, 10_000);
        Company company = core.companies().found(owner, "Gruvan AB");
        var position = core.jobs().createPosition(owner, company.id(), "MINER", "Miner", 1, Money.ZERO, 1);
        core.jobs().accept(owner, core.jobs().apply(miner, position.id(), null).id());

        core.stash().depositWorkOutput(company.id(), miner, ore(10));
        assertThat(core.stash().summary(ItemStashService.Owner.company(company.id()))).singleElement()
                .satisfies(s -> assertThat(s.total()).isEqualTo(10));

        core.companies().terminate(owner, company.id(), miner);
        core.stash().depositWorkOutput(company.id(), miner, ore(4));
        assertThat(core.stash().summary(ItemStashService.Owner.player(miner))).singleElement()
                .satisfies(s -> assertThat(s.total()).isEqualTo(4));
    }
}
