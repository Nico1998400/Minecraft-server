package se.nordia.plugins.core;

import se.nordia.plugins.companies.CompanyConfig;
import se.nordia.plugins.contracts.ContractConfig;
import se.nordia.plugins.database.DatabaseConfig;
import se.nordia.plugins.economy.EconomyConfig;
import se.nordia.plugins.finance.LoanService;
import se.nordia.plugins.localization.SupportedLocale;
import se.nordia.plugins.orders.BuyOrderService;
import se.nordia.plugins.production.ProductionService;
import se.nordia.plugins.properties.PropertyService;
import se.nordia.plugins.settlements.SettlementConfig;
import se.nordia.plugins.shops.ShopService;
import se.nordia.plugins.skills.SkillsConfig;

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
        se.nordia.plugins.properties.LeaseService.Config leases,
        se.nordia.plugins.inventory.ItemStashService.Capacity storage,
        se.nordia.plugins.shares.ShareService.Config shares,
        SupportedLocale defaultLocale,
        boolean shutdownOnDatabaseFailure
) {
    /** Defaults for everything except the database; used by tests. */
    public static CoreConfig defaults(DatabaseConfig database) {
        return new CoreConfig(database, EconomyConfig.defaults(), SkillsConfig.defaults(), CompanyConfig.defaults(),
                ContractConfig.defaults(), PropertyService.Config.defaults(), ShopService.Config.defaults(),
                SettlementConfig.defaults(), BuyOrderService.Config.defaults(), ProductionService.Config.defaults(),
                LoanService.Config.defaults(), se.nordia.plugins.properties.LeaseService.Config.defaults(),
                se.nordia.plugins.inventory.ItemStashService.Capacity.defaults(),
                se.nordia.plugins.shares.ShareService.Config.defaults(), SupportedLocale.SV_SE, true);
    }
}
