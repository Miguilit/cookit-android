package be.cookit.pos.android.data

/**
 * Canonical Cookit API contract shared with CookitPad.
 *
 * Base: /api/application-integration
 * Auth: Laravel Sanctum bearer token.
 *
 * Wave A3 will bind these routes to the production HTTP client:
 * POST auth/login
 * GET  pos/menus
 * GET  pos/orders
 * POST pos/orders
 * PUT  pos/orders/{id}
 * POST pos/orders/{id}/pay
 * POST pos/orders/{id}/kot
 * GET  pos/kots
 * GET  pos/cash-register/registers
 * GET  pos/cash-register/sessions/active
 * POST pos/cash-register/sessions/open
 * POST pos/cash-register/sessions/{id}/close
 * GET  pos/cash-register/denominations
 * POST pos/cash-register/transactions/cash-in
 * POST pos/cash-register/transactions/cash-out
 * POST pos/cash-register/transactions/safe-drop
 * POST pos/notifications/register-token
 *
 * Native bootstrap / native_policy is the RBAC source of truth.
 */
object CookitApiContract {
    const val AUTH_LOGIN = "auth/login"
    const val MENUS = "pos/menus"
    const val ORDERS = "pos/orders"
    const val KOTS = "pos/kots"
    const val ACTIVE_CASH_SESSION = "pos/cash-register/sessions/active"
    const val OPEN_CASH_SESSION = "pos/cash-register/sessions/open"
    const val DENOMINATIONS = "pos/cash-register/denominations"
    const val REGISTER_NOTIFICATION_TOKEN = "pos/notifications/register-token"
}
