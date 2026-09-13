package se.nordia.swedencore.companies;

/**
 * Roles inside a company. Deliberately small for the MVP; future roles (director, accountant, store manager,
 * logistics manager) extend this enum and the permission checks in the services.
 */
public enum CompanyRole {
    /** Founder / owner: full control, cannot leave (must dissolve). */
    OWNER,
    /** Manages positions, applications and employees (not other managers). */
    MANAGER,
    /** Regular employee. */
    EMPLOYEE;

    public boolean canManageStaff() {
        return this == OWNER || this == MANAGER;
    }
}
