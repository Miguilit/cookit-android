package be.cookit.pos.android.data

/**
 * Canonical Cookit API contract shared with CookitPad.
 * Base: /api/application-integration
 * Auth: Laravel Sanctum bearer token.
 */
object CookitApiContract {
    const val AUTH_LOGIN = "auth/login"
    const val PLATFORM_CONFIG = "platform/config"
    const val CATEGORIES = "pos/categories"
    const val ITEMS = "pos/items"
    const val MENUS = "pos/menus"
    const val ORDERS = "pos/orders"
    const val KOTS = "pos/kots"
    const val ACTIVE_CASH_SESSION = "pos/cash-register/sessions/active"
    const val OPEN_CASH_SESSION = "pos/cash-register/sessions/open"
    const val DENOMINATIONS = "pos/cash-register/denominations"
    const val REGISTER_NOTIFICATION_TOKEN = "pos/notifications/register-token"
}
