package se.nordia.swedencore.events;

/**
 * Facts that happened in the world, published after the transaction that caused them committed.
 *
 * <p>Today they keep caches (e.g. the protection index) fresh. Later they are the raw material for the dynamic news
 * system and player history — which is why they describe business facts, not technical changes.
 */
public sealed interface DomainEvent {

    record CompanyMembershipChanged(long companyId) implements DomainEvent {
    }

    record PropertyChanged(long propertyId) implements DomainEvent {
    }

    record PropertySold(long propertyId, String buyerType, String buyerId, long priceOre) implements DomainEvent {
    }

    record SettlementChanged(long settlementId) implements DomainEvent {
    }

    record ShopChanged(long shopId) implements DomainEvent {
    }

    record CompanyBankrupt(long companyId, String reason) implements DomainEvent {
    }
}
