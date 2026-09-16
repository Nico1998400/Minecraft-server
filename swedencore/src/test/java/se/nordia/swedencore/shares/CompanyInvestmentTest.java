package se.nordia.swedencore.shares;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.testing.CoreTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyInvestmentTest extends CoreTest {

    private ShareService shares;
    private UUID owner;
    private UUID investor;
    private Company buyer;
    private Company target;

    @BeforeEach
    void setUp() {
        shares = core.shares();
        owner = player("Owner");
        investor = player("Investor");
        grant(owner, 100_000);
        grant(investor, 50_000);
        buyer = core.companies().found(owner, "Investeraren");
        target = core.companies().found(investor, "Malmen");
        core.companies().deposit(owner, buyer.id(), Money.ofSek(20_000));
        core.companies().deposit(investor, target.id(), Money.ofSek(10_000));
    }

    private long companyHeld() {
        return shares.companyPortfolio(buyer.id()).stream()
                .filter(p -> p.companyId() == target.id()).mapToLong(ShareService.Position::total).sum();
    }

    @Test
    void whollyOwnedCompanyBuysSharesWithCompanyMoney() {
        var offer = shares.offer(investor, target.id(), false, 100, Money.ofSek(10), null, 24);
        Money buyerBefore = core.companies().balance(buyer.id());
        Money sellerBefore = balance(investor);
        var trade = shares.buyForCompany(owner, buyer.id(), offer.id(), 40);
        assertThat(trade.total()).isEqualTo(Money.ofSek(400));
        assertThat(core.companies().balance(buyer.id())).isEqualTo(buyerBefore.minus(Money.ofSek(400)));
        assertThat(balance(investor)).isEqualTo(sellerBefore.plus(Money.ofSek(396)));
        assertThat(companyHeld()).isEqualTo(40);
        assertThat(shares.portfolio(owner).stream().noneMatch(p -> p.companyId() == target.id())).isTrue();
        assertDomainError(() -> shares.buyForCompany(investor, buyer.id(), offer.id(), 1), "company.not_member");
        assertDomainError(() -> shares.buyForCompany(owner, buyer.id(), offer.id(), 0), "shares.invalid_quantity");
        assertLedgerHealthy();
    }

    @Test
    void companyCannotBuyItsOwnSharesAndPrivateOffersStayPersonal() {
        var own = shares.offer(investor, target.id(), false, 10, Money.ofSek(1), null, 24);
        assertDomainError(() -> shares.buyForCompany(investor, target.id(), own.id(), 1), "shares.own_company");
        var priv = shares.offer(investor, target.id(), false, 5, Money.ofSek(1), owner, 24);
        assertDomainError(() -> shares.buyForCompany(owner, buyer.id(), priv.id(), 1), "shares.offer_private");
        assertDomainError(() -> shares.bidForCompany(owner, buyer.id(), buyer.id(), 1, Money.ofSek(1), 24), "shares.own_company");
        assertLedgerHealthy();
    }

    @Test
    void outsideShareholdersCapThePriceAgainstBookValue() {
        var stake = shares.offer(owner, buyer.id(), false, 200, Money.ofSek(1), null, 24);
        shares.buy(investor, stake.id(), 200);
        // Target book = 10 000 SEK / 1 000 shares = 10 SEK; max buy = 30 SEK.
        var expensive = shares.offer(investor, target.id(), false, 10, Money.ofSek(50), null, 24);
        assertDomainError(() -> shares.buyForCompany(owner, buyer.id(), expensive.id(), 1), "shares.price_above_book");
        var cheap = shares.offer(investor, target.id(), false, 10, Money.ofSek(30), null, 24);
        shares.buyForCompany(owner, buyer.id(), cheap.id(), 10);
        assertThat(companyHeld()).isEqualTo(10);

        Company empty = core.companies().found(investor, "Skal");
        var hollow = shares.offer(investor, empty.id(), false, 10, Money.ofSek(1), null, 24);
        assertDomainError(() -> shares.buyForCompany(owner, buyer.id(), hollow.id(), 1), "shares.price_not_justified");

        // Selling the holding below book / 3 is blocked the same way.
        assertDomainError(() -> shares.offerFromCompany(owner, buyer.id(), target.id(), 5, Money.ofOre(1), null, 24),
                "shares.price_below_book");
        var listing = shares.offerFromCompany(owner, buyer.id(), target.id(), 5, Money.ofSek(10), null, 24);
        assertThat(listing.sellerType()).isEqualTo(ShareService.HolderType.COMPANY);
        shares.cancel(owner, listing.id());
        assertThat(companyHeld()).isEqualTo(10);
        assertLedgerHealthy();
    }

    @Test
    void whollyOwnedBuyerMayOverpayBecauseTheOwnerCouldWithdrawAnyway() {
        var offer = shares.offer(investor, target.id(), false, 10, Money.ofSek(50), null, 24);
        shares.buyForCompany(owner, buyer.id(), offer.id(), 10);
        assertThat(companyHeld()).isEqualTo(10);
        assertLedgerHealthy();
    }

    @Test
    void companyHoldingsReceiveDividendsAndBlockTargetWithdrawals() {
        var offer = shares.offer(investor, target.id(), false, 250, Money.ofSek(1), null, 24);
        shares.buyForCompany(owner, buyer.id(), offer.id(), 250);
        assertDomainError(() -> core.companies().withdraw(investor, target.id(), Money.ofSek(1)),
                "company.withdraw_blocked_by_shareholders");

        Money buyerBefore = core.companies().balance(buyer.id());
        Money investorBefore = balance(investor);
        var dividend = shares.declareDividend(investor, target.id(), Money.ofSek(1_000));
        assertThat(dividend.recipients()).isEqualTo(2);
        assertThat(core.companies().balance(buyer.id())).isEqualTo(buyerBefore.plus(Money.ofSek(250)));
        assertThat(balance(investor)).isEqualTo(investorBefore.plus(Money.ofSek(750)));
        assertLedgerHealthy();
    }

    @Test
    void dissolvingACompanySplitsItsInvestmentsAndPaysCompanyShareholders() {
        var offer = shares.offer(investor, target.id(), false, 100, Money.ofSek(1), null, 24);
        shares.buyForCompany(owner, buyer.id(), offer.id(), 100);
        core.companies().deposit(investor, target.id(), Money.ofSek(1_000));
        assertThat(core.companies().dissolve(investor, target.id())).isEqualTo(Money.ofSek(11_000));
        assertThat(core.companies().balance(buyer.id())).isEqualTo(Money.ofSek(20_000 - 100 + 1_100));
        assertThat(shares.companyPortfolio(buyer.id())).isEmpty();
        assertLedgerHealthy();
    }

    @Test
    void closingTheHoldingCompanyDistributesTheStakeProRata() {
        var stake = shares.offer(owner, buyer.id(), false, 250, Money.ofSek(1), null, 24);
        shares.buy(investor, stake.id(), 250);
        var offer = shares.offer(investor, target.id(), false, 100, Money.ofSek(1), null, 24);
        shares.buyForCompany(owner, buyer.id(), offer.id(), 100);
        core.companies().dissolve(owner, buyer.id());
        long ownerTarget = shares.portfolio(owner).stream().filter(p -> p.companyId() == target.id())
                .mapToLong(ShareService.Position::total).sum();
        long investorTarget = shares.portfolio(investor).stream().filter(p -> p.companyId() == target.id())
                .mapToLong(ShareService.Position::total).sum();
        assertThat(ownerTarget).isEqualTo(75);
        assertThat(investorTarget).isEqualTo(1_000 - 100 + 25);
        assertThat(shares.companyPortfolio(buyer.id())).isEmpty();
        assertLedgerHealthy();
    }

    @Test
    void companyBidsEscrowCompanyMoneyAndRefundOnCancel() {
        Money before = core.companies().balance(buyer.id());
        var bid = shares.bidForCompany(owner, buyer.id(), target.id(), 50, Money.ofSek(8), 24);
        assertThat(bid.buyerCompanyId()).isEqualTo(buyer.id());
        assertThat(core.companies().balance(buyer.id())).isEqualTo(before.minus(Money.ofSek(400)));
        Money sellerBefore = balance(investor);
        shares.sellToBid(investor, bid.id(), 20, false);
        assertThat(companyHeld()).isEqualTo(20);
        assertThat(balance(investor)).isEqualTo(sellerBefore.plus(Money.ofOre(15_840)));
        assertDomainError(() -> shares.sellToBidFromCompany(owner, buyer.id(), bid.id(), 1), "shares.own_offer");
        shares.cancelBid(owner, bid.id());
        assertThat(core.companies().balance(buyer.id())).isEqualTo(before.minus(Money.ofSek(160)));
        assertLedgerHealthy();
    }

    @Test
    void investmentsAppearOnTheBalanceSheetAtOperatingBook() {
        var offer = shares.offer(investor, target.id(), false, 100, Money.ofSek(10), null, 24);
        shares.buyForCompany(owner, buyer.id(), offer.id(), 100);
        var sheet = core.companyFinance().report(owner, buyer.id(), 30).balance();
        // Target operating equity 10 000 SEK; 100 / 1 000 = 1 000 SEK of book.
        assertThat(sheet.investments()).isEqualTo(Money.ofSek(1_000));
        assertThat(sheet.equity()).isEqualTo(sheet.cash().plus(sheet.investments()));
        assertLedgerHealthy();
    }

    @Test
    void wageArrearsBlockCompanyPurchases() {
        core.companies().withdraw(owner, buyer.id(), Money.ofSek(20_000));
        UUID worker = player("Worker");
        var position = core.jobs().createPosition(owner, buyer.id(), "GENERAL_WORKER", "Hand", 1, Money.ofSek(100), 1);
        core.jobs().accept(owner, core.jobs().apply(worker, position.id(), null).id());
        var employee = core.companies().employees(buyer.id()).stream().filter(e -> e.playerUuid().equals(worker)).findFirst()
                .orElseThrow();
        core.payroll().recordWork(employee.id(), clock.instant().minus(java.time.Duration.ofMinutes(10)), 10);
        var offer = shares.offer(investor, target.id(), false, 1, Money.ofSek(1), null, 24);
        assertDomainError(() -> shares.buyForCompany(owner, buyer.id(), offer.id(), 1), "company.withdraw_blocked_by_arrears");
        assertDomainError(() -> shares.bidForCompany(owner, buyer.id(), target.id(), 1, Money.ofSek(1), 24),
                "company.withdraw_blocked_by_arrears");
    }
}
