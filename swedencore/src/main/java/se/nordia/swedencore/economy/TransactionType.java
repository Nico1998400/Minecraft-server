package se.nordia.swedencore.economy;

/**
 * Why money moved. Every ledger row is typed so that analytics, valuation, news and player history can be derived
 * from real economic activity later.
 */
public enum TransactionType {
    /** One-time grant to new players (mint). */
    STARTER_GRANT,
    /** Admin created money (mint). */
    ADMIN_GRANT,
    /** Admin destroyed money (sink). */
    ADMIN_REMOVAL,
    /** Player pays another player. */
    PLAYER_PAYMENT,
    /** Fee paid to register a company (sink or city). */
    COMPANY_REGISTRATION_FEE,
    /** Owner moves personal money into the company. */
    COMPANY_DEPOSIT,
    /** Owner takes money out of the company. */
    COMPANY_WITHDRAWAL,
    /** Company pays wages to an employee. */
    SALARY,
    /** Contract reward moved into escrow. */
    CONTRACT_ESCROW,
    /** Escrow pays the contractor. */
    CONTRACT_PAYOUT,
    /** Escrow returned to issuer. */
    CONTRACT_REFUND,
    /** Contract creation fee (sink). */
    CONTRACT_FEE,
    /** Property purchase. */
    PROPERTY_PURCHASE,
    /** A player buys goods in a physical shop. */
    SHOP_PURCHASE,
    /** Money offered in a direct player-to-player trade. */
    TRADE,
    /** Settlement founding / upgrade costs. */
    SETTLEMENT_FEE,
    /** A resident contributes to the settlement treasury. */
    SETTLEMENT_DEPOSIT,
    /** The leader takes money from the settlement treasury. */
    SETTLEMENT_WITHDRAWAL,
    /** Moving a dissolved entity's remaining funds to its owner. */
    DISSOLUTION_PAYOUT
}
