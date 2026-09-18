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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import be.cookit.pos.android.data.DemoRepository
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
                ScreenContent(screen, state, vm, t)
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
            ) { ScreenContent(screen, state, vm, t) }
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
private fun ScreenContent(screen: Screen, state: PosUiState, vm: CookitPosViewModel, t: UiStrings) {
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
        Screen.ORDERS -> OrdersScreen(state.orders)
        Screen.CASH -> CashScreen(state, vm, t)
        Screen.DASHBOARD -> DashboardScreen(state)
        Screen.KDS -> KdsScreen(state.orders, t)
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
            Text(t.newOrder, fontWeight = FontWeight.Black, fontSize = 26.sp)
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
        } else if (state.checkoutMessage == "paid") {
            Text(t.orderPaid, color = CookitGreen, fontWeight = FontWeight.Bold)
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
                    onPlus = vm::incrementProduct,
                    onMinus = vm::decrementProduct,
                    onCheckout = vm::requestCheckout
                )
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                ProductPane(
                    modifier = Modifier.fillMaxSize().padding(bottom = if (cart.isEmpty()) 0.dp else 84.dp),
                    categories = categories,
                    products = products,
                    selectedCategory = selectedCategory,
                    onCategory = { selectedCategory = it },
                    onAdd = vm::addProduct
                )
                if (cart.isNotEmpty()) {
                    MobileCartBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        cart = cart,
                        t = t,
                        busy = state.checkoutBusy,
                        onCheckout = vm::requestCheckout
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
}

@Composable
private fun PaymentMethodDialog(
    state: PosUiState,
    t: UiStrings,
    onDismiss: () -> Unit,
    onConfirm: (PosPaymentMethod) -> Unit
) {
    var selected by remember(state.paymentSheetOpen) { mutableStateOf(PosPaymentMethod.CASH) }
    val total = state.draftCart.sumOf { it.total }

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

                if (state.pendingRemoteOrderId != null) {
                    Surface(shape = RoundedCornerShape(12.dp), color = CookitSoftOrange) {
                        Text(
                            "${t.pendingOrder} #${state.pendingRemoteOrderId}",
                            Modifier.padding(10.dp),
                            color = CookitOrange,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                PaymentChoice(
                    icon = Icons.Default.Payments,
                    title = t.cashPayment,
                    subtitle = t.cashPaymentHelp,
                    selected = selected == PosPaymentMethod.CASH,
                    onClick = { selected = PosPaymentMethod.CASH }
                )
                PaymentChoice(
                    icon = Icons.Default.CreditCard,
                    title = t.cardTerminalPayment,
                    subtitle = t.terminalHelp,
                    selected = selected == PosPaymentMethod.CARD_TERMINAL,
                    onClick = { selected = PosPaymentMethod.CARD_TERMINAL }
                )
                if (selected == PosPaymentMethod.CARD_TERMINAL) {
                    Text(t.terminalConfirmHelp, color = CookitMuted, fontSize = 12.sp)
                }

                if (!state.checkoutError.isNullOrBlank()) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            state.checkoutError,
                            Modifier.padding(10.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected) },
                enabled = !state.checkoutBusy
            ) {
                if (state.checkoutBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (state.pendingRemoteOrderId != null) t.retryPayment else t.confirmPayment,
                    fontWeight = FontWeight.Black
                )
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
private fun MobileCartBar(
    modifier: Modifier,
    cart: List<CartLine>,
    t: UiStrings,
    busy: Boolean,
    onCheckout: () -> Unit
) {
    val total = cart.sumOf { it.total }
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
            Button(onClick = onCheckout, enabled = !busy, shape = RoundedCornerShape(14.dp)) {
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
            .height(190.dp)
            .clickable { onAdd(product) },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CookitLine)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ProductImage(product)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.AddCircle, null, tint = CookitOrange)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                product.name,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                product.description,
                color = CookitMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Text(
                String.format(Locale.FRANCE, "%.2f €", product.price),
                color = CookitGreen,
                fontWeight = FontWeight.Black,
                fontSize = 17.sp
            )
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
private fun ProductImage(product: Product) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, product.imageUrl) {
        value = loadRemoteBitmap(product.imageUrl)
    }

    Surface(
        modifier = Modifier.size(62.dp),
        shape = RoundedCornerShape(16.dp),
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
    onPlus: (Long) -> Unit,
    onMinus: (Long) -> Unit,
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
                            FilledTonalIconButton(onClick = { onMinus(line.product.id) }) { Icon(Icons.Default.Remove, null) }
                            Text("${line.quantity}", Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.Black)
                            FilledTonalIconButton(onClick = { onPlus(line.product.id) }) { Icon(Icons.Default.Add, null) }
                        }
                        HorizontalDivider(color = CookitLine)
                    }
                }
            }

            val subtotal = cart.sumOf { it.total }
            SummaryLine(t.subtotal, subtotal)
            SummaryLine(t.vatIncluded, subtotal * 0.12, muted = true)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = CookitLine)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.total, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                Text(
                    String.format(Locale.FRANCE, "%.2f €", subtotal),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = CookitGreen
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onCheckout,
                enabled = cart.isNotEmpty() && !busy,
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
private fun OrdersScreen(orders: List<PosOrder>) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Commandes", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("QR, web, POS et livraison dans une vue unique.", color = CookitMuted)
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(orders) { order ->
                Card(
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
                            Row {
                                Text(order.code, fontWeight = FontWeight.Black)
                                Spacer(Modifier.width(8.dp))
                                Text(order.channel, color = CookitOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Text(
                                "${order.customer}${order.table?.let { " • $it" } ?: ""} • il y a ${order.minutesAgo} min",
                                color = CookitMuted,
                                fontSize = 12.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CookitSoftGreen
                        ) {
                            Text(
                                order.status,
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = CookitGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(16.dp))
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


@Composable
private fun KdsScreen(orders: List<PosOrder>, t: UiStrings) {
    val kitchenOrders = orders.filter {
        it.status in setOf("Nouveau", "Confirmé", "En cuisine", "Prêt")
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(t.kitchen, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Flux cuisine synchronisé avec les commandes Cookit.", color = CookitMuted)
        Spacer(Modifier.height(16.dp))

        if (kitchenOrders.isEmpty()) {
            EmptyOperationalState(Icons.Default.SoupKitchen, "Aucun ticket cuisine actif")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(260.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(kitchenOrders, key = { it.id }) { order ->
                    OperationalOrderCard(order = order, accent = CookitOrange)
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
