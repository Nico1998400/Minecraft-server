package se.nordia.swedencore.core;

import se.nordia.swedencore.companies.CompanyConfig;
import se.nordia.swedencore.contracts.ContractConfig;
import se.nordia.swedencore.database.DatabaseConfig;
import se.nordia.swedencore.economy.EconomyConfig;
import se.nordia.swedencore.finance.LoanService;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.orders.BuyOrderService;
import se.nordia.swedencore.production.ProductionService;
import se.nordia.swedencore.properties.PropertyService;
import se.nordia.swedencore.settlements.SettlementConfig;
import se.nordia.swedencore.shops.ShopService;
import se.nordia.swedencore.skills.SkillsConfig;

/** Complete, validated plugin configuration. Built by the Paper layer from config.yml and environment variables. */
public record CoreConfig(
        DatabaseConfig database,
        EconomyConfig economy,
        SkillsConfig skills,
        CompanyConfig companies,
        ContractConfig contracts,
        PropertyService.Config properties,
        ShopService.Config shops,
        SettlementConfig settlements,
        BuyOrderService.Config orders,
        ProductionService.Config production,
        LoanService.Config loans,
        se.nordia.swedencore.properties.LeaseService.Config leases,
        se.nordia.swedencore.inventory.ItemStashService.Capacity storage,
        se.nordia.swedencore.shares.ShareService.Config shares,
        SupportedLocale defaultLocale,
        boolean shutdownOnDatabaseFailure
) {
    /** Defaults for everything except the database; used by tests. */
    public static CoreConfig defaults(DatabaseConfig database) {
        return new CoreConfig(database, EconomyConfig.defaults(), SkillsConfig.defaults(), CompanyConfig.defaults(),
                ContractConfig.defaults(), PropertyService.Config.defaults(), ShopService.Config.defaults(),
                SettlementConfig.defaults(), BuyOrderService.Config.defaults(), ProductionService.Config.defaults(),
                LoanService.Config.defaults(), se.nordia.swedencore.properties.LeaseService.Config.defaults(),
                se.nordia.swedencore.inventory.ItemStashService.Capacity.defaults(),
                se.nordia.swedencore.shares.ShareService.Config.defaults(), SupportedLocale.SV_SE, true);
    }
}
