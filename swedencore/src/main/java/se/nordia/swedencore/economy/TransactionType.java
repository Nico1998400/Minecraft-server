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
    /** Buy order budget moved into escrow. */
    ORDER_ESCROW,
    /** Seller paid from a buy order's escrow. */
    ORDER_PAYOUT,
    /** Unused buy order escrow returned to the issuer. */
    ORDER_REFUND,
    /** Buy order listing fee (sink). */
    ORDER_FEE,
    /** Transport reward into escrow / paid to the carrier / refunded. */
    TRANSPORT_ESCROW,
    TRANSPORT_PAYOUT,
    TRANSPORT_REFUND,
    /** Carrier collateral into escrow / returned / forfeited to the issuer. */
    TRANSPORT_COLLATERAL,
    TRANSPORT_COLLATERAL_RETURN,
    TRANSPORT_COLLATERAL_FORFEIT,
    /** Transport job fee (sink). */
    TRANSPORT_FEE,
    /** Rent from tenant to landlord. */
    RENT,
    /** Loan principal paid out from lender to borrower. */
    LOAN_PRINCIPAL,
    /** Loan repayment from borrower to lender. */
    LOAN_REPAYMENT,
    /** A bankrupt company's remaining money distributed to a creditor. */
    BANKRUPTCY_DISTRIBUTION,
    /** Settlement founding / upgrade costs. */
    SETTLEMENT_FEE,
    /** A resident contributes to the settlement treasury. */
    SETTLEMENT_DEPOSIT,
    /** The leader takes money from the settlement treasury. */
    SETTLEMENT_WITHDRAWAL,
    /** Moving a dissolved entity's remaining funds to its owner. */
    DISSOLUTION_PAYOUT,
    /** A buyer pays the seller (player or company treasury) for shares. */
    SHARE_PURCHASE,
    /** Share sale fee (sink). */
    SHARE_FEE,
    /** Company pays a dividend to a shareholder. */
    DIVIDEND
}
