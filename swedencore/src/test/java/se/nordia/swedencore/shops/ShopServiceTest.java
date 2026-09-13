package se.nordia.swedencore.shops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.Region;
import se.nordia.swedencore.testing.CoreTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ShopServiceTest extends CoreTest {

    private static final byte[] ITEM = {9, 9, 9};

    private ShopService shops;
    private UUID owner;
    private UUID customer;
    private Property shopProperty;

    @BeforeEach
    void setUp() {
        shops = core.shops();
        owner = player("Handlare");
        customer = player("Kund");
        grant(owner, 100_000);
        shopProperty = core.properties().create("Storgatan 5", Property.Type.SHOP, Region.of("world", 0, 60, 0, 9, 70, 9), Money.ofSek(10_000));
        core.properties().buy(owner, shopProperty.id(), null, null);
    }

    private ShopListing pickaxes(Shop shop, long priceSek) {
        return shops.list(owner, shop.id(), "world", 2, 61, 2, "IRON_PICKAXE", ITEM, 1, Money.ofSek(priceSek));
    }

    @Test
    void createListAndBuy() {
        Shop shop = shops.create(owner, shopProperty.id(), "Nordhamn Tools");
        ShopListing listing = pickaxes(shop, 450);
        var receipt = shops.recordPurchase(customer, listing.id(), 2, Money.ofSek(450), UUID.randomUUID());
        assertThat(receipt.total()).isEqualTo(Money.ofSek(900));
        assertThat(receipt.items()).isEqualTo(2);
        assertThat(balance(customer)).isEqualTo(Money.ofSek(100));
        assertThat(balance(owner)).isEqualTo(Money.ofSek(101_000 - 10_000 + 900));
        assertThat(shops.salesSummary(shop.id()).revenue()).isEqualTo(Money.ofSek(900));
        assertLedgerHealthy();
    }

    @Test
    void shopRules() {
        Property house = core.properties().create("Villa", Property.Type.HOUSE, Region.of("world", 100, 60, 100, 110, 70, 110), Money.ofSek(1));
        core.properties().buy(owner, house.id(), null, null);
        assertDomainError(() -> shops.create(owner, house.id(), "Hemma butik"), "shop.not_shop_property");
        assertDomainError(() -> shops.create(customer, shopProperty.id(), "Stulen butik"), "property.not_owner");
        Shop shop = shops.create(owner, shopProperty.id(), "Nordhamn Tools");
        assertDomainError(() -> shops.create(owner, shopProperty.id(), "Andra"), "shop.already_exists");
        assertDomainError(() -> shops.list(owner, shop.id(), "world", 50, 61, 50, "IRON_PICKAXE", ITEM, 1, Money.ofSek(1)), "shop.outside_property");
        assertDomainError(() -> shops.list(customer, shop.id(), "world", 2, 61, 2, "IRON_PICKAXE", ITEM, 1, Money.ofSek(1)), "property.not_owner");
        assertDomainError(() -> shops.list(owner, shop.id(), "world", 2, 61, 2, "IRON_PICKAXE", ITEM, 0, Money.ofSek(1)), "shop.invalid_bundle");
        assertDomainError(() -> shops.list(owner, shop.id(), "world", 2, 61, 2, "IRON_PICKAXE", ITEM, 1, Money.ZERO), "economy.invalid_amount");
    }

    @Test
    void purchaseRulesAndBaitAndSwitch() {
        Shop shop = shops.create(owner, shopProperty.id(), "Nordhamn Tools");
        ShopListing listing = pickaxes(shop, 450);
        assertDomainError(() -> shops.recordPurchase(owner, listing.id(), 1, Money.ofSek(450), UUID.randomUUID()), "shop.own_shop");
        pickaxes(shop, 900);
        assertDomainError(() -> shops.recordPurchase(customer, listing.id(), 1, Money.ofSek(450), UUID.randomUUID()), "shop.price_changed");
        assertDomainError(() -> shops.recordPurchase(customer, listing.id(), 0, Money.ofSek(900), UUID.randomUUID()), "shop.invalid_quantity");
        assertDomainError(() -> shops.recordPurchase(customer, listing.id(), 2, Money.ofSek(900), UUID.randomUUID()), "economy.insufficient_funds");
        shops.setOpen(owner, shop.id(), false);
        assertDomainError(() -> shops.recordPurchase(customer, listing.id(), 1, Money.ofSek(900), UUID.randomUUID()), "shop.closed");
        assertThat(balance(customer)).isEqualTo(Money.ofSek(1_000));
    }

    @Test
    void duplicateTokenCannotChargeTwiceAndIsDetectable() {
        Shop shop = shops.create(owner, shopProperty.id(), "Nordhamn Tools");
        ShopListing listing = pickaxes(shop, 100);
        UUID token = UUID.randomUUID();
        assertThat(shops.saleRecorded(token)).isFalse();
        shops.recordPurchase(customer, listing.id(), 1, Money.ofSek(100), token);
        assertThat(shops.saleRecorded(token)).isTrue();
        // A retried purchase with the same token must not create a second sale.
        assertDomainError(() -> shops.recordPurchase(customer, listing.id(), 1, Money.ofSek(100), token), "shop.duplicate_purchase");
        assertThat(balance(customer)).isEqualTo(Money.ofSek(900));
    }

    @Test
    void sellingThePropertyClosesTheShop() {
        Shop shop = shops.create(owner, shopProperty.id(), "Nordhamn Tools");
        ShopListing listing = pickaxes(shop, 100);
        core.properties().listForSale(owner, shopProperty.id(), Money.ofSek(20_000));
        UUID newOwner = player("Ny");
        grant(newOwner, 50_000);
        core.properties().buy(newOwner, shopProperty.id(), null, null);
        assertThat(shops.find(shop.id()).orElseThrow().open()).isFalse();
        assertThat(shops.findListing(listing.id())).isEmpty();
        Shop reopened = shops.create(newOwner, shopProperty.id(), "Nya Butiken");
        assertThat(reopened.id()).isEqualTo(shop.id());
        assertThat(reopened.ownerName()).isEqualTo("Ny");
    }

    @Test
    void companyShopRevenueGoesToCompany() {
        Company company = core.companies().found(owner, "Nordia Tools AB");
        core.companies().deposit(owner, company.id(), Money.ofSek(30_000));
        Property store = core.properties().create("Hamngatan 1", Property.Type.SHOP, Region.of("world", 200, 60, 200, 210, 70, 210), Money.ofSek(20_000));
        core.properties().buy(owner, store.id(), company.id(), null);
        Shop shop = shops.create(owner, store.id(), "Nordia Tools");
        ShopListing listing = shops.list(owner, shop.id(), "world", 205, 61, 205, "BREAD", ITEM, 8, Money.ofSek(40));
        shops.recordPurchase(customer, listing.id(), 3, Money.ofSek(40), UUID.randomUUID());
        assertThat(core.companies().balance(company.id())).isEqualTo(Money.ofSek(10_120));
        assertThat(shops.whereToBuy("BREAD", 5)).extracting(ShopListing::id).containsExactly(listing.id());
        assertThat(shops.shopsOwnedBy(owner)).extracting(Shop::id).contains(shop.id());
    }

    @Test
    void concurrentPurchasesNeverOverdraw() throws Exception {
        Shop shop = shops.create(owner, shopProperty.id(), "Nordhamn Tools");
        ShopListing listing = pickaxes(shop, 300);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                try {
                    shops.recordPurchase(customer, listing.id(), 1, Money.ofSek(300), UUID.randomUUID());
                    return true;
                } catch (DomainException e) {
                    return false;
                }
            }));
        }
        int bought = 0;
        for (Future<Boolean> f : futures) {
            bought += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(bought).isEqualTo(3);
        assertThat(balance(customer)).isEqualTo(Money.ofSek(100));
        assertLedgerHealthy();
    }
}
