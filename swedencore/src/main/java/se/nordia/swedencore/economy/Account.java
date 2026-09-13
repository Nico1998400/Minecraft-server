package se.nordia.swedencore.economy;

public record Account(long id, AccountOwner owner, String purpose, Money balance, boolean allowNegative, boolean frozen) {

    public static final String MAIN = "MAIN";
    public static final String ESCROW = "ESCROW";
}
