package se.nordia.swedencore.market;

import org.junit.jupiter.api.Test;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.Region;
import se.nordia.swedencore.testing.CoreTest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MarketServiceTest extends CoreTest {

    @Test
    void pricesComeFromRealTradesWithTrend() {
        UUID merchant = player("Merchant");
        UUID customer = player("Customer");
        UUID steelworks = player("Steelworks");
        grant(merchant, 50_000);
        grant(customer, 50_000);
        grant(steelworks, 50_000);

        // Previous week: a buy order pays 10 SEK per ingot.
        var order = core.orders().create(steelworks, null, "IRON_INGOT", 100, Money.ofSek(10), 24 * 14);
        core.orders().fill(merchant, order.id(), List.of(new ItemStashService.StashItem("IRON_INGOT", 20, new byte[]{1})), UUID.randomUUID());
        clock.advance(Duration.ofDays(8));

        // This week: a shop sells bundles of 4 ingots for 52 SEK (13 SEK each) and the order pays 10.
        Property store = core.properties().create("Järnboden", Property.Type.SHOP, Region.of("world", 0, 0, 0, 5, 5, 5), Money.ofSek(1_000));
        core.properties().buy(merchant, store.id(), null, null);
        var shop = core.shops().create(merchant, store.id(), "Järnboden");
        var listing = core.shops().list(merchant, shop.id(), "world", 1, 1, 1, "IRON_INGOT", new byte[]{1}, 4, Money.ofSek(52));
        core.shops().recordPurchase(customer, listing.id(), 3, Money.ofSek(52), UUID.randomUUID());
        core.orders().fill(merchant, order.id(), List.of(new ItemStashService.StashItem("IRON_INGOT", 12, new byte[]{1})), UUID.randomUUID());

        MarketService.Report report = core.market().report("IRON_INGOT");
        assertThat(report.shops().volume()).isEqualTo(12);
        assertThat(report.shops().averagePrice()).isEqualTo(Money.ofSek(13));
        assertThat(report.orders().averagePrice()).isEqualTo(Money.ofSek(10));
        assertThat(report.combined().volume()).isEqualTo(24);
        assertThat(report.combined().averagePrice()).isEqualTo(Money.ofOre(1_150));
        assertThat(report.previous().averagePrice()).isEqualTo(Money.ofSek(10));
        assertThat(report.trendPercent()).isEqualTo(15);
        assertThat(report.bestShopPrice()).isEqualTo(Money.ofSek(13));
        assertThat(report.bestOrderPrice()).isEqualTo(Money.ofSek(10));
        assertThat(core.market().topTraded(5)).extracting(MarketService.Traded::material).containsExactly("IRON_INGOT");

        MarketService.Report empty = core.market().report("DIAMOND");
        assertThat(empty.combined().averagePrice()).isNull();
        assertThat(empty.trendPercent()).isNull();
    }
}
