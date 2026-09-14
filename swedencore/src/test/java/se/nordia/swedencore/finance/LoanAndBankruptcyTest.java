package se.nordia.swedencore.finance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.contracts.Contract;
import se.nordia.swedencore.contracts.ContractService;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.Region;
import se.nordia.swedencore.reputation.ReputationService;
import se.nordia.swedencore.testing.CoreTest;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoanAndBankruptcyTest extends CoreTest {

    private UUID lender;
    private UUID borrower;
    private LoanService loans;

    @BeforeEach
    void setUp() {
        loans = core.loans();
        lender = player("Lender");
        borrower = player("Borrower");
        grant(lender, 100_000);
    }

    private int rep(UUID player) {
        return core.reputation().score(ReputationService.Subject.player(player));
    }

    @Test
    void scheduleIsCollectedAndLoanRepaid() {
        Loan offer = loans.offer(lender, LoanService.Party.player(lender), LoanService.Party.player(borrower), Money.ofSek(10_000), 10, 4, 24);
        assertThat(offer.totalRepayment()).isEqualTo(Money.ofSek(11_000));
        assertThat(balance(borrower)).isEqualTo(Money.ofSek(1_000));
        loans.accept(borrower, offer.id());
        assertThat(balance(borrower)).isEqualTo(Money.ofSek(11_000));

        assertThat(loans.collectDue()).allSatisfy(c -> assertThat(c.collected()).isEqualTo(Money.ZERO));
        clock.advance(Duration.ofHours(24));
        loans.collectDue();
        assertThat(balance(lender)).isEqualTo(Money.ofSek(101_000 - 10_000 + 2_750));
        clock.advance(Duration.ofHours(72));
        var result = loans.collectDue().getFirst();
        assertThat(result.repaid()).isTrue();
        assertThat(loans.find(offer.id()).orElseThrow().status()).isEqualTo(Loan.Status.REPAID);
        assertThat(balance(borrower)).isEqualTo(Money.ZERO);
        assertThat(rep(borrower)).isEqualTo(2);
        assertLedgerHealthy();
    }

    @Test
    void offerRules() {
        var lp = LoanService.Party.player(lender);
        var bp = LoanService.Party.player(borrower);
        assertDomainError(() -> loans.offer(lender, lp, bp, Money.ofSek(100), 101, 1, 24), "loan.invalid_interest");
        assertDomainError(() -> loans.offer(lender, lp, bp, Money.ofSek(100), 5, 0, 24), "loan.invalid_schedule");
        assertDomainError(() -> loans.offer(lender, lp, bp, Money.ofSek(100), 5, 1, 1), "loan.invalid_schedule");
        assertDomainError(() -> loans.offer(lender, lp, lp, Money.ofSek(100), 5, 1, 24), "loan.self");
        assertDomainError(() -> loans.offer(borrower, lp, bp, Money.ofSek(100), 5, 1, 24), "loan.not_party");

        Loan offer = loans.offer(lender, lp, bp, Money.ofSek(100), 5, 1, 24);
        assertDomainError(() -> loans.accept(lender, offer.id()), "loan.not_party");
        assertDomainError(() -> loans.withdraw(borrower, offer.id()), "loan.not_party");
        loans.decline(borrower, offer.id());
        assertDomainError(() -> loans.accept(borrower, offer.id()), "loan.not_offered");

        Loan tooBig = loans.offer(lender, lp, bp, Money.ofSek(500_000), 5, 1, 24);
        assertDomainError(() -> loans.accept(borrower, tooBig.id()), "loan.lender_cannot_pay");

        Loan stale = loans.offer(lender, lp, bp, Money.ofSek(100), 5, 1, 24);
        clock.advance(Duration.ofHours(73));
        assertThat(loans.accept(borrower, stale.id()).status()).isEqualTo(Loan.Status.WITHDRAWN);
        assertThat(balance(borrower)).isEqualTo(Money.ofSek(1_000));
    }

    @Test
    void latePlayerDefaultsButKeepsPaying() {
        Loan loan = loans.offer(lender, LoanService.Party.player(lender), LoanService.Party.player(borrower), Money.ofSek(3_000), 0, 3, 24);
        loans.accept(borrower, loan.id());
        UUID sink = player("Spender");
        core.economy().pay(borrower, sink, Money.ofSek(4_000), "spend-all");

        clock.advance(Duration.ofHours(24 * 3 + 25));
        var result = loans.collectDue().getFirst();
        assertThat(result.defaulted()).isTrue();
        assertThat(result.loan().status()).isEqualTo(Loan.Status.DEFAULTED);
        assertThat(rep(borrower)).isEqualTo(-3 * 3 - 20);
        loans.collectDue();
        assertThat(rep(borrower)).as("penalties are applied once").isEqualTo(-29);

        core.economy().adminGrant(null, borrower, Money.ofSek(1_000));
        loans.collectDue();
        assertThat(loans.find(loan.id()).orElseThrow().repaid()).isEqualTo(Money.ofSek(1_000));
        core.economy().adminGrant(null, borrower, Money.ofSek(5_000));
        assertThat(loans.collectDue().getFirst().repaid()).isTrue();
        assertLedgerHealthy();
    }

    @Test
    void companyDefaultTriggersOrderlyBankruptcy() {
        UUID owner = player("Owner");
        UUID worker = player("Worker");
        UUID contractor = player("Contractor");
        UUID lender2 = player("Lender2");
        grant(owner, 30_000);
        grant(lender2, 100_000);
        Company company = core.companies().found(owner, "Olycka AB");
        // Company buys a property, posts a contract, and hires a worker who is owed wages.
        core.companies().deposit(owner, company.id(), Money.ofSek(20_000));
        Property mine = core.properties().create("Gruvan", Property.Type.MINE, Region.of("world", 0, 0, 0, 20, 20, 20), Money.ofSek(10_000));
        core.properties().buy(owner, mine.id(), company.id(), null);
        Contract contract = core.contracts().create(owner, company.id(),
                new ContractService.CreateRequest(Contract.Type.SERVICE, "Bygg ett lager", null, null, Money.ofSek(5_000), 48, null, 1));
        var position = core.jobs().createPosition(owner, company.id(), "MINER", "Miner", 1, Money.ofSek(12_000), 1);
        core.jobs().accept(owner, core.jobs().apply(worker, position.id(), null).id());

        // Two loans: 6 000 and 3 000 due in one installment after a day.
        Loan a = loans.offer(lender, LoanService.Party.player(lender), LoanService.Party.company(company.id()), Money.ofSek(6_000), 0, 1, 24);
        Loan b = loans.offer(lender2, LoanService.Party.player(lender2), LoanService.Party.company(company.id()), Money.ofSek(3_000), 0, 1, 24);
        loans.accept(owner, a.id());
        loans.accept(owner, b.id());
        // Company balance: 20 000 - 10 000 - 5 000 - 100 fee + 9 000 = 13 900. Owner drains it (no arrears yet).
        core.companies().withdraw(owner, company.id(), Money.ofSek(13_900));
        // Worker earns 12 000/h × 5 min = 1 000 unpaid wages.
        var employee = core.companies().employees(company.id()).stream().filter(e -> e.playerUuid().equals(worker)).findFirst().orElseThrow();
        core.payroll().recordWork(employee.id(), clock.instant().minus(Duration.ofMinutes(5)), 5);

        clock.advance(Duration.ofHours(24 * 4));
        var collection = loans.collectDue();
        assertThat(collection).anyMatch(LoanService.Collection::defaulted);

        assertThat(core.companies().find(company.id()).orElseThrow().status()).isEqualTo(Company.Status.BANKRUPT);
        assertThat(core.properties().find(mine.id()).orElseThrow().status()).isEqualTo(Property.Status.AVAILABLE);
        assertThat(core.contracts().find(contract.id()).orElseThrow().status()).isEqualTo(Contract.Status.CANCELLED);
        assertThat(core.companies().employees(company.id())).isEmpty();
        // Contract escrow (5 000) came back: wages (1 000) first, then 4 000 split 2:1 between the lenders.
        assertThat(balance(worker)).isEqualTo(Money.ofSek(2_000));
        assertThat(loans.find(a.id()).orElseThrow().status()).isEqualTo(Loan.Status.SETTLED_IN_BANKRUPTCY);
        assertThat(loans.find(a.id()).orElseThrow().repaid()).isEqualTo(Money.ofOre(266_666));
        assertThat(loans.find(b.id()).orElseThrow().repaid()).isEqualTo(Money.ofOre(133_333));
        assertThat(core.companies().balance(company.id())).isEqualTo(Money.ZERO);
        assertThat(rep(owner)).isEqualTo(-15);
        assertLedgerHealthy();
    }

    @Test
    void voluntaryBankruptcyRequiresDebtsAndPaysResidualToOwner() {
        UUID owner = player("Owner");
        grant(owner, 30_000);
        Company company = core.companies().found(owner, "Frivillig AB");
        assertDomainError(() -> core.bankruptcy().declare(company.id(), BankruptcyService.Reason.VOLUNTARY, owner), "bankruptcy.no_debts");
        Loan loan = loans.offer(lender, LoanService.Party.player(lender), LoanService.Party.company(company.id()), Money.ofSek(1_000), 10, 2, 24);
        loans.accept(owner, loan.id());
        core.companies().deposit(owner, company.id(), Money.ofSek(5_000));
        assertDomainError(() -> core.bankruptcy().declare(company.id(), BankruptcyService.Reason.VOLUNTARY, lender), "company.not_member");

        Money ownerBefore = balance(owner);
        var result = core.bankruptcy().declare(company.id(), BankruptcyService.Reason.VOLUNTARY, owner);
        assertThat(result.paidCreditors()).isEqualTo(Money.ofSek(1_100));
        assertThat(result.unpaidDebt()).isEqualTo(Money.ZERO);
        assertThat(balance(owner)).isEqualTo(ownerBefore.plus(Money.ofSek(6_000 - 1_100)));
        assertDomainError(() -> core.bankruptcy().declare(company.id(), BankruptcyService.Reason.VOLUNTARY, owner), "company.not_active");
        assertLedgerHealthy();
    }
}
