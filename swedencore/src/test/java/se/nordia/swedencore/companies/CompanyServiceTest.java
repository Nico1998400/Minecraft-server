package se.nordia.swedencore.companies;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.jobs.JobApplication;
import se.nordia.swedencore.jobs.JobPosition;
import se.nordia.swedencore.testing.CoreTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyServiceTest extends CoreTest {

    private UUID owner;
    private CompanyService companies;

    @BeforeEach
    void setUp() {
        companies = core.companies();
        owner = player("Owner");
        grant(owner, 20_000);
    }

    private Company found(String name) {
        return companies.found(owner, name);
    }

    /** Hires {@code worker} via a real position/application flow. */
    private Employee hire(Company company, UUID worker, long salarySek) {
        JobPosition position = core.jobs().createPosition(owner, company.id(), "GENERAL_WORKER", "Worker", 1, Money.ofSek(salarySek), 10);
        JobApplication app = core.jobs().apply(worker, position.id(), null);
        core.jobs().accept(owner, app.id());
        return companies.employees(company.id()).stream().filter(e -> e.playerUuid().equals(worker)).findFirst().orElseThrow();
    }

    @Test
    void foundingChargesFeeAndCreatesAccountAndOwnerMembership() {
        Company company = found("Nordhamn Mining AB");
        assertThat(company.name()).isEqualTo("Nordhamn Mining AB");
        assertThat(balance(owner)).isEqualTo(Money.ofSek(21_000 - 5_000));
        assertThat(companyBalance(company.id())).isEqualTo(Money.ZERO);
        assertThat(companies.memberships(owner)).singleElement()
                .satisfies(m -> assertThat(m.role()).isEqualTo(CompanyRole.OWNER));
        assertLedgerHealthy();
    }

    @Test
    void cannotFoundWithoutAffordingFee() {
        UUID poor = player("Poor");
        assertDomainError(() -> companies.found(poor, "Poor Company"), "economy.insufficient_funds");
        assertThat(companies.memberships(poor)).isEmpty();
        assertThat(balance(poor)).isEqualTo(Money.ofSek(1_000));
    }

    @Test
    void rejectsInvalidAndDuplicateNames() {
        for (String bad : List.of("", "AB", "<red>Evil</red>", "Два Company", "  ", "Double  Space", "x".repeat(33), "-Start")) {
            assertDomainError(() -> found(bad), "company.invalid_name");
        }
        found("Göteborg Logistik AB");
        UUID other = player("Other");
        grant(other, 10_000);
        assertDomainError(() -> companies.found(other, "göteborg logistik ab"), "company.name_taken");
    }

    @Test
    void ownershipLimitIsEnforced() {
        grant(owner, 100_000);
        found("Alpha AB");
        found("Beta AB");
        found("Gamma AB");
        assertDomainError(() -> found("Delta AB"), "company.too_many_owned");
    }

    @Test
    void onlyOwnerCanWithdrawAndOutsidersCannotDeposit() {
        Company company = found("Vault AB");
        UUID worker = player("Worker");
        UUID outsider = player("Outsider");
        hire(company, worker, 0);

        companies.deposit(owner, company.id(), Money.ofSek(1_000));
        companies.deposit(worker, company.id(), Money.ofSek(100));
        assertDomainError(() -> companies.deposit(outsider, company.id(), Money.ofSek(1)), "company.not_member");
        assertDomainError(() -> companies.withdraw(worker, company.id(), Money.ofSek(1)), "company.no_permission");
        assertDomainError(() -> companies.withdraw(outsider, company.id(), Money.ofSek(1)), "company.not_member");
        assertDomainError(() -> companies.withdraw(owner, company.id(), Money.ofSek(1_101)), "economy.insufficient_funds");

        companies.withdraw(owner, company.id(), Money.ofSek(600));
        assertThat(companyBalance(company.id())).isEqualTo(Money.ofSek(500));
        assertLedgerHealthy();
    }

    @Test
    void staffPermissions() {
        Company company = found("Hierarchy AB");
        UUID manager = player("Manager");
        UUID employee = player("Employee");
        UUID employee2 = player("Employee2");
        hire(company, manager, 0);
        hire(company, employee, 0);
        hire(company, employee2, 0);
        companies.setRole(owner, company.id(), manager, CompanyRole.MANAGER);

        assertDomainError(() -> companies.setRole(manager, company.id(), employee, CompanyRole.MANAGER), "company.no_permission");
        assertDomainError(() -> companies.setRole(owner, company.id(), manager, CompanyRole.OWNER), "company.no_permission");
        assertDomainError(() -> companies.terminate(employee, company.id(), employee2), "company.no_permission");
        assertDomainError(() -> companies.terminate(manager, company.id(), owner), "company.no_permission");
        assertDomainError(() -> companies.setSalary(manager, company.id(), manager, Money.ofSek(9_999)), "company.no_permission");
        assertDomainError(() -> companies.setSalary(owner, company.id(), employee, Money.ofSek(1_000_000)), "company.invalid_salary");

        companies.terminate(manager, company.id(), employee);
        assertDomainError(() -> companies.terminate(manager, company.id(), employee), "company.target_not_member");
        assertDomainError(() -> companies.leave(owner, company.id()), "company.owner_cannot_leave");
        companies.leave(employee2, company.id());
        assertThat(companies.employees(company.id())).extracting(Employee::playerUuid).containsExactlyInAnyOrder(owner, manager);
    }

    @Test
    void dissolutionRequiresNoEmployeesAndPaysOutBalance() {
        Company company = found("Short Lived AB");
        UUID worker = player("Worker");
        hire(company, worker, 0);
        companies.deposit(owner, company.id(), Money.ofSek(2_000));
        assertDomainError(() -> companies.dissolve(owner, company.id()), "company.dissolve_has_employees");
        assertDomainError(() -> companies.dissolve(worker, company.id()), "company.no_permission");
        companies.terminate(owner, company.id(), worker);

        Money before = balance(owner);
        Money payout = companies.dissolve(owner, company.id());
        assertThat(payout).isEqualTo(Money.ofSek(2_000));
        assertThat(balance(owner)).isEqualTo(before.plus(payout));
        assertDomainError(() -> companies.deposit(owner, company.id(), Money.ofSek(1)), "company.not_active");
        // Name can be reused after dissolution.
        found("Short Lived AB");
        assertLedgerHealthy();
    }

    @Test
    void concurrentFoundingCannotExceedLimit() throws Exception {
        grant(owner, 1_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final String name = "Racer " + (char) ('A' + i) + " AB";
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    companies.found(owner, name);
                    return true;
                } catch (se.nordia.swedencore.core.DomainException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<Boolean> f : futures) {
            created += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(3);
    }
}
