package be.cookit.pos.android.data

import android.content.Intent

data class PushNavigationRequest(
    val action: String?,
    val notificationId: String?,
    val orderId: Long?,
    val tableId: Long?,
    val waiterRequestId: Long?,
    val branchId: Long?
) {
    companion object {
        fun fromIntent(
            intent: Intent?
        ): PushNavigationRequest? {
            if (intent == null) {
                return null
            }

            fun stringExtra(name: String): String? =
                intent
                    .getStringExtra(name)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

            fun longExtra(name: String): Long? =
                stringExtra(name)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }

            val request =
                PushNavigationRequest(
                    action = stringExtra("action"),
                    notificationId =
                        stringExtra("notification_id"),
                    orderId =
                        longExtra("order_id"),
                    tableId =
                        longExtra("table_id"),
                    waiterRequestId =
                        longExtra("waiter_request_id"),
                    branchId =
                        longExtra("branch_id")
                )

            val actionable =
                request.action
                    ?.equals(
                        "open_order",
                        ignoreCase = true
                    ) == true ||
                    request.action
                        ?.equals(
                            "open_waiter_request",
                            ignoreCase = true
                        ) == true

            return request.takeIf { actionable }
        }
    }
}
