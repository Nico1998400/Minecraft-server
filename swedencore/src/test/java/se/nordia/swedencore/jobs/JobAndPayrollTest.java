package se.nordia.swedencore.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.companies.Employee;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.reputation.ReputationService;
import se.nordia.swedencore.skills.Skill;
import se.nordia.swedencore.testing.CoreTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class JobAndPayrollTest extends CoreTest {

    private UUID owner;
    private UUID miner;
    private Company company;

    @BeforeEach
    void setUp() {
        owner = player("Owner");
        miner = player("Miner");
        grant(owner, 50_000);
        company = core.companies().found(owner, "Nordhamn Mining AB");
    }

    private JobPosition minerPosition(int requiredLevel, long salarySek, int openings) {
        return core.jobs().createPosition(owner, company.id(), "MINER", "Gruvarbetare", requiredLevel, Money.ofSek(salarySek), openings);
    }

    private Employee employeeOf(UUID player) {
        return core.companies().employees(company.id()).stream().filter(e -> e.playerUuid().equals(player)).findFirst().orElseThrow();
    }

    @Test
    void fullHiringFlow() {
        JobPosition position = minerPosition(1, 250, 3);
        assertThat(core.jobs().openPositions(10, 0)).extracting(JobPosition::id).contains(position.id());
        JobApplication app = core.jobs().apply(miner, position.id(), "Jag gräver gärna!");
        assertThat(core.jobs().pendingApplications(owner, company.id())).extracting(JobApplication::id).containsExactly(app.id());

        JobApplication accepted = core.jobs().accept(owner, app.id());
        assertThat(accepted.status()).isEqualTo(JobApplication.Status.ACCEPTED);
        Employee employee = employeeOf(miner);
        assertThat(employee.role()).isEqualTo(CompanyRole.EMPLOYEE);
        assertThat(employee.skill()).isEqualTo(Skill.MINING);
        assertThat(employee.salaryPerHour()).isEqualTo(Money.ofSek(250));
    }

    @Test
    void applicationRules() {
        JobPosition skilled = minerPosition(10, 400, 1);
        assertDomainError(() -> core.jobs().apply(miner, skilled.id(), null), "job.skill_too_low");
        core.skills().addXp(miner, Skill.MINING, core.skills().curve().totalXpForLevel(10));
        JobApplication app = core.jobs().apply(miner, skilled.id(), null);
        assertDomainError(() -> core.jobs().apply(miner, skilled.id(), null), "job.already_applied");
        assertDomainError(() -> core.jobs().apply(owner, skilled.id(), null), "job.already_member");

        UUID stranger = player("Stranger");
        assertDomainError(() -> core.jobs().accept(stranger, app.id()), "company.not_member");
        assertDomainError(() -> core.jobs().withdraw(stranger, app.id()), "job.application_not_found");

        core.jobs().accept(owner, app.id());
        assertDomainError(() -> core.jobs().accept(owner, app.id()), "job.application_not_pending");
        UUID another = player("Another");
        assertDomainError(() -> core.jobs().apply(another, skilled.id(), null), "job.position_full");

        core.jobs().closePosition(owner, skilled.id());
        assertDomainError(() -> core.jobs().apply(another, skilled.id(), null), "job.position_closed");
    }

    @Test
    void employeesCannotManagePositions() {
        JobPosition position = minerPosition(1, 100, 5);
        core.jobs().accept(owner, core.jobs().apply(miner, position.id(), null).id());
        assertDomainError(() -> core.jobs().createPosition(miner, company.id(), "MINER", "Boss", 1, Money.ofSek(1), 1), "company.no_permission");
        assertDomainError(() -> core.jobs().closePosition(miner, position.id()), "company.no_permission");
        assertDomainError(() -> core.jobs().createPosition(owner, company.id(), "MINER", "<b>x</b>", 1, Money.ofSek(1), 1), "job.invalid_title");
        assertDomainError(() -> core.jobs().createPosition(owner, company.id(), "NOT_A_JOB", "Title", 1, Money.ofSek(1), 1), "job.unknown_job");
        assertDomainError(() -> core.jobs().createPosition(owner, company.id(), "MINER", "Title", 101, Money.ofSek(1), 1), "job.invalid_level");
    }

    @Test
    void concurrentAcceptsCannotOverfillLastOpening() throws Exception {
        JobPosition position = minerPosition(1, 100, 1);
        List<Long> applications = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            applications.add(core.jobs().apply(player("Applicant" + i), position.id(), null).id());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (long id : applications) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    core.jobs().accept(owner, id);
                    return true;
                } catch (DomainException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int hired = 0;
        for (Future<Boolean> f : futures) {
            hired += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(hired).isEqualTo(1);
        assertThat(core.companies().employees(company.id())).hasSize(2);
    }

    @Test
    void payrollPaysVerifiedMinutesFromCompanyAccount() {
        JobPosition position = minerPosition(1, 300, 1);
        core.jobs().accept(owner, core.jobs().apply(miner, position.id(), null).id());
        core.companies().deposit(owner, company.id(), Money.ofSek(10_000));
        Employee employee = employeeOf(miner);

        Instant period = clock.instant().minus(Duration.ofMinutes(5));
        Optional<PayrollService.PayrollResult> result = core.payroll().recordWork(employee.id(), period, 5);
        assertThat(result).get().satisfies(r -> {
            assertThat(r.paid()).isTrue();
            assertThat(r.amount()).isEqualTo(Money.ofSek(25)); // 300 SEK/h × 5 min
        });
        assertThat(balance(miner)).isEqualTo(Money.ofSek(1_025));
        assertThat(companyBalance(company.id())).isEqualTo(Money.ofSek(9_975));

        // Retrying the same batch never pays twice.
        assertThat(core.payroll().recordWork(employee.id(), period, 5)).get().satisfies(r -> assertThat(r.duplicate()).isTrue());
        assertThat(balance(miner)).isEqualTo(Money.ofSek(1_025));
        assertLedgerHealthy();
    }

    @Test
    void unpaidWagesBecomeArrearsHurtReputationAndBlockWithdrawals() {
        JobPosition position = minerPosition(1, 600, 1);
        core.jobs().accept(owner, core.jobs().apply(miner, position.id(), null).id());
        core.companies().deposit(owner, company.id(), Money.ofSek(30));
        Employee employee = employeeOf(miner);

        // 600 SEK/h: 5 min = 50 SEK, company has 30 SEK.
        var first = core.payroll().recordWork(employee.id(), clock.instant().minus(Duration.ofMinutes(10)), 5).orElseThrow();
        assertThat(first.paid()).isFalse();
        assertThat(core.payroll().arrears(company.id())).isEqualTo(Money.ofSek(50));
        assertThat(core.reputation().score(ReputationService.Subject.company(company.id()))).isEqualTo(-1);
        assertDomainError(() -> core.companies().withdraw(owner, company.id(), Money.ofSek(10)), "company.withdraw_blocked_by_arrears");
        assertDomainError(() -> core.companies().dissolve(owner, company.id()), "company.dissolve_has_employees");

        // Deposit settles arrears automatically.
        core.companies().deposit(owner, company.id(), Money.ofSek(100));
        assertThat(core.payroll().arrears(company.id())).isEqualTo(Money.ZERO);
        assertThat(balance(miner)).isEqualTo(Money.ofSek(1_050));
        assertThat(companyBalance(company.id())).isEqualTo(Money.ofSek(80));
        assertLedgerHealthy();
    }

    @Test
    void oldestArrearsArePaidFirst() {
        JobPosition position = minerPosition(1, 1_200, 2);
        UUID second = player("Second");
        core.jobs().accept(owner, core.jobs().apply(miner, position.id(), null).id());
        core.jobs().accept(owner, core.jobs().apply(second, position.id(), null).id());
        Employee a = employeeOf(miner);
        Employee b = employeeOf(second);
        // Each batch: 1200/h × 5 min = 100 SEK. Company is empty.
        core.payroll().recordWork(a.id(), clock.instant().minus(Duration.ofMinutes(20)), 5);
        core.payroll().recordWork(b.id(), clock.instant().minus(Duration.ofMinutes(15)), 5);
        core.companies().deposit(owner, company.id(), Money.ofSek(150));
        assertThat(balance(miner)).isEqualTo(Money.ofSek(1_100));
        assertThat(balance(second)).isEqualTo(Money.ofSek(1_000));
        assertThat(core.payroll().arrears(company.id())).isEqualTo(Money.ofSek(100));
    }

    @Test
    void terminatedEmployeesCannotBePaidForNewWorkButKeepArrears() {
        JobPosition position = minerPosition(1, 600, 1);
        core.jobs().accept(owner, core.jobs().apply(miner, position.id(), null).id());
        Employee employee = employeeOf(miner);
        core.payroll().recordWork(employee.id(), clock.instant().minus(Duration.ofMinutes(10)), 5);
        core.companies().terminate(owner, company.id(), miner);
        assertDomainError(() -> core.payroll().recordWork(employee.id(), clock.instant().minus(Duration.ofMinutes(4)), 4), "payroll.not_employed");

        core.companies().deposit(owner, company.id(), Money.ofSek(50));
        assertThat(balance(miner)).isEqualTo(Money.ofSek(1_050));
    }

    @Test
    void rejectsImpossibleWorkBatches() {
        JobPosition position = minerPosition(1, 600, 1);
        core.jobs().accept(owner, core.jobs().apply(miner, position.id(), null).id());
        long id = employeeOf(miner).id();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> core.payroll().recordWork(id, clock.instant(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> core.payroll().recordWork(id, clock.instant(), 500))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> core.payroll().recordWork(id, clock.instant().plus(Duration.ofHours(1)), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reputationIsClampedAndIdempotent() {
        var subject = ReputationService.Subject.player(miner);
        assertThat(core.reputation().adjust(subject, 150, "TEST", "k1")).isEqualTo(100);
        assertThat(core.reputation().adjust(subject, 150, "TEST", "k1")).isEqualTo(100);
        assertThat(core.reputation().adjust(subject, -200, "TEST", "k2")).isEqualTo(-100);
        assertThat(core.reputation().history(subject, 10)).hasSize(2);
    }
}
