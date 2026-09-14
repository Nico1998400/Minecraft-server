package se.nordia.swedencore.shares;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.testing.CoreTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ShareServiceTest extends CoreTest {

    private ShareService shares;
    private UUID owner;
    private UUID investor;
    private Company company;

    @BeforeEach
    void setUp() {
        shares = core.shares();
        owner = player("Owner");
        investor = player("Investor");
        grant(owner, 50_000);
        grant(investor, 50_000);
        company = core.companies().found(owner, "Aktiebolaget");
    }

    private long held(UUID player) {
        return shares.portfolio(player).stream().filter(p -> p.companyId() == company.id()).mapToLong(ShareService.Position::total).sum();
    }

    @Test
    void foundersStartWithAllSharesAndTradesMoveMoneyWithFee() {
        assertThat(held(owner)).isEqualTo(1_000);
        Money supply = core.economy().moneySupply();
        var offer = shares.offer(owner, company.id(), false, 100, Money.ofSek(10), null, 24);
        // Listed shares are escrowed: they cannot be listed twice.
        assertDomainError(() -> shares.offer(owner, company.id(), false, 901, Money.ofSek(10), null, 24), "shares.insufficient");
        assertThat(held(owner)).isEqualTo(1_000);

        Money ownerBefore = balance(owner);
        var trade = shares.buy(investor, offer.id(), 40);
        assertThat(trade.total()).isEqualTo(Money.ofSek(400));
        assertThat(trade.fee()).isEqualTo(Money.ofSek(4));
        assertThat(balance(owner)).isEqualTo(ownerBefore.plus(Money.ofSek(396)));
        assertThat(held(investor)).isEqualTo(40);
        assertThat(held(owner)).isEqualTo(960);
        assertThat(core.economy().moneySupply()).isEqualTo(supply.minus(Money.ofSek(4)));

        assertDomainError(() -> shares.buy(investor, offer.id(), 61), "shares.not_enough_offered");
        assertDomainError(() -> shares.buy(owner, offer.id(), 1), "shares.own_offer");
        assertDomainError(() -> shares.buy(investor, offer.id(), 0), "shares.invalid_quantity");

        var valuation = shares.valuation(company.id());
        assertThat(valuation.totalShares()).isEqualTo(1_000);
        assertThat(valuation.lastPrice()).isEqualTo(Money.ofSek(10));
        assertThat(valuation.marketCap()).isEqualTo(Money.ofSek(10_000));
        assertThat(valuation.volume30d()).isEqualTo(40);

        shares.cancel(owner, offer.id());
        assertThat(held(owner)).isEqualTo(960);
        assertDomainError(() -> shares.buy(investor, offer.id(), 1), "shares.offer_not_open");
        assertLedgerHealthy();
    }

    @Test
    void offerRulesPrivateBuyersExpiryAndOverflow() {
        UUID stranger = player("Stranger");
        grant(stranger, 1_000);
        var privateOffer = shares.offer(owner, company.id(), false, 10, Money.ofSek(5), investor, 24);
        assertDomainError(() -> shares.buy(stranger, privateOffer.id(), 1), "shares.offer_private");
        assertDomainError(() -> shares.cancel(investor, privateOffer.id()), "shares.not_seller");
        assertThat(shares.openOffers(stranger, company.id(), 10)).isEmpty();
        assertThat(shares.openOffers(investor, company.id(), 10)).hasSize(1);

        assertDomainError(() -> shares.offer(owner, company.id(), false, 10, Money.ofOre(Long.MAX_VALUE / 2), null, 24), "economy.amount_too_large");
        assertDomainError(() -> shares.offer(owner, company.id(), false, 10, Money.ZERO, null, 24), "economy.invalid_amount");
        assertDomainError(() -> shares.offer(investor, company.id(), false, 1, Money.ofSek(1), null, 24), "shares.insufficient");
        assertDomainError(() -> shares.offer(owner, company.id(), false, 1, Money.ofSek(1), owner, 24), "shares.own_offer");

        clock.advance(Duration.ofHours(25));
        assertDomainError(() -> shares.buy(investor, privateOffer.id(), 1), "shares.offer_not_open");
        assertThat(shares.expireDue()).isEqualTo(1);
        assertThat(held(owner)).isEqualTo(1_000);
        assertThat(shares.findOffer(privateOffer.id()).orElseThrow().status()).isEqualTo("EXPIRED");
    }

    @Test
    void treasurySharesRaiseCapitalForTheCompany() {
        assertDomainError(() -> shares.issue(investor, company.id(), 500), "company.not_member");
        assertDomainError(() -> shares.issue(owner, company.id(), 1_000_000), "shares.too_many");
        assertThat(shares.issue(owner, company.id(), 500)).isEqualTo(1_500);

        var offer = shares.offer(owner, company.id(), true, 500, Money.ofSek(20), null, 48);
        assertDomainError(() -> shares.offer(investor, company.id(), true, 1, Money.ofSek(20), null, 48), "company.not_member");
        Money companyBefore = core.companies().balance(company.id());
        shares.buy(investor, offer.id(), 250);
        assertThat(core.companies().balance(company.id())).isEqualTo(companyBefore.plus(Money.ofSek(5_000 - 50)));
        // The owner can buy treasury shares too: it is a capital injection at the offered price.
        shares.buy(owner, offer.id(), 10);

        var valuation = shares.valuation(company.id());
        assertThat(valuation.totalShares()).isEqualTo(1_500);
        assertThat(valuation.treasuryShares()).isEqualTo(240);
        assertThat(valuation.outstanding()).isEqualTo(1_260);
        assertLedgerHealthy();
    }

    @Test
    void dividendsArePaidPerShareOutsideTheTreasury() {
        shares.issue(owner, company.id(), 1_000); // treasury, excluded
        var offer = shares.offer(owner, company.id(), false, 250, Money.ofSek(1), null, 24);
        shares.buy(investor, offer.id(), 200); // owner 750 free + 50 listed, investor 200
        core.companies().deposit(owner, company.id(), Money.ofSek(5_000));

        assertDomainError(() -> shares.declareDividend(investor, company.id(), Money.ofSek(100)), "company.not_member");
        assertDomainError(() -> shares.declareDividend(owner, company.id(), Money.ofOre(999)), "shares.dividend_too_small");
        assertDomainError(() -> shares.declareDividend(owner, company.id(), Money.ofSek(6_000)), "economy.insufficient_funds");

        Money ownerBefore = balance(owner);
        Money investorBefore = balance(investor);
        var dividend = shares.declareDividend(owner, company.id(), Money.ofOre(100_050));
        assertThat(dividend.perShare()).isEqualTo(Money.ofOre(100));
        assertThat(dividend.shares()).isEqualTo(1_000);
        assertThat(dividend.total()).isEqualTo(Money.ofSek(1_000));
        assertThat(balance(investor)).isEqualTo(investorBefore.plus(Money.ofSek(200)));
        assertThat(balance(owner)).isEqualTo(ownerBefore.plus(Money.ofSek(800)));
        assertThat(core.companies().balance(company.id())).isEqualTo(Money.ofSek(4_000));
        assertLedgerHealthy();
    }

    @Test
    void outsideShareholdersBlockWithdrawalsAndShareInDissolution() {
        core.companies().deposit(owner, company.id(), Money.ofSek(10_000));
        core.companies().withdraw(owner, company.id(), Money.ofSek(1_000));
        var offer = shares.offer(owner, company.id(), false, 250, Money.ofSek(1), null, 24);
        shares.buy(investor, offer.id(), 250);
        assertDomainError(() -> core.companies().withdraw(owner, company.id(), Money.ofSek(1)), "company.withdraw_blocked_by_shareholders");

        // Investor lists part of their stake; it still counts for the payout and is returned.
        var resale = shares.offer(investor, company.id(), false, 50, Money.ofSek(2), null, 24);
        Money ownerBefore = balance(owner);
        Money investorBefore = balance(investor);
        assertThat(core.companies().dissolve(owner, company.id())).isEqualTo(Money.ofSek(9_000));
        assertThat(balance(investor)).isEqualTo(investorBefore.plus(Money.ofSek(2_250)));
        assertThat(balance(owner)).isEqualTo(ownerBefore.plus(Money.ofSek(6_750)));
        assertThat(shares.findOffer(resale.id()).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(shares.portfolio(investor)).isEmpty();
        assertLedgerHealthy();
    }

    @Test
    void marketOverviewAndProfileShowPublicActivity() {
        assertThat(shares.market(10)).isEmpty();
        Company other = core.companies().found(investor, "Konkurrenten");
        shares.offer(owner, company.id(), false, 30, Money.ofSek(12), null, 24);
        shares.offer(owner, company.id(), false, 20, Money.ofSek(12), null, 24);
        shares.offer(owner, company.id(), false, 10, Money.ofSek(9), investor, 24); // private: not an ask
        var cheap = shares.offer(investor, other.id(), false, 5, Money.ofSek(3), null, 24);
        shares.buy(owner, cheap.id(), 5);

        var market = shares.market(10);
        assertThat(market).extracting(ShareService.Listing::companyName).containsExactly("Konkurrenten", "Aktiebolaget");
        var listing = market.get(1);
        assertThat(listing.bestAsk()).isEqualTo(Money.ofSek(12));
        assertThat(listing.askQuantity()).isEqualTo(50);
        assertThat(listing.lastPrice()).isNull();
        assertThat(market.getFirst().volume30d()).isEqualTo(5);
        assertThat(market.getFirst().bestAsk()).isNull();

        assertThat(core.profiles().profile(owner).shareholdings()).containsExactly("Aktiebolaget", "Konkurrenten");
        clock.advance(Duration.ofDays(31));
        assertThat(shares.market(10)).isEmpty();
    }

    @Test
    void solventBankruptcyPaysTheResidualToShareholders() {
        UUID lender = player("Lender");
        grant(lender, 5_000);
        var loan = core.loans().offer(lender, se.nordia.swedencore.finance.LoanService.Party.player(lender),
                se.nordia.swedencore.finance.LoanService.Party.company(company.id()), Money.ofSek(1_000), 10, 2, 24);
        core.loans().accept(owner, loan.id());
        core.companies().deposit(owner, company.id(), Money.ofSek(5_000));
        var offer = shares.offer(owner, company.id(), false, 100, Money.ofSek(1), null, 24);
        shares.buy(investor, offer.id(), 100);

        Money ownerBefore = balance(owner);
        Money investorBefore = balance(investor);
        var result = core.bankruptcy().declare(company.id(), se.nordia.swedencore.finance.BankruptcyService.Reason.VOLUNTARY, owner);
        assertThat(result.paidCreditors()).isEqualTo(Money.ofSek(1_100));
        assertThat(balance(investor)).isEqualTo(investorBefore.plus(Money.ofSek(490)));
        assertThat(balance(owner)).isEqualTo(ownerBefore.plus(Money.ofSek(4_410)));
        assertThat(shares.portfolio(investor)).isEmpty();
        assertLedgerHealthy();
    }

    @Test
    void ownerAloneCanStillWithdrawAfterBuyingBackEveryShare() {
        var offer = shares.offer(owner, company.id(), false, 10, Money.ofSek(1), null, 24);
        shares.buy(investor, offer.id(), 10);
        var back = shares.offer(investor, company.id(), false, 10, Money.ofSek(1), owner, 24);
        shares.buy(owner, back.id(), 10);
        core.companies().deposit(owner, company.id(), Money.ofSek(100));
        core.companies().withdraw(owner, company.id(), Money.ofSek(100));
        assertThat(held(owner)).isEqualTo(1_000);
    }

    @Test
    void concurrentBuyersCannotOversellAnOffer() throws Exception {
        var offer = shares.offer(owner, company.id(), false, 10, Money.ofSek(1), null, 24);
        int threads = 8;
        List<UUID> buyers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID b = player("Buyer" + i);
            grant(b, 100);
            buyers.add(b);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (UUID b : buyers) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    shares.buy(b, offer.id(), 3);
                    return true;
                } catch (DomainException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> r : results) {
            succeeded += r.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(succeeded).isEqualTo(3);
        assertThat(shares.findOffer(offer.id()).orElseThrow().remaining()).isEqualTo(1);
        long sold = buyers.stream().mapToLong(this::held).sum();
        assertThat(sold).isEqualTo(9);
        assertThat(shares.valuation(company.id()).totalShares()).isEqualTo(1_000);
        assertLedgerHealthy();
    }
}
