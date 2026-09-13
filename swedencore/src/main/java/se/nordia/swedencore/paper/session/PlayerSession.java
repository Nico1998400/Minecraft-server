package se.nordia.swedencore.paper.session;

import se.nordia.swedencore.localization.SupportedLocale;

import java.util.UUID;

/** Volatile per-online-player state. The database remains the source of truth. */
public final class PlayerSession {

    /** A destructive action awaiting confirmation (e.g. dissolving a company). */
    public record PendingConfirmation(String action, long targetId, long expiresAtMillis) {
        public boolean matches(String otherAction, long now) {
            return action.equals(otherAction) && now <= expiresAtMillis;
        }
    }

    /** A block position selected by an administrator (e.g. property corners). */
    public record BlockPos(String world, int x, int y, int z) {
    }

    private volatile BlockPos selection1;
    private volatile BlockPos selection2;

    public BlockPos selection1() {
        return selection1;
    }

    public void selection1(BlockPos pos) {
        this.selection1 = pos;
    }

    public BlockPos selection2() {
        return selection2;
    }

    public void selection2(BlockPos pos) {
        this.selection2 = pos;
    }

    private final UUID uuid;
    private volatile SupportedLocale locale;
    private volatile Long selectedCompanyId;
    private volatile PendingConfirmation pendingConfirmation;

    public PlayerSession(UUID uuid, SupportedLocale locale) {
        this.uuid = uuid;
        this.locale = locale;
    }

    public UUID uuid() {
        return uuid;
    }

    /** Preferred locale, or {@code null} to use the server default. */
    public SupportedLocale locale() {
        return locale;
    }

    public void locale(SupportedLocale locale) {
        this.locale = locale;
    }

    /** Company chosen with {@code /company use}, used when a command omits the company. */
    public Long selectedCompanyId() {
        return selectedCompanyId;
    }

    public void selectedCompanyId(Long companyId) {
        this.selectedCompanyId = companyId;
    }

    public PendingConfirmation pendingConfirmation() {
        return pendingConfirmation;
    }

    public void pendingConfirmation(PendingConfirmation confirmation) {
        this.pendingConfirmation = confirmation;
    }
}
