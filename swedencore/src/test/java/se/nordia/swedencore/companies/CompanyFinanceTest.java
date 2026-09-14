package se.nordia.swedencore.companies;

import org.junit.jupiter.api.Test;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.finance.LoanService;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.Region;
import se.nordia.swedencore.testing.CoreTest;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyFinanceTest extends CoreTest {

    @Test
    void statementsSeparateOperationsFromFinancing() {
        UUID owner = player("Owner");
        UUID worker = player("Worker");
        UUID customer = player("Customer");
        UUID bank = player("Bank");
        grant(owner, 50_000);
        grant(customer, 10_000);
        grant(bank, 50_000);
        Company company = core.companies().found(owner, "Siffror AB");
        core.companies().deposit(owner, company.id(), Money.ofSek(20_000));
        var loan = core.loans().offer(bank, LoanService.Party.player(bank), LoanService.Party.company(company.id()), Money.ofSek(5_000), 10, 1, 24);
        core.loans().accept(owner, loan.id());

        Property store = core.properties().create("Butiken", Property.Type.SHOP, Region.of("world", 0, 0, 0, 5, 5, 5), Money.ofSek(8_000));
        core.properties().buy(owner, store.id(), company.id(), null);
        var shop = core.shops().create(owner, store.id(), "Siffror");
        var listing = core.shops().list(owner, shop.id(), "world", 1, 1, 1, "BREAD", new byte[]{1}, 1, Money.ofSek(300));
        core.shops().recordPurchase(customer, listing.id(), 10, Money.ofSek(300), UUID.randomUUID());

        var position = core.jobs().createPosition(owner, company.id(), "SHOP_ASSISTANT", "Kassa", 1, Money.ofSek(1_200), 1);
        core.jobs().accept(owner, core.jobs().apply(worker, position.id(), null).id());
        var employee = core.companies().employees(company.id()).stream().filter(e -> e.playerUuid().equals(worker)).findFirst().orElseThrow();
        core.payroll().recordWork(employee.id(), clock.instant().minus(Duration.ofMinutes(10)), 10);

        var report = core.companyFinance().report(owner, company.id(), 30);
        assertThat(report.income().revenue()).isEqualTo(Money.ofSek(3_000));
        assertThat(report.income().wages()).isEqualTo(Money.ofSek(200));
        assertThat(report.income().otherCosts()).isEqualTo(Money.ofSek(8_000)); // property purchase
        assertThat(report.income().financingIn()).isEqualTo(Money.ofSek(25_000));
        assertThat(report.balance().cash()).isEqualTo(Money.ofSek(20_000 + 5_000 - 8_000 + 3_000 - 200));
        assertThat(report.balance().properties()).isEqualTo(Money.ofSek(8_000));
        assertThat(report.balance().debt()).isEqualTo(Money.ofSek(5_500));
        assertThat(report.balance().equity()).isEqualTo(Money.ofSek(19_800 + 8_000 - 5_500));
        assertThat(report.balance().employees()).isEqualTo(2);

        assertDomainError(() -> core.companyFinance().report(worker, company.id(), 30), "company.no_permission");
    }
}
