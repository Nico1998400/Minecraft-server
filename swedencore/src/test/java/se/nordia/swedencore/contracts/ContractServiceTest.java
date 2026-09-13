package se.nordia.swedencore.contracts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Account;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.reputation.ReputationService;
import se.nordia.swedencore.skills.Skill;
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

class ContractServiceTest extends CoreTest {

    private UUID issuer;
    private UUID contractor;
    private ContractService contracts;

    @BeforeEach
    void setUp() {
        contracts = core.contracts();
        issuer = player("Issuer");
        contractor = player("Contractor");
        grant(issuer, 200_000);
    }

    private static ContractService.CreateRequest delivery(String material, int quantity, long rewardOre, Skill skill, int level) {
        return new ContractService.CreateRequest(Contract.Type.ITEM_DELIVERY, "Leverans av varor", material, quantity,
                Money.ofOre(rewardOre), 24, skill, level);
    }

    private static ContractService.CreateRequest service(long rewardSek) {
        return new ContractService.CreateRequest(Contract.Type.SERVICE, "Bygg ett hus", null, null, Money.ofSek(rewardSek), 48, null, 1);
    }

    private static List<ItemStashService.StashItem> items(String material, int... amounts) {
        List<ItemStashService.StashItem> list = new ArrayList<>();
        for (int amount : amounts) {
            list.add(new ItemStashService.StashItem(material, amount, new byte[]{1, 2, 3}));
        }
        return list;
    }

    private Money escrow(long contractId) {
        return core.database().inTransaction(tx -> core.economy().findAccount(tx, AccountOwner.contract(contractId), Account.ESCROW))
                .map(Account::balance).orElseThrow();
    }

    private int rep(UUID player) {
        return core.reputation().score(ReputationService.Subject.player(player));
    }

    @Test
    void creationEscrowsRewardAndBurnsFee() {
        Contract contract = contracts.create(issuer, null, delivery("IRON_ORE", 100, Money.ofSek(10_000).ore(), null, 1));
        assertThat(contract.status()).isEqualTo(Contract.Status.OPEN);
        assertThat(escrow(contract.id())).isEqualTo(Money.ofSek(10_000));
        assertThat(balance(issuer)).isEqualTo(Money.ofSek(201_000 - 10_000 - 200));
        assertLedgerHealthy();
    }

    @Test
    void cannotCreateWithoutFundsOrWithInvalidInput() {
        UUID poor = player("Poor");
        assertDomainError(() -> contracts.create(poor, null, service(5_000)), "economy.insufficient_funds");
        assertThat(contracts.involving(poor)).isEmpty();
        assertDomainError(() -> contracts.create(issuer, null, delivery("iron ore; drop", 1, 100, null, 1)), "contract.invalid_material");
        assertDomainError(() -> contracts.create(issuer, null, delivery("IRON_ORE", 0, 100, null, 1)), "contract.invalid_quantity");
        assertDomainError(() -> contracts.create(issuer, null, delivery("IRON_ORE", 5, -100, null, 1)), "economy.invalid_amount");
        assertDomainError(() -> contracts.create(issuer, null,
                new ContractService.CreateRequest(Contract.Type.SERVICE, "Ok title", null, null, Money.ofSek(1), 0, null, 1)), "contract.invalid_duration");
        assertDomainError(() -> contracts.create(issuer, null, delivery("IRON_ORE", 5, 100, null, 5)), "job.invalid_level");
    }

    @Test
    void proportionalDeliveryPaysExactlyTheRewardInTotal() {
        Contract contract = contracts.create(issuer, null, delivery("IRON_ORE", 3, 100, null, 1));
        contracts.accept(contractor, contract.id());
        var first = contracts.deliver(contractor, contract.id(), items("IRON_ORE", 1));
        var second = contracts.deliver(contractor, contract.id(), items("IRON_ORE", 1));
        var third = contracts.deliver(contractor, contract.id(), items("IRON_ORE", 1));
        assertThat(first.payout()).isEqualTo(Money.ofOre(33));
        assertThat(second.payout()).isEqualTo(Money.ofOre(33));
        assertThat(third.payout()).isEqualTo(Money.ofOre(34));
        assertThat(third.completed()).isTrue();
        assertThat(escrow(contract.id())).isEqualTo(Money.ZERO);
        assertThat(balance(contractor)).isEqualTo(Money.ofSek(1_000).plus(Money.ofOre(100)));
        assertThat(core.stash().summary(ItemStashService.Owner.player(issuer)))
                .singleElement().satisfies(s -> assertThat(s.total()).isEqualTo(3));
        assertDomainError(() -> contracts.deliver(contractor, contract.id(), items("IRON_ORE", 1)), "contract.not_contractor");
        assertLedgerHealthy();
    }

    @Test
    void deliveryRulesAreEnforced() {
        Contract contract = contracts.create(issuer, null, delivery("DIAMOND", 10, Money.ofSek(1_000).ore(), null, 1));
        assertDomainError(() -> contracts.deliver(contractor, contract.id(), items("DIAMOND", 1)), "contract.not_contractor");
        contracts.accept(contractor, contract.id());
        UUID thief = player("Thief");
        assertDomainError(() -> contracts.deliver(thief, contract.id(), items("DIAMOND", 1)), "contract.not_contractor");
        assertDomainError(() -> contracts.deliver(contractor, contract.id(), items("COAL", 5)), "contract.wrong_material");
        assertDomainError(() -> contracts.deliver(contractor, contract.id(), items("DIAMOND", 64)), "contract.too_many_items");
        assertDomainError(() -> contracts.deliver(contractor, contract.id(), List.of()), "contract.nothing_to_deliver");
        assertDomainError(() -> contracts.complete(issuer, contract.id()), "contract.wrong_type");
        assertThat(escrow(contract.id())).isEqualTo(Money.ofSek(1_000));
    }

    @Test
    void completionGrantsXpAndReputationOncePerPairPerDay() {
        long reward = Money.ofSek(10_000).ore();
        Contract first = contracts.create(issuer, null, delivery("IRON_ORE", 2, reward, Skill.MINING, 1));
        assertThat(first.xpReward()).isEqualTo(500);
        contracts.accept(contractor, first.id());
        contracts.deliver(contractor, first.id(), items("IRON_ORE", 2));
        assertThat(core.skills().load(contractor).get(Skill.MINING).xp()).isEqualTo(500);
        assertThat(rep(contractor)).isEqualTo(2);
        assertThat(rep(issuer)).isEqualTo(1);

        Contract second = contracts.create(issuer, null, delivery("IRON_ORE", 1, reward, Skill.MINING, 1));
        contracts.accept(contractor, second.id());
        contracts.deliver(contractor, second.id(), items("IRON_ORE", 1));
        assertThat(rep(contractor)).as("same pair, same day").isEqualTo(2);

        clock.advance(Duration.ofDays(1));
        Contract third = contracts.create(issuer, null, delivery("IRON_ORE", 1, reward, Skill.MINING, 1));
        contracts.accept(contractor, third.id());
        contracts.deliver(contractor, third.id(), items("IRON_ORE", 1));
        assertThat(rep(contractor)).isEqualTo(4);
    }

    @Test
    void smallContractsGiveNoReputation() {
        Contract contract = contracts.create(issuer, null, service(100));
        contracts.accept(contractor, contract.id());
        contracts.complete(issuer, contract.id());
        assertThat(rep(contractor)).isZero();
    }

    @Test
    void serviceContractLifecycle() {
        Contract contract = contracts.create(issuer, null, service(50_000));
        assertDomainError(() -> contracts.accept(issuer, contract.id()), "contract.own_contract");
        contracts.accept(contractor, contract.id());
        UUID other = player("Other");
        assertDomainError(() -> contracts.accept(other, contract.id()), "contract.not_open");
        assertDomainError(() -> contracts.complete(contractor, contract.id()), "contract.not_issuer");
        assertDomainError(() -> contracts.cancel(issuer, contract.id()), "contract.cannot_cancel");
        Contract done = contracts.complete(issuer, contract.id());
        assertThat(done.status()).isEqualTo(Contract.Status.COMPLETED);
        assertThat(balance(contractor)).isEqualTo(Money.ofSek(51_000));
        assertDomainError(() -> contracts.complete(issuer, contract.id()), "contract.not_in_progress");
        assertLedgerHealthy();
    }

    @Test
    void cancelRefundsEscrowButNotFee() {
        Contract contract = contracts.create(issuer, null, service(10_000));
        assertDomainError(() -> contracts.cancel(contractor, contract.id()), "contract.not_issuer");
        contracts.cancel(issuer, contract.id());
        assertThat(balance(issuer)).isEqualTo(Money.ofSek(201_000 - 200));
        assertThat(escrow(contract.id())).isEqualTo(Money.ZERO);
        assertDomainError(() -> contracts.cancel(issuer, contract.id()), "contract.cannot_cancel");
        assertDomainError(() -> contracts.accept(contractor, contract.id()), "contract.not_open");
        assertLedgerHealthy();
    }

    @Test
    void abandonReopensWithPenaltyAndKeepsPartialPayouts() {
        Contract contract = contracts.create(issuer, null, delivery("OAK_LOG", 4, Money.ofSek(400).ore(), null, 1));
        contracts.accept(contractor, contract.id());
        contracts.deliver(contractor, contract.id(), items("OAK_LOG", 1));
        Contract reopened = contracts.abandon(contractor, contract.id());
        assertThat(reopened.status()).isEqualTo(Contract.Status.OPEN);
        assertThat(rep(contractor)).isEqualTo(-3);
        assertThat(escrow(contract.id())).isEqualTo(Money.ofSek(300));

        UUID second = player("Second");
        contracts.accept(second, contract.id());
        var result = contracts.deliver(second, contract.id(), items("OAK_LOG", 3));
        assertThat(result.completed()).isTrue();
        assertThat(balance(second)).isEqualTo(Money.ofSek(1_300));
        assertThat(balance(contractor)).isEqualTo(Money.ofSek(1_100));
        assertLedgerHealthy();
    }

    @Test
    void expiryRefundsRemainingEscrowAndPenalisesContractor() {
        Contract contract = contracts.create(issuer, null, delivery("WHEAT", 10, Money.ofSek(1_000).ore(), null, 1));
        contracts.accept(contractor, contract.id());
        contracts.deliver(contractor, contract.id(), items("WHEAT", 5));
        clock.advance(Duration.ofHours(25));
        assertDomainError(() -> contracts.deliver(contractor, contract.id(), items("WHEAT", 5)), "contract.expired");
        List<Contract> expired = contracts.expireDue();
        assertThat(expired).extracting(Contract::id).containsExactly(contract.id());
        assertThat(contracts.find(contract.id()).orElseThrow().status()).isEqualTo(Contract.Status.EXPIRED);
        assertThat(escrow(contract.id())).isEqualTo(Money.ZERO);
        assertThat(balance(issuer)).isEqualTo(Money.ofSek(201_000 - 1_000 - 20 + 500));
        assertThat(rep(contractor)).isEqualTo(-2);
        assertThat(contracts.expireDue()).isEmpty();
        assertLedgerHealthy();
    }

    @Test
    void companyContractsRequireOwnerAndBlockDissolution() {
        Company company = core.companies().found(issuer, "Kontrakt AB");
        core.companies().deposit(issuer, company.id(), Money.ofSek(50_000));
        UUID manager = player("Manager");
        var position = core.jobs().createPosition(issuer, company.id(), "GENERAL_WORKER", "Chef", 1, Money.ZERO, 1);
        core.jobs().accept(issuer, core.jobs().apply(manager, position.id(), null).id());
        core.companies().setRole(issuer, company.id(), manager, CompanyRole.MANAGER);

        assertDomainError(() -> contracts.create(manager, company.id(), service(1_000)), "company.no_permission");
        Contract contract = contracts.create(issuer, company.id(), service(10_000));
        assertThat(core.companies().balance(company.id())).isEqualTo(Money.ofSek(50_000 - 10_000 - 200));
        assertDomainError(() -> contracts.accept(manager, contract.id()), "contract.own_contract");

        contracts.accept(contractor, contract.id());
        assertDomainError(() -> contracts.complete(manager, contract.id()), "company.no_permission");
        core.companies().terminate(issuer, company.id(), manager);
        assertDomainError(() -> core.companies().dissolve(issuer, company.id()), "company.dissolve_has_contracts");
        contracts.complete(issuer, contract.id());
        core.companies().dissolve(issuer, company.id());
        assertLedgerHealthy();
    }

    @Test
    void stashClaimsAreExclusive() {
        Contract contract = contracts.create(issuer, null, delivery("IRON_INGOT", 70, Money.ofSek(700).ore(), null, 1));
        contracts.accept(contractor, contract.id());
        contracts.deliver(contractor, contract.id(), items("IRON_INGOT", 64, 6));
        var owner = ItemStashService.Owner.player(issuer);
        assertDomainError(() -> core.stash().claim(contractor, owner, 10), "stash.no_permission");
        assertThat(core.stash().claim(issuer, owner, 1)).singleElement().satisfies(e -> assertThat(e.amount()).isEqualTo(64));
        assertThat(core.stash().claim(issuer, owner, 10)).singleElement().satisfies(e -> assertThat(e.amount()).isEqualTo(6));
        assertThat(core.stash().claim(issuer, owner, 10)).isEmpty();
    }

    @Test
    void concurrentAcceptsAllowOneContractor() throws Exception {
        Contract contract = contracts.create(issuer, null, service(1_000));
        List<UUID> racers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            racers.add(player("Racer" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (UUID racer : racers) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    contracts.accept(racer, contract.id());
                    return true;
                } catch (DomainException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int accepted = 0;
        for (Future<Boolean> f : futures) {
            accepted += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(accepted).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesCannotExceedQuantityOrOverpay() throws Exception {
        Contract contract = contracts.create(issuer, null, delivery("COBBLESTONE", 10, Money.ofSek(1_000).ore(), null, 1));
        contracts.accept(contractor, contract.id());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return contracts.deliver(contractor, contract.id(), items("COBBLESTONE", 3)).accepted();
                } catch (DomainException e) {
                    return 0;
                }
            }));
        }
        start.countDown();
        int total = 0;
        for (Future<Integer> f : futures) {
            total += f.get();
        }
        pool.shutdown();
        assertThat(total).isEqualTo(9);
        assertThat(balance(contractor)).isEqualTo(Money.ofSek(1_900));
        assertThat(escrow(contract.id())).isEqualTo(Money.ofSek(100));
        assertLedgerHealthy();
    }

    @Test
    void contractorLimitAndSkillRequirement() {
        Contract skilled = contracts.create(issuer, null, delivery("DIAMOND", 1, Money.ofSek(100).ore(), Skill.MINING, 20));
        assertDomainError(() -> contracts.accept(contractor, skilled.id()), "job.skill_too_low");
        for (int i = 0; i < 5; i++) {
            contracts.accept(contractor, contracts.create(issuer, null, service(100)).id());
        }
        Contract sixth = contracts.create(issuer, null, service(100));
        assertDomainError(() -> contracts.accept(contractor, sixth.id()), "contract.too_many_taken");
    }
}
