package be.cookit.pos.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import be.cookit.pos.android.BuildConfig
import be.cookit.pos.android.data.DemoRepository
import be.cookit.pos.android.data.fiscal.EmbeddedMockFdmContract
import be.cookit.pos.android.domain.*
import be.cookit.pos.android.ui.theme.*
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Screen(val label: String) {
    DASHBOARD("Accueil"),
    POS("Caisse"),
    ORDERS("Commandes"),
    KDS("Cuisine"),
    DELIVERY("Livraison"),
    CASH("Fond de caisse"),
    SETTINGS("Réglages")
}

private fun screenLabel(screen: Screen, t: UiStrings): String = when (screen) {
    Screen.DASHBOARD -> t.home
    Screen.POS -> t.pos
    Screen.ORDERS -> t.orders
    Screen.KDS -> t.kitchen
    Screen.DELIVERY -> t.delivery
    Screen.CASH -> t.cash
    Screen.SETTINGS -> t.settings
}

private fun allowedTabletScreens(policy: NativePolicy): List<Screen> = buildList {
    add(Screen.DASHBOARD)
    if (policy.canUsePos) add(Screen.POS)
    add(Screen.ORDERS)
    if (policy.canViewKds) add(Screen.KDS)
    if (policy.canViewDelivery) add(Screen.DELIVERY)
    if (policy.canUsePos) add(Screen.CASH)
    add(Screen.SETTINGS)
}

private fun defaultScreen(policy: NativePolicy): Screen = when {
    policy.canUsePos -> Screen.POS
    policy.canViewKds -> Screen.KDS
    policy.canViewDelivery -> Screen.DELIVERY
    else -> Screen.ORDERS
}

private fun mobileScreens(policy: NativePolicy): List<Screen> = buildList {
    if (policy.canUsePos) add(Screen.POS)
    add(Screen.ORDERS)
    if (!policy.canUsePos && policy.canViewKds) add(Screen.KDS)
    if (!policy.canUsePos && policy.canViewDelivery) add(Screen.DELIVERY)
    if (policy.canUsePos) add(Screen.CASH)
    add(Screen.SETTINGS)
}.distinct()

@Composable
fun CookitApp(vm: CookitPosViewModel = viewModel()) {
    val state by vm.ui.collectAsState()
    val t = strings(state.language)

    if (!state.authenticated) {
        LoginScreen(
            loading = state.loading,
            error = state.error,
            onLogin = vm::login,
            onDemo = vm::enterDemo
        )
        return
    }

    var screen by remember(state.user.role, state.policy) { mutableStateOf(defaultScreen(state.policy)) }
    val widthDp = LocalConfiguration.current.screenWidthDp
    val tablet = widthDp >= 840

    if (tablet) {
        Row(Modifier.fillMaxSize().background(CookitCanvas)) {
            SideNavigation(screen = screen, state = state, t = t, onSelect = { screen = it })
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(state = state, t = t)
                ScreenContent(screen, state, vm, t, onNavigate = { screen = it })
            }
        }
    } else {
        Scaffold(
            topBar = { TopBar(state = state, t = t, compact = true) },
            bottomBar = {
                NavigationBar {
                    mobileScreens(state.policy).forEach {
                        NavigationBarItem(
                            selected = screen == it,
                            onClick = { screen = it },
                            icon = { Icon(iconFor(it), contentDescription = null) },
                            label = { Text(screenLabel(it, t)) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(CookitCanvas)
            ) { ScreenContent(screen, state, vm, t, onNavigate = { screen = it }) }
        }
    }
}

@Composable
private fun LoginScreen(
    loading: Boolean,
    error: String?,
    onLogin: (String, String) -> Unit,
    onDemo: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize().background(CookitCanvas),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 460.dp).padding(24.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = CookitOrange
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("C", color = Color.White, fontWeight = FontWeight.Black, fontSize = 30.sp)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Cookit POS", fontSize = 26.sp, fontWeight = FontWeight.Black)
                        Text("Android • A8–A9 Parité CookitPad", color = CookitMuted)
                    }
                }
                HorizontalDivider(color = CookitLine)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("E-mail Cookit") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !loading
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mot de passe") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !loading
                )
                if (!error.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            error,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp
                        )
                    }
                }
                Button(
                    onClick = { onLogin(email, password) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !loading
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (loading) "Connexion…" else "Se connecter à Cookit", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onDemo,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = !loading
                ) { Text("Continuer en mode démo") }
                Text(
                    "Connexion réelle via l’API Cookit. Le mode démo reste disponible pour comparer l’UI.",
                    color = CookitMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SideNavigation(screen: Screen, state: PosUiState, t: UiStrings, onSelect: (Screen) -> Unit) {
    val screens = allowedTabletScreens(state.policy)
    val settings = Screen.SETTINGS
    val mainScreens = screens.filterNot { it == settings }

    Surface(
        modifier = Modifier.width(88.dp).fillMaxHeight(),
        color = Color(0xFF152019)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(16.dp),
                color = CookitOrange
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("C", color = Color.White, fontWeight = FontWeight.Black, fontSize = 25.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            mainScreens.forEach { item ->
                SideNavigationItem(
                    item = item,
                    selected = item == screen,
                    label = screenLabel(item, t),
                    onClick = { onSelect(item) }
                )
            }

            Spacer(Modifier.weight(1f))

            if (settings in screens) {
                SideNavigationItem(
                    item = settings,
                    selected = settings == screen,
                    label = screenLabel(settings, t),
                    onClick = { onSelect(settings) }
                )
            }
        }
    }
}

@Composable
private fun SideNavigationItem(
    item: Screen,
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) Color.White.copy(alpha = 0.13f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            iconFor(item),
            contentDescription = label,
            tint = if (selected) CookitOrange else Color(0xFFD7DDD9),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = if (selected) Color.White else Color(0xFFB9C0BC),
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

private fun iconFor(screen: Screen) = when (screen) {
    Screen.DASHBOARD -> Icons.Default.Home
    Screen.POS -> Icons.Default.PointOfSale
    Screen.ORDERS -> Icons.Default.ReceiptLong
    Screen.KDS -> Icons.Default.SoupKitchen
    Screen.DELIVERY -> Icons.Default.DeliveryDining
    Screen.CASH -> Icons.Default.AccountBalanceWallet
    Screen.SETTINGS -> Icons.Default.Settings
}

@Composable
private fun TopBar(state: PosUiState, t: UiStrings, compact: Boolean = false) {
    Surface(modifier = Modifier.statusBarsPadding(), color = Color.White, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(if (compact) 68.dp else 78.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RestaurantBrandMark(
                logoUrl = state.user.restaurantLogoUrl,
                restaurantName = state.user.restaurant,
                compact = compact
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.user.restaurant,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 17.sp else 20.sp
                )
                Text(
                    "${state.user.branch} • ${state.user.role.name.lowercase().replaceFirstChar { it.uppercase() }}${if (state.demoMode) " • Démo" else ""}",
                    color = CookitMuted,
                    fontSize = 12.sp
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (state.online) CookitSoftGreen else MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(8.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (state.online) CookitGreen else MaterialTheme.colorScheme.error)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (state.online) t.online else t.offline,
                        color = if (state.online) CookitGreen else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
            if (!compact) {
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = {}) {
                    BadgedBox(
                        badge = { if (state.unreadInbound > 0) Badge { Text(state.unreadInbound.toString()) } }
                    ) { Icon(Icons.Default.Notifications, null) }
                }
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = CookitSoftOrange
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            state.user.name.split(' ').mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").ifBlank { "C" },
                            color = CookitOrange,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun RestaurantBrandMark(
    logoUrl: String?,
    restaurantName: String,
    compact: Boolean
) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, logoUrl) {
        value = loadRemoteBitmap(logoUrl)
    }

    Surface(
        modifier = Modifier.size(if (compact) 38.dp else 44.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (bitmap == null) CookitSoftOrange else Color.White,
        border = BorderStroke(1.dp, CookitLine)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = restaurantName,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    restaurantName.trim().firstOrNull()?.uppercase() ?: "R",
                    color = CookitOrange,
                    fontWeight = FontWeight.Black,
                    fontSize = if (compact) 17.sp else 20.sp
                )
            }
        }
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    state: PosUiState,
    vm: CookitPosViewModel,
    t: UiStrings,
    onNavigate: (Screen) -> Unit
) {
    when (screen) {
        Screen.POS -> PosScreen(
            state = state,
            vm = vm,
            t = t,
            categories = state.categories,
            products = state.products,
            inboundCount = state.unreadInbound,
            inboundOrders = state.orders.filter { it.unread },
            onReadInbound = vm::markInboundRead
        )
        Screen.ORDERS -> OrdersScreen(
            state = state,
            onOpen = { order ->
                vm.openOrderForPos(order)
                onNavigate(Screen.POS)
            }
        )
        Screen.CASH -> CashScreen(state, vm, t)
        Screen.DASHBOARD -> DashboardScreen(state)
        Screen.KDS -> KdsScreen(state, t, vm::advanceKitchenTicket)
        Screen.DELIVERY -> DeliveryScreen(state.orders, t)
        Screen.SETTINGS -> SettingsScreen(state, vm, t, onRefresh = vm::refresh, onLogout = vm::logout)
    }
}

@Composable
private fun InboundBanner(count: Int, orders: List<PosOrder>, onRead: () -> Unit) {
    if (count <= 0) return
    val preview = orders.take(2).joinToString(" • ") { "${it.channel} ${it.code}" }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFFF7E8),
        border = BorderStroke(1.dp, Color(0xFFFFD99A))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = CookitOrange) {
                Icon(Icons.Default.NotificationsActive, null, tint = Color.White, modifier = Modifier.padding(9.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (count == 1) "1 nouvelle commande" else "$count nouvelles commandes",
                    fontWeight = FontWeight.ExtraBold
                )
                Text(preview.ifBlank { "Nouvelle commande externe" }, color = CookitMuted, fontSize = 12.sp)
            }
            TextButton(onClick = onRead) { Text("Vu") }
        }
    }
}

@Composable
private fun PosScreen(
    state: PosUiState,
    vm: CookitPosViewModel,
    t: UiStrings,
    categories: List<Category>,
    products: List<Product>,
    inboundCount: Int,
    inboundOrders: List<PosOrder>,
    onReadInbound: () -> Unit
) {
    val initialCategory = categories.firstOrNull()?.id ?: 0L
    var selectedCategory by remember(categories) { mutableLongStateOf(initialCategory) }
    val cart = state.draftCart
    val orderType = state.draftOrderType
    val selectedTableId = state.draftTableId
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp >= 900

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        InboundBanner(inboundCount, inboundOrders, onReadInbound)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(state.openedOrderCode ?: t.newOrder, fontWeight = FontWeight.Black, fontSize = 26.sp)
            if (state.resumedRemoteOrderId != null || state.pendingRemoteOrderId != null) {
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = vm::startNewOrder, enabled = !state.orderLoadBusy) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text(t.newOrder, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            OrderTypeChip(t.dineIn, orderType == OrderType.DINE_IN) { vm.setDraftOrderType(OrderType.DINE_IN) }
            OrderTypeChip(t.takeaway, orderType == OrderType.TAKEAWAY) { vm.setDraftOrderType(OrderType.TAKEAWAY) }
            OrderTypeChip(t.deliveryType, orderType == OrderType.DELIVERY) { vm.setDraftOrderType(OrderType.DELIVERY) }
        }

        if (orderType == OrderType.DINE_IN && state.tables.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.tables.filter { it.available || it.id == selectedTableId }) { table ->
                    FilterChip(
                        selected = selectedTableId == table.id,
                        onClick = { vm.selectDraftTable(table.id) },
                        label = { Text("${t.chooseTable} ${table.label}") }
                    )
                }
            }
        }

        if (state.checkoutMessage == "cash_required") {
            Text(t.openCashFirst, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        } else if (state.checkoutMessage == "table_required") {
            Text("${t.chooseTable}: ${t.selectionRequired}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }

        if (!state.checkoutError.isNullOrBlank() && !state.paymentSheetOpen) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    state.checkoutError,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        state.openedOrderCode?.let { code ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ReceiptLong, null, tint = CookitOrange)
                Spacer(Modifier.width(8.dp))
                Text("Commande $code", fontWeight = FontWeight.Black)
                Spacer(Modifier.width(10.dp))
                Text(
                    String.format(Locale.FRANCE, "%.2f €", state.resumedRemoteOrderTotal ?: cart.sumOf { it.total }),
                    color = CookitGreen,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::refreshOpenedOrder, enabled = !state.orderLoadBusy) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Rafraîchir")
                }
                TextButton(onClick = vm::startNewOrder, enabled = !state.orderLoadBusy) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Nouvelle commande")
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProductPane(
                    modifier = Modifier.weight(1.65f),
                    categories = categories,
                    products = products,
                    selectedCategory = selectedCategory,
                    onCategory = { selectedCategory = it },
                    onAdd = vm::addProduct
                )
                CartPane(
                    modifier = Modifier.weight(0.9f),
                    cart = cart,
                    t = t,
                    busy = state.checkoutBusy,
                    tableLabel = state.tables.firstOrNull { it.id == selectedTableId }?.label,
                    canonicalTotal = state.resumedRemoteOrderTotal,
                    locked = state.resumedRemoteOrderId != null,
                    onPlus = vm::incrementProduct,
                    onMinus = vm::decrementProduct,
                    onSendKitchen = vm::sendDraftToKitchen,
                    onBillingTools = vm::openBillingTools,
                    onCheckout = {
                        val billing = state.billingContext
                        if (billing != null && (billing.splitBills.isNotEmpty() || billing.groupOrders.size > 1)) vm.openBillingTools()
                        else vm.requestCheckout()
                    }
                )
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                val showMobileCartBar = cart.isNotEmpty() || ((state.resumedRemoteOrderTotal ?: 0.0) > 0.0 && state.resumedRemoteOrderId != null)
                ProductPane(
                    modifier = Modifier.fillMaxSize().padding(bottom = if (!showMobileCartBar) 0.dp else 84.dp),
                    categories = categories,
                    products = products,
                    selectedCategory = selectedCategory,
                    onCategory = { selectedCategory = it },
                    onAdd = vm::addProduct
                )
                if (showMobileCartBar) {
                    MobileCartBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        cart = cart,
                        t = t,
                        busy = state.checkoutBusy,
                        canonicalTotal = state.resumedRemoteOrderTotal,
                        locked = state.resumedRemoteOrderId != null,
                        onSendKitchen = vm::sendDraftToKitchen,
                        onBillingTools = vm::openBillingTools,
                        onCheckout = {
                            val billing = state.billingContext
                            if (billing != null && (billing.splitBills.isNotEmpty() || billing.groupOrders.size > 1)) vm.openBillingTools()
                            else vm.requestCheckout()
                        }
                    )
                }
            }
        }
    }

    if (state.paymentSheetOpen) {
        PaymentMethodDialog(
            state = state,
            t = t,
            onDismiss = vm::dismissPaymentSheet,
            onConfirm = vm::confirmPayment
        )
    }

    if (state.billingSheetOpen) {
        BillingToolsDialog(
            state = state,
            onDismiss = vm::dismissBillingTools,
            onSplitEqual = vm::splitBillEqual,
            onSplitCustom = vm::splitBillCustom,
            onSplitItems = vm::splitBillItems,
            onCancelSplit = vm::cancelSplitBill,
            onPaySplit = vm::paySplitBill,
            onMergeTables = vm::mergeBillingTables,
            onUnmergeTables = vm::unmergeBillingTables,
            onPayGroup = vm::payMergedGroup
        )
    }
}

@Composable
private fun PaymentMethodDialog(
    state: PosUiState,
    t: UiStrings,
    onDismiss: () -> Unit,
    onConfirm: (PosPaymentMethod, Double?) -> Unit
) {
    var selected by remember(state.paymentSheetOpen) { mutableStateOf(PosPaymentMethod.CASH) }
    val total = state.resumedRemoteOrderTotal?.takeIf { state.resumedRemoteOrderId != null && it > 0 }
        ?: state.draftCart.sumOf { it.total }
    var tenderedText by remember(state.paymentSheetOpen, total) {
        mutableStateOf(String.format(Locale.US, "%.2f", total))
    }
    val tendered = tenderedText.replace(',', '.').toDoubleOrNull()
    val change = if (selected == PosPaymentMethod.CASH && tendered != null) (tendered - total).coerceAtLeast(0.0) else 0.0
    val cashValid = selected != PosPaymentMethod.CASH || ((tendered ?: 0.0) + 0.0001 >= total)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.paymentTitle, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.total, color = CookitMuted)
                    Spacer(Modifier.weight(1f))
                    Text(
                        String.format(Locale.FRANCE, "%.2f €", total),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = CookitGreen
                    )
                }

                if (state.resumedRemoteOrderId != null || state.pendingRemoteOrderId != null) {
                    Surface(shape = RoundedCornerShape(12.dp), color = CookitSoftOrange) {
                        Text(
                            state.openedOrderCode?.let { "${t.pendingOrder} $it" } ?: t.pendingOrder,
                            Modifier.padding(10.dp),
                            color = CookitOrange,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                PaymentChoice(
                    selected = selected == PosPaymentMethod.CASH,
                    icon = Icons.Default.Payments,
                    title = t.cashPayment,
                    subtitle = t.cashPaymentHelp
                ) { selected = PosPaymentMethod.CASH }

                PaymentChoice(
                    selected = selected == PosPaymentMethod.CARD_TERMINAL,
                    icon = Icons.Default.CreditCard,
                    title = t.cardTerminalPayment,
                    subtitle = t.terminalHelp
                ) { selected = PosPaymentMethod.CARD_TERMINAL }

                if (selected == PosPaymentMethod.CASH) {
                    OutlinedTextField(
                        value = tenderedText,
                        onValueChange = { tenderedText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Montant reçu") },
                        singleLine = true
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text("Monnaie à rendre", color = CookitMuted)
                        Spacer(Modifier.weight(1f))
                        Text(
                            String.format(Locale.FRANCE, "%.2f €", change),
                            color = if (cashValid) CookitGreen else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Black
                        )
                    }
                } else {
                    Text(t.terminalConfirmHelp, color = CookitMuted, fontSize = 12.sp)
                }

                state.checkoutError?.let { error ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            error,
                            Modifier.padding(10.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected, if (selected == PosPaymentMethod.CASH) tendered else null) },
                enabled = !state.checkoutBusy && cashValid
            ) {
                if (state.checkoutBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.pendingRemoteOrderId != null || state.resumedRemoteOrderId != null) t.retryPayment else t.confirmPayment)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.checkoutBusy) { Text(t.cancel) }
        }
    )
}

@Composable
private fun PaymentChoice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) CookitSoftOrange else Color.White,
        border = BorderStroke(1.dp, if (selected) CookitOrange else CookitLine)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) CookitOrange else CookitMuted)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, color = CookitMuted, fontSize = 12.sp)
            }
            if (selected) Icon(Icons.Default.CheckCircle, null, tint = CookitOrange)
        }
    }
}

@Composable
private fun BillingToolsDialog(
    state: PosUiState,
    onDismiss: () -> Unit,
    onSplitEqual: (Int) -> Unit,
    onSplitCustom: (List<Double>) -> Unit,
    onSplitItems: (Map<Long, Int>) -> Unit,
    onCancelSplit: () -> Unit,
    onPaySplit: (Long, PosPaymentMethod) -> Unit,
    onMergeTables: (List<Long>) -> Unit,
    onUnmergeTables: (List<Long>) -> Unit,
    onPayGroup: (PosPaymentMethod, Double?) -> Unit
) {
    val context = state.billingContext
    var equalParts by remember(context?.orderId) { mutableIntStateOf(2) }
    var customAmounts by remember(context?.orderId) { mutableStateOf("") }
    val selectedItems = remember(context?.orderId) { mutableStateMapOf<Long, Int>() }
    val selectedTables = remember(context?.orderId) { mutableStateMapOf<Long, Boolean>() }
    var groupMethod by remember(context?.orderId) { mutableStateOf(PosPaymentMethod.CASH) }
    var groupTenderedText by remember(context?.orderId, context?.groupAmountDue) {
        mutableStateOf(String.format(Locale.US, "%.2f", context?.groupAmountDue ?: 0.0))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.9f)
                .widthIn(max = 980.dp),
            shape = RoundedCornerShape(26.dp),
            color = Color.White,
            shadowElevation = 18.dp
        ) {
            Column(Modifier.fillMaxSize().padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CallSplit, null, tint = CookitOrange)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Addition • Split & Merge", fontSize = 24.sp, fontWeight = FontWeight.Black)
                        context?.let {
                            Text(
                                "Commande #${it.orderNumber} • ${String.format(Locale.FRANCE, "%.2f €", it.amountDue)} restant",
                                color = CookitMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss, enabled = !state.billingBusy) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = CookitLine)

                if (state.billingBusy && context == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@Column
                }

                state.billingError?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            error,
                            Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (context == null) {
                    Text("Données d’addition indisponibles.", color = CookitMuted)
                    return@Column
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BillingMetric("Total", context.total, Modifier.weight(1f))
                        BillingMetric("Déjà payé", context.amountPaid, Modifier.weight(1f))
                        BillingMetric("Reste dû", context.amountDue, Modifier.weight(1f), emphasize = true)
                        if (context.groupOrders.size > 1) {
                            BillingMetric("Groupe", context.groupAmountDue, Modifier.weight(1f), emphasize = true)
                        }
                    }

                    if (context.splitBills.isNotEmpty()) {
                        BillingSection("Parts existantes") {
                            context.splitBills.forEach { bill ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    color = CookitCanvas,
                                    border = BorderStroke(1.dp, CookitLine)
                                ) {
                                    Row(
                                        Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(bill.label, fontWeight = FontWeight.Bold)
                                            Text(
                                                "${String.format(Locale.FRANCE, "%.2f €", bill.amountDue)} restant • ${bill.status}",
                                                color = CookitMuted,
                                                fontSize = 12.sp
                                            )
                                        }
                                        if (bill.amountDue > 0.0001) {
                                            OutlinedButton(
                                                onClick = { onPaySplit(bill.id, PosPaymentMethod.CASH) },
                                                enabled = !state.billingBusy
                                            ) { Text("Espèces") }
                                            Spacer(Modifier.width(6.dp))
                                            Button(
                                                onClick = { onPaySplit(bill.id, PosPaymentMethod.CARD_TERMINAL) },
                                                enabled = !state.billingBusy
                                            ) { Text("Carte") }
                                        } else {
                                            Text("PAYÉ", color = CookitGreen, fontWeight = FontWeight.Black)
                                        }
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = onCancelSplit,
                                enabled = !state.billingBusy,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Undo, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Annuler la division")
                            }
                        }
                    }

                    if (context.capabilities.splitEqual) {
                        BillingSection("Division égale") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalIconButton(
                                    onClick = { equalParts = (equalParts - 1).coerceAtLeast(2) },
                                    enabled = !state.billingBusy
                                ) { Icon(Icons.Default.Remove, null) }
                                Text(
                                    "$equalParts parts",
                                    modifier = Modifier.padding(horizontal = 18.dp),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp
                                )
                                FilledTonalIconButton(
                                    onClick = { equalParts = (equalParts + 1).coerceAtMost(20) },
                                    enabled = !state.billingBusy
                                ) { Icon(Icons.Default.Add, null) }
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = { onSplitEqual(equalParts) },
                                    enabled = !state.billingBusy
                                ) {
                                    Text("Diviser")
                                }
                            }
                        }
                    }

                    if (context.capabilities.splitCustom) {
                        BillingSection("Division par montants") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = customAmounts,
                                    onValueChange = { customAmounts = it },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Montants séparés par virgule") },
                                    placeholder = { Text("20, 20, 25") },
                                    singleLine = true
                                )
                                Spacer(Modifier.width(10.dp))
                                Button(
                                    onClick = {
                                        onSplitCustom(parseSplitAmounts(customAmounts))
                                    },
                                    enabled = !state.billingBusy
                                ) { Text("Créer les parts") }
                            }
                        }
                    }

                    if (context.capabilities.splitItems && context.items.isNotEmpty()) {
                        BillingSection("Division par articles") {
                            context.items.forEach { line ->
                                val selectedQty = selectedItems[line.orderItemId] ?: 0
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = selectedQty > 0,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedItems[line.orderItemId] = line.quantity
                                            else selectedItems.remove(line.orderItemId)
                                        },
                                        enabled = !state.billingBusy
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(line.name, fontWeight = FontWeight.Bold)
                                        Text(
                                            "${line.quantity} × ${String.format(Locale.FRANCE, "%.2f €", line.unitPrice)}",
                                            color = CookitMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                    if (selectedQty > 0 && line.quantity > 1) {
                                        FilledTonalIconButton(
                                            onClick = {
                                                val next = selectedQty - 1
                                                if (next <= 0) selectedItems.remove(line.orderItemId)
                                                else selectedItems[line.orderItemId] = next
                                            },
                                            enabled = !state.billingBusy
                                        ) { Icon(Icons.Default.Remove, null) }
                                        Text("$selectedQty", Modifier.padding(horizontal = 6.dp), fontWeight = FontWeight.Black)
                                        FilledTonalIconButton(
                                            onClick = { selectedItems[line.orderItemId] = (selectedQty + 1).coerceAtMost(line.quantity) },
                                            enabled = !state.billingBusy
                                        ) { Icon(Icons.Default.Add, null) }
                                    }
                                }
                            }
                            Button(
                                onClick = { onSplitItems(selectedItems.toMap()) },
                                enabled = !state.billingBusy && selectedItems.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Restaurant, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Créer une part avec la sélection")
                            }
                        }
                    }

                    if (context.capabilities.mergeTables && context.tableId != null) {
                        BillingSection("Fusion de tables") {
                            if (context.sessionTables.size > 1) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    context.sessionTables.forEach { table ->
                                        AssistChip(
                                            onClick = {},
                                            label = { Text(table.label) },
                                            leadingIcon = { Icon(Icons.Default.TableRestaurant, null, Modifier.size(16.dp)) }
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        val remove = context.sessionTables
                                            .filter { it.id != context.tableId }
                                            .map { it.id }
                                        onUnmergeTables(remove)
                                    },
                                    enabled = !state.billingBusy
                                ) {
                                    Icon(Icons.Default.CallSplit, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Dissocier les tables")
                                }
                            }

                            context.mergeCandidates
                                .filter { !it.alreadyMerged }
                                .forEach { candidate ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selectedTables[candidate.tableId] == true,
                                            onCheckedChange = { checked -> selectedTables[candidate.tableId] = checked },
                                            enabled = !state.billingBusy
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(candidate.label, fontWeight = FontWeight.Bold)
                                            Text(
                                                "${candidate.orderIds.size} commande(s) • ${String.format(Locale.FRANCE, "%.2f €", candidate.amountDue)} restant",
                                                color = CookitMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }

                            Button(
                                onClick = {
                                    onMergeTables(
                                        selectedTables.filterValues { it }.keys.toList()
                                    )
                                },
                                enabled = !state.billingBusy && selectedTables.any { it.value }
                            ) {
                                Icon(Icons.Default.MergeType, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Fusionner les tables sélectionnées")
                            }
                        }
                    }

                    if (context.groupOrders.size > 1 && context.groupAmountDue > 0.0001) {
                        BillingSection("Paiement groupé") {
                            context.groupOrders.forEach { row ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        listOfNotNull(row.tableLabel, "#${row.orderNumber}").joinToString(" • "),
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(String.format(Locale.FRANCE, "%.2f €", row.amountDue))
                                }
                            }
                            HorizontalDivider(color = CookitLine)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = groupMethod == PosPaymentMethod.CASH,
                                    onClick = { groupMethod = PosPaymentMethod.CASH },
                                    label = { Text("Espèces") },
                                    leadingIcon = { Icon(Icons.Default.Payments, null) }
                                )
                                Spacer(Modifier.width(8.dp))
                                FilterChip(
                                    selected = groupMethod == PosPaymentMethod.CARD_TERMINAL,
                                    onClick = { groupMethod = PosPaymentMethod.CARD_TERMINAL },
                                    label = { Text("Carte / terminal") },
                                    leadingIcon = { Icon(Icons.Default.CreditCard, null) }
                                )
                            }
                            if (groupMethod == PosPaymentMethod.CASH) {
                                OutlinedTextField(
                                    value = groupTenderedText,
                                    onValueChange = { groupTenderedText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                                    label = { Text("Montant reçu") },
                                    singleLine = true
                                )
                                val tendered = groupTenderedText.replace(',', '.').toDoubleOrNull() ?: 0.0
                                Text(
                                    "Monnaie : ${String.format(Locale.FRANCE, "%.2f €", (tendered - context.groupAmountDue).coerceAtLeast(0.0))}",
                                    fontWeight = FontWeight.Bold,
                                    color = CookitGreen
                                )
                            }
                            Button(
                                onClick = {
                                    val tendered = if (groupMethod == PosPaymentMethod.CASH) {
                                        groupTenderedText.replace(',', '.').toDoubleOrNull()
                                    } else null
                                    onPayGroup(groupMethod, tendered)
                                },
                                enabled = !state.billingBusy
                            ) {
                                Icon(Icons.Default.Payments, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Encaisser le groupe • ${String.format(Locale.FRANCE, "%.2f €", context.groupAmountDue)}")
                            }
                        }
                    }
                }

                if (state.billingBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun BillingMetric(label: String, amount: Double, modifier: Modifier = Modifier, emphasize: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (emphasize) CookitSoftOrange else CookitCanvas
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = CookitMuted, fontSize = 11.sp)
            Text(
                String.format(Locale.FRANCE, "%.2f €", amount),
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = if (emphasize) CookitOrange else CookitInk
            )
        }
    }
}

@Composable
private fun BillingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, CookitLine)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Black)
            this.content()
        }
    }
}

@Composable
private fun MobileCartBar(
    modifier: Modifier,
    cart: List<CartLine>,
    t: UiStrings,
    busy: Boolean,
    canonicalTotal: Double? = null,
    locked: Boolean = false,
    onSendKitchen: () -> Unit,
    onBillingTools: () -> Unit,
    onCheckout: () -> Unit
) {
    val lineTotal = cart.sumOf { it.total }
    val total = canonicalTotal?.takeIf { it > 0 } ?: lineTotal
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${cart.sumOf { it.quantity }} articles", color = CookitMuted, fontSize = 12.sp)
                Text(String.format(Locale.FRANCE, "%.2f €", total), fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            if (!locked) {
                OutlinedButton(onClick = onSendKitchen, enabled = !busy && cart.isNotEmpty(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.SoupKitchen, null)
                    Spacer(Modifier.width(6.dp))
                    Text("KOT", fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(8.dp))
            } else {
                OutlinedButton(onClick = onBillingTools, enabled = !busy, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.CallSplit, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Split / Merge", fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = onCheckout, enabled = !busy && (cart.isNotEmpty() || total > 0), shape = RoundedCornerShape(14.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Default.Payments, null)
                Spacer(Modifier.width(8.dp))
                Text(t.checkout, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun OrderTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) CookitSoftOrange else Color.White,
        border = BorderStroke(1.dp, if (selected) CookitOrange else CookitLine)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (selected) CookitOrange else CookitMuted,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ProductPane(
    modifier: Modifier,
    categories: List<Category>,
    products: List<Product>,
    selectedCategory: Long,
    onCategory: (Long) -> Unit,
    onAdd: (Product) -> Unit
) {
    Column(modifier) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { category ->
                FilterChip(
                    selected = category.id == selectedCategory,
                    onClick = { onCategory(category.id) },
                    label = { Text("${category.emoji} ${category.name}") }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        val visibleProducts = products.filter {
            selectedCategory == 0L || it.categoryId == selectedCategory
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 180.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(visibleProducts, key = { it.id }) { product ->
                ProductCard(product, onAdd)
            }
        }
    }
}

@Composable
private fun ProductCard(product: Product, onAdd: (Product) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(226.dp)
            .clickable { onAdd(product) },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CookitLine)
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(142.dp)) {
                ProductImage(
                    product = product,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    shape = RoundedCornerShape(30.dp),
                    color = CookitOrange
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(7.dp).size(18.dp)
                    )
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    product.name,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Text(
                    String.format(Locale.FRANCE, "%.2f €", product.price),
                    color = CookitGreen,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp
                )
            }
        }
    }
}

private suspend fun loadRemoteBitmap(url: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (url.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection().apply {
                connectTimeout = 5000
                readTimeout = 8000
                setRequestProperty("User-Agent", "CookitPOS-Android")
            }
            connection.getInputStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull()
    }
}

@Composable
private fun ProductImage(product: Product, modifier: Modifier = Modifier.size(62.dp)) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, product.imageUrl) {
        value = loadRemoteBitmap(product.imageUrl)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        color = CookitCanvas
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = product.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(contentAlignment = Alignment.Center) { Text(product.emoji, fontSize = 26.sp) }
        }
    }
}

@Composable
private fun CartPane(
    modifier: Modifier,
    cart: List<CartLine>,
    t: UiStrings,
    busy: Boolean,
    tableLabel: String?,
    canonicalTotal: Double? = null,
    locked: Boolean = false,
    onPlus: (Long) -> Unit,
    onMinus: (Long) -> Unit,
    onSendKitchen: () -> Unit,
    onBillingTools: () -> Unit,
    onCheckout: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.cart, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                if (!tableLabel.isNullOrBlank()) {
                    Surface(shape = RoundedCornerShape(12.dp), color = CookitCanvas) {
                        Text(tableLabel, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (cart.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(t.emptyCart, color = CookitMuted)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(cart, key = { it.product.id }) { line ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.product.name, fontWeight = FontWeight.Bold)
                                Text(
                                    String.format(Locale.FRANCE, "%.2f €", line.total),
                                    color = CookitGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            FilledTonalIconButton(onClick = { onMinus(line.product.id) }, enabled = !locked) { Icon(Icons.Default.Remove, null) }
                            Text("${line.quantity}", Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.Black)
                            FilledTonalIconButton(onClick = { onPlus(line.product.id) }, enabled = !locked) { Icon(Icons.Default.Add, null) }
                        }
                        HorizontalDivider(color = CookitLine)
                    }
                }
            }

            val subtotal = cart.sumOf { it.total }
            val displayTotal = canonicalTotal?.takeIf { it > 0 } ?: subtotal
            SummaryLine(t.subtotal, subtotal)
            if (!locked) SummaryLine(t.vatIncluded, subtotal * 0.12, muted = true)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = CookitLine)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.total, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                Text(
                    String.format(Locale.FRANCE, "%.2f €", displayTotal),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = CookitGreen
                )
            }
            Spacer(Modifier.height(14.dp))
            if (!locked) {
                OutlinedButton(
                    onClick = onSendKitchen,
                    enabled = cart.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(17.dp)
                ) {
                    Icon(Icons.Default.SoupKitchen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Envoyer en cuisine (KOT)", fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(8.dp))
            } else {
                OutlinedButton(
                    onClick = onBillingTools,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(17.dp)
                ) {
                    Icon(Icons.Default.CallSplit, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Diviser / Fusionner", fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = onCheckout,
                enabled = !busy && (cart.isNotEmpty() || displayTotal > 0),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(17.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Default.Payments, null)
                Spacer(Modifier.width(8.dp))
                Text(t.checkout, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, amount: Double, muted: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = if (muted) CookitMuted else CookitInk)
        Spacer(Modifier.weight(1f))
        Text(
            String.format(Locale.FRANCE, "%.2f €", amount),
            color = if (muted) CookitMuted else CookitInk,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun OrdersScreen(
    state: PosUiState,
    onOpen: (PosOrder) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Commandes", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Touchez une commande non payée pour la rouvrir dans la caisse.", color = CookitMuted)

        state.orderLoadError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(error, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.orders, key = { it.id }) { order ->
                val paid = order.settlementStatus.lowercase(Locale.ROOT) in setOf("paid", "cancelled", "canceled", "refunded", "completed")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !paid && !state.orderLoadBusy) { onOpen(order) },
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, CookitLine)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (order.unread) {
                            Box(
                                Modifier.size(10.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CookitOrange)
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(order.code, fontWeight = FontWeight.Black)
                                Spacer(Modifier.width(8.dp))
                                Text(order.channel, color = CookitOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                order.table?.let {
                                    Spacer(Modifier.width(8.dp))
                                    Text(it, color = CookitMuted, fontSize = 12.sp)
                                }
                            }
                            Text(
                                "${order.customer} • ${relativeAge(order.minutesAgo)}",
                                color = CookitMuted,
                                fontSize = 12.sp
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = CookitSoftGreen
                                ) {
                                    Text(
                                        order.status,
                                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        color = CookitGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (paid) CookitSoftGreen else CookitSoftOrange
                                ) {
                                    Text(
                                        if (paid) "PAYÉ" else order.settlementStatus.uppercase(Locale.ROOT),
                                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        color = if (paid) CookitGreen else CookitOrange,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(
                                String.format(Locale.FRANCE, "%.2f €", order.total),
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun relativeAge(minutes: Int): String = when {
    minutes < 1 -> "à l'instant"
    minutes < 60 -> "il y a $minutes min"
    minutes < 1440 -> "il y a ${minutes / 60} h"
    else -> "il y a ${minutes / 1440} j"
}

private fun parseSplitAmounts(raw: String): List<Double> {
    val chunks = if (';' in raw) raw.split(';') else raw.split(',')
    return chunks
        .mapNotNull { it.trim().replace(',', '.').toDoubleOrNull() }
        .filter { it > 0.0 }
}


@Composable
private fun KdsScreen(
    state: PosUiState,
    t: UiStrings,
    onAdvance: (KotTicket) -> Unit
) {
    val activeTickets = state.kots.filter {
        it.status.lowercase(Locale.ROOT) !in setOf("served", "cancelled", "canceled")
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(t.kitchen, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Flux KOT réel Cookit : les changements mettent à jour le suivi de commande.", color = CookitMuted)
        state.kdsError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Spacer(Modifier.height(16.dp))

        if (activeTickets.isEmpty()) {
            EmptyOperationalState(Icons.Default.SoupKitchen, t.noKitchenTickets)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(activeTickets, key = { it.id }) { ticket ->
                    KotTicketCard(
                        ticket = ticket,
                        t = t,
                        busy = ticket.id in state.kdsBusyKotIds,
                        onAdvance = { onAdvance(ticket) }
                    )
                }
            }
        }
    }
}

@Composable
private fun KotTicketCard(
    ticket: KotTicket,
    t: UiStrings,
    busy: Boolean,
    onAdvance: () -> Unit
) {
    val step = when (ticket.status.lowercase(Locale.ROOT)) {
        "pending", "pending_confirmation" -> 0
        "in_kitchen", "cooking" -> 1
        "food_ready", "ready" -> 2
        "served" -> 3
        else -> 0
    }
    val nextLabel = when (step) {
        0 -> t.kitchenPreparing
        1 -> t.kitchenReady
        2 -> t.kitchenServed
        else -> null
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CookitLine)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("${ticket.orderCode} • KOT #${ticket.id}", fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text(
                        listOfNotNull(ticket.tableName, ticket.kitchenPlace).joinToString(" • ").ifBlank { "Cuisine" },
                        color = CookitMuted,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(12.dp), color = CookitSoftOrange) {
                    Text(
                        when (step) {
                            0 -> t.kitchenReceived
                            1 -> t.kitchenPreparing
                            2 -> t.kitchenReady
                            else -> t.kitchenServed
                        },
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = CookitOrange,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            ticket.items.forEach { item ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${item.quantity}×", fontWeight = FontWeight.Black, color = CookitOrange)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.Bold)
                        item.note?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = CookitMuted, fontSize = 11.sp)
                        }
                    }
                }
            }

            if (nextLabel != null) {
                Button(
                    onClick = onAdvance,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Icon(Icons.Default.ArrowForward, null)
                    Spacer(Modifier.width(8.dp))
                    Text("${t.nextStatus} $nextLabel", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DeliveryScreen(orders: List<PosOrder>, t: UiStrings) {
    val deliveryOrders = orders.filter { it.type == OrderType.DELIVERY }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(t.delivery, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Commandes livraison internes et plateformes dans le même flux.", color = CookitMuted)
        Spacer(Modifier.height(16.dp))

        if (deliveryOrders.isEmpty()) {
            EmptyOperationalState(Icons.Default.DeliveryDining, "Aucune livraison active")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(280.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(deliveryOrders, key = { it.id }) { order ->
                    OperationalOrderCard(order = order, accent = CookitGreen)
                }
            }
        }
    }
}

@Composable
private fun OperationalOrderCard(order: PosOrder, accent: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CookitLine)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(order.code, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.10f)) {
                    Text(
                        order.status,
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(order.channel, color = CookitOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(
                buildString {
                    append(order.customer)
                    order.table?.let { append(" • "); append(it) }
                },
                color = CookitMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            HorizontalDivider(color = CookitLine)
            Text(
                String.format(Locale.FRANCE, "%.2f €", order.total),
                fontWeight = FontWeight.Black,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
private fun EmptyOperationalState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = CookitMuted)
            Spacer(Modifier.width(12.dp))
            Text(message, color = CookitMuted)
        }
    }
}

@Composable
private fun CashScreen(state: PosUiState, vm: CookitPosViewModel, t: UiStrings) {
    var quantities by remember(state.cashDenominations) {
        mutableStateOf(state.cashDenominations.associate { it.label to 0 })
    }
    val total = state.cashDenominations.sumOf { d -> d.value * (quantities[d.label] ?: 0) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.activeCashSession == null) t.cashOpening else t.activeSession,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    if (state.activeCashSession == null) t.cashOpeningHelp
                    else "#${state.activeCashSession.id} • ${state.activeCashSession.status}",
                    color = CookitMuted
                )
            }
            Surface(shape = RoundedCornerShape(16.dp), color = CookitSoftOrange) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), horizontalAlignment = Alignment.End) {
                    Text(t.cashFund, color = CookitOrange, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(String.format(Locale.FRANCE, "%.2f €", total), fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(210.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.cashDenominations) { denomination ->
                val qty = quantities[denomination.label] ?: 0
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, CookitLine)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(denomination.label, Modifier.weight(1f), fontWeight = FontWeight.Black)
                        IconButton(onClick = {
                            quantities = quantities.toMutableMap().also { it[denomination.label] = (qty - 1).coerceAtLeast(0) }
                        }) { Icon(Icons.Default.RemoveCircleOutline, null) }
                        Text("$qty", fontWeight = FontWeight.Black)
                        IconButton(onClick = {
                            quantities = quantities.toMutableMap().also { it[denomination.label] = qty + 1 }
                        }) { Icon(Icons.Default.AddCircleOutline, null, tint = CookitOrange) }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::openDrawer, modifier = Modifier.height(54.dp)) {
                Icon(Icons.Default.PointOfSale, null)
                Spacer(Modifier.width(8.dp))
                Text(t.drawer)
            }
            if (state.activeCashSession == null) {
                Button(
                    onClick = { vm.openCashSession(total) },
                    modifier = Modifier.height(54.dp),
                    enabled = !state.cashBusy && (state.demoMode || state.cashRegisters.isNotEmpty())
                ) {
                    if (state.cashBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text(t.startService, fontWeight = FontWeight.Black)
                }
            }
        }
        state.printerMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                if (it == "drawer_ok") "✓ ${t.drawer}" else if (it == "failed") t.printerFailed else it,
                color = if (it == "failed") MaterialTheme.colorScheme.error else CookitGreen,
                fontSize = 12.sp
            )
        }
        if (!state.error.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(state.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DashboardScreen(state: PosUiState) {
    val orders = state.orders
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Bonjour ${state.user.name.substringBefore(' ')}", fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text("${state.user.restaurant} • ${state.user.branch}", color = CookitMuted)
        Spacer(Modifier.height(18.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { MetricCard("CA chargé", String.format(Locale.FRANCE, "%.2f €", orders.sumOf { it.total }), "session courante", Icons.Default.TrendingUp) }
            item { MetricCard("Commandes", orders.size.toString(), "flux API", Icons.Default.ReceiptLong) }
            item { MetricCard("En cuisine", orders.count { it.status == "En cuisine" }.toString(), "à préparer", Icons.Default.SoupKitchen) }
            item { MetricCard("Livraisons", orders.count { it.type == OrderType.DELIVERY }.toString(), "commandes delivery", Icons.Default.DeliveryDining) }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, note: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, CookitLine)
    ) {
        Column(Modifier.padding(18.dp)) {
            Icon(icon, null, tint = CookitOrange)
            Spacer(Modifier.height(16.dp))
            Text(title, color = CookitMuted, fontSize = 12.sp)
            Text(value, fontWeight = FontWeight.Black, fontSize = 24.sp)
            Text(note, color = CookitGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsScreen(
    state: PosUiState,
    vm: CookitPosViewModel,
    t: UiStrings,
    onRefresh: () -> Unit,
    onLogout: () -> Unit
) {
    var printerHost by remember(state.printerHost) { mutableStateOf(state.printerHost) }
    var printerPort by remember(state.printerPort) { mutableStateOf(state.printerPort.toString()) }
    var starIdentifier by remember(state.starIdentifier) { mutableStateOf(state.starIdentifier) }
    var starInterface by remember(state.starInterface) { mutableStateOf(state.starInterface) }
    var fdmHost by remember(state.fdmSettings.host) { mutableStateOf(state.fdmSettings.host) }
    var fdmPort by remember(state.fdmSettings.port) { mutableStateOf(state.fdmSettings.port.toString()) }
    var mockFdmMode by remember(state.fdmSettings.provider) {
        mutableStateOf(BuildConfig.ENABLE_MOCK_FDM && state.fdmSettings.isMock)
    }
    var mockScenario by remember { mutableStateOf("success") }
    var fiscalAgentDeviceId by remember(state.fiscalAgentDeviceHint) { mutableStateOf(state.fiscalAgentDeviceHint) }
    var fiscalAgentToken by remember { mutableStateOf("") }
    val context = LocalContext.current
    var pendingPrinterAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingPrinterAction
        pendingPrinterAction = null
        if (result.values.all { it }) action?.invoke()
    }

    fun permissionsFor(interfaceType: StarInterfaceType): Array<String> = when (interfaceType) {
        StarInterfaceType.LAN -> if (Build.VERSION.SDK_INT >= 37) arrayOf("android.permission.ACCESS_LOCAL_NETWORK") else emptyArray()
        StarInterfaceType.BLUETOOTH, StarInterfaceType.BLUETOOTH_LE -> if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else emptyArray()
        StarInterfaceType.USB -> emptyArray()
    }

    fun runWithPrinterPermissions(interfaceType: StarInterfaceType, action: () -> Unit) {
        val missing = permissionsFor(interfaceType).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action() else {
            pendingPrinterAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(t.settings, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(if (state.demoMode) "Mode démo local" else t.settingsHelp, color = CookitMuted)

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, CookitLine)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Language, null, tint = CookitInk)
                    Spacer(Modifier.width(10.dp))
                    Text(t.language, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.entries.forEach { language ->
                        FilterChip(
                            selected = state.language == language,
                            onClick = { vm.setLanguage(language) },
                            label = { Text(language.code.uppercase(Locale.ROOT)) }
                        )
                    }
                }
            }
        }

        SettingsRow(Icons.Default.Sync, t.synchronization, if (state.online) "API Cookit ${t.online.lowercase()}" else t.offline)
        SettingsRow(Icons.Default.Person, t.account, "${state.user.name} • ${state.user.role.name.lowercase()}")
        SettingsRow(Icons.Default.Security, t.permissions, "${state.policy.profile.name.lowercase()}")

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, CookitLine)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null, tint = CookitInk)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Runtime fiscal local", fontWeight = FontWeight.Bold)
                        Text(
                            if (state.fiscalLocalDbError == null) "Room / SQLite durable • A14.2A" else "SQLite indisponible",
                            color = if (state.fiscalLocalDbError == null) CookitGreen else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }

                val fiscalIdentity = state.fiscalIdentity
                if (fiscalIdentity != null) {
                    Text("runtime_id  ${fiscalIdentity.runtimeId}", fontSize = 11.sp, color = CookitMuted)
                    Text("terminal_id ${fiscalIdentity.terminalId}", fontSize = 11.sp, color = CookitMuted)
                    val scope = listOfNotNull(
                        fiscalIdentity.restaurantId?.let { "restaurant=$it" },
                        fiscalIdentity.branchId?.let { "branch=$it" }
                    ).joinToString(" • ")
                    if (scope.isNotBlank()) Text(scope, fontSize = 11.sp, color = CookitMuted)
                } else if (state.fiscalLocalDbError == null) {
                    Text("Initialisation de l’identité fiscale locale…", fontSize = 11.sp, color = CookitMuted)
                }

                state.fiscalLocalDbError?.let {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                val health = state.fiscalOutboxHealth
                Text(
                    "Outbox locale • préparés ${health.prepared} • à synchroniser ${health.pending} • cloud ${health.cloudQueued} • fiscalisés ${health.fiscalized}",
                    fontSize = 11.sp,
                    color = CookitMuted
                )
                health.latestError?.let { lastError ->
                    Text(
                        "Dernier état sync: ${lastError.take(180)}",
                        fontSize = 11.sp,
                        color = CookitMuted
                    )
                }
                Text(
                    when (state.fiscalSyncMessage) {
                        "profile_off" -> "Profil fiscal cloud OFF : l’outbox reste durable et réessaiera sans activer le profil."
                        "cloud_queued" -> "Événements locaux remis à CookitFiscal."
                        "retry" -> "Synchronisation fiscale en attente de reconnexion / retry."
                        else -> "A14.2 : identité + outbox durable + reprise après crash."
                    },
                    fontSize = 11.sp,
                    color = CookitMuted
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, CookitLine)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, null, tint = CookitInk)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (state.fdmSettings.isMock) "Mock FDM A14.4.1" else "Checkbox / Eutronix FDM", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                !state.fdmSettings.configured -> "Transport local non configuré"
                                state.fdmSettings.isMock -> "Harness GraphQL embarqué sur la tablette"
                                else -> "Transport HTTPS /graphql configuré"
                            },
                            color = if (state.fdmSettings.configured) CookitGreen else CookitMuted,
                            fontSize = 12.sp
                        )
                    }
                    if (!state.policy.canManageSettings) Icon(Icons.Default.Lock, null, tint = CookitMuted)
                }

                if (BuildConfig.ENABLE_MOCK_FDM && (state.policy.canManageSettings || state.demoMode)) {
                    FilterChip(
                        selected = mockFdmMode,
                        onClick = {
                            mockFdmMode = !mockFdmMode
                            if (mockFdmMode) {
                                fdmHost = EmbeddedMockFdmContract.HOST
                                fdmPort = EmbeddedMockFdmContract.PORT.toString()
                            } else {
                                if (fdmHost == EmbeddedMockFdmContract.HOST) fdmHost = ""
                                if (fdmPort == EmbeddedMockFdmContract.PORT.toString()) fdmPort = "443"
                            }
                        },
                        label = { Text("Mode Mock A14.4.1 embarqué (debug uniquement)") },
                        leadingIcon = { Icon(Icons.Default.BugReport, null, Modifier.size(16.dp)) }
                    )
                }

                if (state.policy.canManageSettings || state.demoMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = fdmHost,
                            onValueChange = { fdmHost = it.trim() },
                            label = { Text(if (mockFdmMode) "Loopback Android" else "FDM host / IP") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = !mockFdmMode
                        )
                        OutlinedTextField(
                            value = fdmPort,
                            onValueChange = { fdmPort = it.filter(Char::isDigit).take(5) },
                            label = { Text(if (mockFdmMode) "Port local" else "Port TLS") },
                            modifier = Modifier.width(120.dp),
                            singleLine = true,
                            enabled = !mockFdmMode
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.saveFdmSettings(fdmHost, fdmPort, mockFdmMode) }) {
                            Text(if (mockFdmMode) "Activer Mock embarqué" else "Enregistrer FDM")
                        }
                        OutlinedButton(onClick = { vm.probeFdmConnectivity() }, enabled = !state.fdmProbeBusy) {
                            if (state.fdmProbeBusy) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("Tester GraphQL")
                        }
                    }
                    if (BuildConfig.ENABLE_MOCK_FDM && mockFdmMode) {
                        val embedded = state.embeddedMockFdmStatus
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (embedded.running) {
                                    "Mock embarqué actif • ${embedded.host}:${embedded.port}"
                                } else {
                                    "Mock embarqué arrêté${embedded.lastError?.let { " • ${it.take(100)}" } ?: ""}"
                                },
                                fontSize = 11.sp,
                                color = if (embedded.running) CookitGreen else MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            if (!embedded.running) {
                                OutlinedButton(onClick = { vm.restartEmbeddedMockFdm() }) { Text("Redémarrer") }
                            }
                        }
                    }
                    if (!state.fdmSettings.isMock) {
                        OutlinedButton(onClick = { vm.verifyFdmAdapterGate() }) {
                            Text("Vérifier le verrou signSale")
                        }
                    }
                }

                Text(
                    if (state.fdmSettings.isMock) {
                        "Mutation de test : ${state.fdmReadiness.mutationName}. Mapping Mock : ${if (state.fdmReadiness.mappingInstalled) "actif" else "indisponible"}. Aucune valeur de ce harness n’est une preuve fiscale."
                    } else {
                        "Mutation attendue pour une vente normale : ${state.fdmReadiness.mutationName}. Mapping certifié : ${if (state.fdmReadiness.mappingInstalled) "installé" else "verrouillé"}."
                    },
                    fontSize = 11.sp,
                    color = if (state.fdmReadiness.mappingInstalled) CookitGreen else CookitMuted
                )
                if (state.fdmProbe.attempted) {
                    val probe = state.fdmProbe
                    Text(
                        buildString {
                            when {
                                probe.tlsConnected -> append("TLS OK")
                                probe.mockCleartextConnected -> append("Mock local HTTP OK")
                                else -> append("Transport échec")
                            }
                            probe.httpStatus?.let { append(" • HTTP $it") }
                            if (probe.graphqlResponded) append(" • GraphQL détecté")
                            probe.latencyMs?.let { append(" • ${it} ms") }
                        },
                        fontSize = 11.sp,
                        color = if (probe.transportReady) CookitGreen else MaterialTheme.colorScheme.error
                    )
                    probe.certificateSha256?.let { fingerprint ->
                        Text("Certificat SHA-256 ${fingerprint.take(16)}…${fingerprint.takeLast(12)}", fontSize = 10.sp, color = CookitMuted)
                    }
                    probe.message?.let { Text(it, fontSize = 10.sp, color = CookitMuted) }
                }

                if (BuildConfig.ENABLE_MOCK_FDM && state.fdmSettings.isMock) {
                    HorizontalDivider(color = CookitLine)
                    Text("Campagne Runtime PASS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        "Le bouton utilise le dernier événement réel de l’outbox locale, vérifie son SHA-256 puis l’envoie au Mock sans changer son statut fiscal.",
                        fontSize = 10.sp,
                        color = CookitMuted
                    )
                    val scenarios = listOf(
                        "success" to "Succès",
                        "lost_response" to "Réponse perdue",
                        "graphql_error" to "Erreur GraphQL",
                        "http_500" to "HTTP 500",
                        "malformed" to "JSON invalide",
                        "auth_required" to "401",
                        "slow" to "Lent 3 s",
                        "timeout" to "Timeout"
                    )
                    scenarios.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (key, label) ->
                                FilterChip(
                                    selected = mockScenario == key,
                                    onClick = { mockScenario = key },
                                    label = { Text(label, fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Button(
                        onClick = { vm.runMockFdmTest(mockScenario) },
                        enabled = !state.mockFdmBusy && state.fdmSettings.configured && !state.demoMode
                    ) {
                        if (state.mockFdmBusy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("Tester le dernier événement fiscal")
                    }
                    val mockStatus = when {
                        state.mockFdmMessage == null -> "Effectue d’abord une commande payée de test pour alimenter l’outbox."
                        state.mockFdmMessage == "mock_no_event" -> "Aucun événement fiscal local disponible : effectue une commande payée de test."
                        state.mockFdmMessage == "mock_not_configured" -> "Active le Mock embarqué 127.0.0.1:8787."
                        state.mockFdmMessage == "mock_identity_missing" -> "Runtime fiscal non lié à un restaurant/une branche."
                        state.mockFdmMessage == "mock_forbidden" -> "Test Mock réservé à un compte autorisé hors mode démo."
                        state.mockFdmMessage == "mock_disabled" -> "Mock FDM désactivé dans ce build."
                        state.mockFdmMessage.startsWith("mock_embedded_unavailable:") -> "Mock embarqué indisponible : ${state.mockFdmMessage.substringAfter(':').take(160)}"
                        state.mockFdmMessage.startsWith("mock_running:") -> "Scénario ${state.mockFdmMessage.substringAfter(':')} en cours…"
                        state.mockFdmMessage.startsWith("mock_local_hash_mismatch:") -> "STOP : SHA-256 local invalide pour ${state.mockFdmMessage.substringAfter(':')}."
                        state.mockFdmMessage.startsWith("mock_ok:") -> {
                            val parts = state.mockFdmMessage.split(':')
                            "PASS Mock • event ${parts.getOrNull(1).orEmpty()} • reçu ${parts.getOrNull(2).orEmpty()} • duplicate=${parts.getOrNull(3).orEmpty()}"
                        }
                        state.mockFdmMessage.startsWith("mock_failed:") -> "Résultat attendu/erreur Mock : ${state.mockFdmMessage.substringAfter(':').take(220)}"
                        else -> state.mockFdmMessage
                    }
                    Text(mockStatus, fontSize = 10.sp, color = if (state.mockFdmMessage?.startsWith("mock_ok:") == true) CookitGreen else CookitMuted)
                }

                Text(
                    if (state.fdmSettings.isMock) {
                        "Le Mock A14.4.1 est embarqué uniquement dans le build debug et écoute sur 127.0.0.1. Les builds release n’exécutent aucun serveur Mock. Le vrai adapter Checkbox/Eutronix reste verrouillé."
                    } else {
                        "Le test réseau est non fiscalisant : il ne lance aucune mutation signSale/signOrder. Cookit ne fiscalise rien tant que le mapping certifié n’est pas installé."
                    },
                    fontSize = 11.sp,
                    color = CookitMuted
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, CookitLine)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VpnKey, null, tint = CookitInk)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Cookit Fiscal Agent", fontWeight = FontWeight.Bold)
                        Text(
                            if (state.fiscalAgentConfigured) "Identifiants appareil chiffrés dans Android Keystore" else "Appareil fiscal non provisionné",
                            color = if (state.fiscalAgentConfigured) CookitGreen else CookitMuted,
                            fontSize = 12.sp
                        )
                    }
                    if (!state.policy.canManageSettings) Icon(Icons.Default.Lock, null, tint = CookitMuted)
                }

                if (state.fiscalAgentConfigured) {
                    Text("device ${state.fiscalAgentDeviceHint}", fontSize = 11.sp, color = CookitMuted)
                }

                if (state.policy.canManageSettings && !state.demoMode) {
                    OutlinedTextField(
                        value = fiscalAgentDeviceId,
                        onValueChange = { fiscalAgentDeviceId = it.trim() },
                        label = { Text("Fiscal Agent device ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = fiscalAgentToken,
                        onValueChange = { fiscalAgentToken = it },
                        label = { Text("Device token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            vm.saveFiscalAgentCredentials(fiscalAgentDeviceId, fiscalAgentToken)
                            fiscalAgentToken = ""
                        }) { Text("Enregistrer sécurisé") }
                        OutlinedButton(onClick = { vm.handshakeFiscalAgent() }, enabled = state.fiscalAgentConfigured) {
                            Text("Handshake")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.heartbeatFiscalAgent() }, enabled = state.fiscalAgentConfigured) {
                            Text("Heartbeat")
                        }
                        OutlinedButton(
                            onClick = { vm.processNextFiscalAgentJob() },
                            enabled = state.fiscalAgentConfigured && !state.fiscalAgentBusy && !state.fiscalAgentAutoRunning
                        ) {
                            Text("Traiter 1 job")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.fiscalAgentAutoRunning) {
                            Button(onClick = { vm.stopFiscalAgentAuto() }) {
                                Text("Arrêter auto")
                            }
                        } else {
                            Button(
                                onClick = { vm.startFiscalAgentAuto() },
                                enabled = state.fiscalAgentConfigured && !state.fiscalAgentBusy
                            ) {
                                Text("Démarrer auto")
                            }
                        }
                        TextButton(onClick = { vm.clearFiscalAgentCredentials() }, enabled = state.fiscalAgentConfigured) {
                            Text("Effacer les credentials")
                        }
                    }
                    if (state.fiscalAgentConfigured) {
                        Text(
                            "C4 • auto=${if (state.fiscalAgentAutoRunning) "ON" else "OFF"} • service=${if (state.fiscalAgentServiceRunning) "RUNNING" else "STOPPED"} • traités=${state.fiscalAgentProcessedJobs}" +
                                (state.fiscalAgentLastJobId?.let { " • dernier job #$it" } ?: "") +
                                (state.fiscalAgentLastReceipt?.let { " • reçu $it" } ?: ""),
                            fontSize = 10.sp,
                            color = if (state.fiscalAgentAutoRunning) CookitGreen else CookitMuted
                        )
                    }
                }

                val agentStatus = when {
                    state.fiscalAgentMessage == null -> "C4 prêt : le mode Auto exécute désormais le Fiscal Agent dans un service Android de premier plan indépendant de l’écran POS."
                    state.fiscalAgentMessage == "credentials_saved" -> "Identifiants Fiscal Agent enregistrés dans Android Keystore."
                    state.fiscalAgentMessage == "credentials_cleared" -> "Identifiants Fiscal Agent supprimés."
                    state.fiscalAgentMessage == "handshake_running" -> "Handshake Fiscal Agent en cours…"
                    state.fiscalAgentMessage == "handshake_ok" -> "Handshake Fiscal Agent réussi."
                    state.fiscalAgentMessage == "heartbeat_running" -> "Heartbeat Fiscal Agent en cours…"
                    state.fiscalAgentMessage == "heartbeat_ok" -> "Heartbeat Fiscal Agent réussi."
                    state.fiscalAgentMessage == "service_starting" -> "C4 : démarrage du service Fiscal Agent…"
                    state.fiscalAgentMessage == "service_running" -> "C4 : service Fiscal Agent actif en arrière-plan."
                    state.fiscalAgentMessage == "service_restarting" -> "C4 : Android redémarre le service Fiscal Agent…"
                    state.fiscalAgentMessage == "job_polling" -> "C4 : récupération manuelle du prochain job fiscal cloud…"
                    state.fiscalAgentMessage == "job_idle" -> "C4 : service actif, aucun job fiscal en attente."
                    state.fiscalAgentMessage == "auto_started" -> "C4 : service Fiscal Agent démarré."
                    state.fiscalAgentMessage == "auto_resumed" -> "C4 : service Fiscal Agent restauré après redémarrage."
                    state.fiscalAgentMessage == "auto_polling" -> "C4 : service Fiscal Agent actif, polling cloud…"
                    state.fiscalAgentMessage == "auto_stopped" -> "C4 : service Fiscal Agent arrêté."
                    state.fiscalAgentMessage == "credentials_incomplete" -> "Device ID et token sont requis."
                    state.fiscalAgentMessage == "credentials_or_identity_missing" -> "Identité runtime ou credentials Fiscal Agent manquants."
                    state.fiscalAgentMessage == "forbidden" -> "Action réservée à un compte autorisé hors mode démo."
                    state.fiscalAgentMessage.startsWith("job_ok:") -> {
                        val parts = state.fiscalAgentMessage.split(':')
                        "C4 PASS • job #${parts.getOrNull(1).orEmpty()} • reçu ${parts.getOrNull(2).orEmpty()} • duplicate=${parts.getOrNull(3).orEmpty()} • replayLocal=${parts.getOrNull(4).orEmpty()}"
                    }
                    state.fiscalAgentMessage.startsWith("job_failed:") -> "C4 job/retry : ${state.fiscalAgentMessage.substringAfter(':').take(220)}"
                    state.fiscalAgentMessage.startsWith("agent_provider_gated:") -> "Adapter FDM verrouillé : ${state.fiscalAgentMessage.substringAfter(':').take(200)}"
                    state.fiscalAgentMessage.startsWith("agent_mock_unavailable:") -> "Mock FDM embarqué indisponible : ${state.fiscalAgentMessage.substringAfter(':').take(160)}"
                    state.fiscalAgentMessage.startsWith("handshake_failed:") -> "Handshake échoué : ${state.fiscalAgentMessage.substringAfter(':').take(160)}"
                    state.fiscalAgentMessage.startsWith("heartbeat_failed:") -> "Heartbeat échoué : ${state.fiscalAgentMessage.substringAfter(':').take(160)}"
                    else -> state.fiscalAgentMessage
                }
                Text(agentStatus, fontSize = 11.sp, color = CookitMuted)
                Text(
                    "C4 exécute cloud → service Android → adapter FDM → journal Room durable → submitted/ack cloud, même lorsque l’écran POS est fermé. Le Mock reste debug-only ; l’adapter Checkbox/Eutronix reste fail-closed jusqu’au mapping certifié.",
                    fontSize = 10.sp,
                    color = CookitMuted
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, CookitLine)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Print, null, tint = CookitInk)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.printers, fontWeight = FontWeight.Bold)
                        Text(
                            "ESC/POS universel ou StarIO10 natif — même stratégie que CookitPad",
                            color = CookitMuted,
                            fontSize = 12.sp
                        )
                    }
                    if (!state.policy.canManagePrinters) Icon(Icons.Default.Lock, null, tint = CookitMuted)
                }

                if (state.policy.canManagePrinters || state.demoMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.printerProvider == PrinterProviderType.ESC_POS,
                            onClick = { vm.setPrinterProvider(PrinterProviderType.ESC_POS) },
                            label = { Text("ESC/POS") }
                        )
                        FilterChip(
                            selected = state.printerProvider == PrinterProviderType.STAR,
                            onClick = { vm.setPrinterProvider(PrinterProviderType.STAR) },
                            label = { Text("Star Micronics") }
                        )
                    }

                    if (state.printerProvider == PrinterProviderType.ESC_POS) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = printerHost,
                                onValueChange = { printerHost = it },
                                label = { Text(t.printerHost) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = printerPort,
                                onValueChange = { printerPort = it.filter(Char::isDigit).take(5) },
                                label = { Text(t.printerPort) },
                                modifier = Modifier.width(120.dp),
                                singleLine = true
                            )
                        }
                        OutlinedButton(onClick = { vm.savePrinter(printerHost, printerPort) }) {
                            Text("Enregistrer")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StarInterfaceType.entries.forEach { interfaceType ->
                                FilterChip(
                                    selected = starInterface == interfaceType,
                                    onClick = {
                                        starInterface = interfaceType
                                        vm.saveStarPrinter(starIdentifier, starInterface)
                                    },
                                    label = {
                                        Text(
                                            when (interfaceType) {
                                                StarInterfaceType.LAN -> "LAN"
                                                StarInterfaceType.BLUETOOTH -> "Bluetooth"
                                                StarInterfaceType.BLUETOOTH_LE -> "Bluetooth LE"
                                                StarInterfaceType.USB -> "USB"
                                            }
                                        )
                                    }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = starIdentifier,
                            onValueChange = { starIdentifier = it },
                            label = { Text("Identifiant Star (IP / MAC / nom / série USB)") },
                            supportingText = { Text("Ex. 192.168.1.40 ou adresse MAC") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.saveStarPrinter(starIdentifier, starInterface) }) {
                                Text("Enregistrer Star")
                            }
                            OutlinedButton(
                                onClick = {
                                    runWithPrinterPermissions(starInterface) {
                                        vm.discoverStarPrinters(starInterface)
                                    }
                                },
                                enabled = !state.printerDiscoveryBusy
                            ) {
                                if (state.printerDiscoveryBusy) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (state.printerDiscoveryBusy) "Recherche…" else "Rechercher")
                            }
                        }

                        if (state.discoveredPrinters.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Imprimantes Star détectées", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                state.discoveredPrinters.forEach { printer ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                starIdentifier = printer.identifier
                                                starInterface = printer.interfaceType
                                                vm.selectDiscoveredPrinter(printer)
                                            },
                                        color = CookitCanvas,
                                        border = BorderStroke(1.dp, CookitLine)
                                    ) {
                                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Print, null, tint = CookitOrange, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(printer.identifier, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                Text(printer.interfaceType.name.replace('_', ' '), color = CookitMuted, fontSize = 10.sp)
                                            }
                                            Icon(Icons.Default.ChevronRight, null, tint = CookitMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (state.printerProvider == PrinterProviderType.ESC_POS) {
                                vm.savePrinter(printerHost, printerPort)
                                vm.testPrinter()
                            } else {
                                vm.saveStarPrinter(starIdentifier, starInterface)
                                runWithPrinterPermissions(starInterface) { vm.testPrinter() }
                            }
                        }) {
                            Text(
                                if (state.printerProvider == PrinterProviderType.STAR)
                                    "Tester Star"
                                else t.testPrinter
                            )
                        }
                        OutlinedButton(onClick = {
                            if (state.printerProvider == PrinterProviderType.STAR) {
                                runWithPrinterPermissions(starInterface) { vm.openDrawer() }
                            } else {
                                vm.openDrawer()
                            }
                        }) {
                            Icon(Icons.Default.PointOfSale, null)
                            Spacer(Modifier.width(6.dp))
                            Text(t.drawer)
                        }
                    }

                    state.printerMessage?.let { result ->
                        Text(
                            when (result) {
                                "ok", "drawer_ok", "selected" -> t.printerReady
                                "discovery_ok" -> "Imprimante(s) détectée(s)"
                                "discovery_empty" -> "Aucune imprimante Star détectée"
                                else -> t.printerFailed
                            },
                            color = if (result == "failed") MaterialTheme.colorScheme.error else CookitGreen,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRefresh, enabled = !state.demoMode && !state.loading) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text(t.refresh)
            }
            Button(onClick = onLogout) {
                Icon(Icons.Default.Logout, null)
                Spacer(Modifier.width(6.dp))
                Text(t.logout)
            }
        }
        if (!state.error.isNullOrBlank()) {
            Text(state.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    locked: Boolean = false
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CookitLine)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = CookitCanvas) {
                Icon(icon, null, modifier = Modifier.padding(10.dp), tint = CookitInk)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = CookitMuted, fontSize = 12.sp)
            }
            if (locked) Icon(Icons.Default.Lock, null, tint = CookitMuted)
            else Icon(Icons.Default.ChevronRight, null, tint = CookitMuted)
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(20.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(26.dp)) {
                Text(title, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(subtitle, color = CookitMuted)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Écran de parité préparé pour la vague suivante.",
                    color = CookitOrange,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
