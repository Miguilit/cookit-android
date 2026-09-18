package be.cookit.pos.android.ui

import android.graphics.BitmapFactory
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

    var screen by remember { mutableStateOf(Screen.POS) }
    val widthDp = LocalConfiguration.current.screenWidthDp
    val tablet = widthDp >= 840

    if (tablet) {
        Row(Modifier.fillMaxSize().background(CookitCanvas)) {
            SideNavigation(screen = screen, t = t, onSelect = { screen = it })
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
                    listOf(Screen.POS, Screen.ORDERS, Screen.CASH, Screen.SETTINGS).forEach {
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
                        Text("Android • A3–A5 Live", color = CookitMuted)
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
private fun SideNavigation(screen: Screen, t: UiStrings, onSelect: (Screen) -> Unit) {
    Surface(
        modifier = Modifier.width(104.dp).fillMaxHeight(),
        color = Color(0xFF152019)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = RoundedCornerShape(18.dp),
                color = CookitOrange
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("C", color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            listOf(
                Screen.DASHBOARD, Screen.POS, Screen.ORDERS,
                Screen.KDS, Screen.DELIVERY, Screen.CASH, Screen.SETTINGS
            ).forEach { item ->
                val selected = item == screen
                Column(
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onSelect(item) }
                        .padding(vertical = 10.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        iconFor(item),
                        contentDescription = screenLabel(item, t),
                        tint = if (selected) CookitOrange else Color(0xFFD7DDD9)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        screenLabel(item, t),
                        color = if (selected) Color.White else Color(0xFFB9C0BC),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
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
        Screen.DASHBOARD -> DashboardScreen(state.orders)
        Screen.KDS -> PlaceholderScreen("Cuisine", "KDS / tickets cuisine")
        Screen.DELIVERY -> PlaceholderScreen("Livraison", "Commandes internes et plateformes")
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
    var cart by remember { mutableStateOf(emptyList<CartLine>()) }
    var orderType by remember { mutableStateOf(OrderType.DINE_IN) }
    var selectedTableId by remember(state.tables) {
        mutableStateOf(state.tables.firstOrNull { it.available }?.id)
    }
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp >= 900

    LaunchedEffect(state.checkoutNonce) {
        if (state.checkoutNonce > 0 && state.checkoutMessage in setOf("paid", "demo")) {
            cart = emptyList()
        }
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        InboundBanner(inboundCount, inboundOrders, onReadInbound)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(t.newOrder, fontWeight = FontWeight.Black, fontSize = 26.sp)
            Spacer(Modifier.weight(1f))
            OrderTypeChip(t.dineIn, orderType == OrderType.DINE_IN) { orderType = OrderType.DINE_IN }
            OrderTypeChip(t.takeaway, orderType == OrderType.TAKEAWAY) { orderType = OrderType.TAKEAWAY }
            OrderTypeChip(t.deliveryType, orderType == OrderType.DELIVERY) { orderType = OrderType.DELIVERY }
        }

        if (orderType == OrderType.DINE_IN && state.tables.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.tables.filter { it.available }) { table ->
                    FilterChip(
                        selected = selectedTableId == table.id,
                        onClick = { selectedTableId = table.id },
                        label = { Text("${t.chooseTable} ${table.label}") }
                    )
                }
            }
        }

        if (state.checkoutMessage == "cash_required") {
            Text(t.openCashFirst, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        } else if (state.checkoutMessage == "table_required") {
            Text("${t.chooseTable}: sélection requise", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        } else if (state.checkoutMessage == "paid") {
            Text(t.orderPaid, color = CookitGreen, fontWeight = FontWeight.Bold)
        }

        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProductPane(
                    modifier = Modifier.weight(1.65f),
                    categories = categories,
                    products = products,
                    selectedCategory = selectedCategory,
                    onCategory = { selectedCategory = it },
                    onAdd = { product -> cart = addToCart(cart, product) }
                )
                CartPane(
                    modifier = Modifier.weight(0.9f),
                    cart = cart,
                    t = t,
                    busy = state.checkoutBusy,
                    tableLabel = state.tables.firstOrNull { it.id == selectedTableId }?.label,
                    onPlus = { id -> cart = cart.map { if (it.product.id == id) it.copy(quantity = it.quantity + 1) else it } },
                    onMinus = { id -> cart = decrementCart(cart, id) },
                    onCheckout = { vm.checkout(cart, orderType, selectedTableId) }
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
                    onAdd = { product -> cart = addToCart(cart, product) }
                )
                if (cart.isNotEmpty()) {
                    MobileCartBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        cart = cart,
                        t = t,
                        busy = state.checkoutBusy,
                        onCheckout = { vm.checkout(cart, orderType, selectedTableId) }
                    )
                }
            }
        }
    }
}

private fun addToCart(cart: List<CartLine>, product: Product): List<CartLine> {
    val existing = cart.firstOrNull { it.product.id == product.id }
    return if (existing == null) cart + CartLine(product, 1)
    else cart.map { if (it.product.id == product.id) it.copy(quantity = it.quantity + 1) else it }
}

private fun decrementCart(cart: List<CartLine>, id: Long): List<CartLine> = cart.mapNotNull {
    if (it.product.id != id) it else if (it.quantity <= 1) null else it.copy(quantity = it.quantity - 1)
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

@Composable
private fun ProductImage(product: Product) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, product.imageUrl) {
        value = product.imageUrl?.let { url ->
            withContext(Dispatchers.IO) {
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
private fun DashboardScreen(orders: List<PosOrder>) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Bonjour Amina", fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text("Voici l'activité de la branche aujourd'hui.", color = CookitMuted)
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
                        Text("ESC/POS LAN • Star Android arrive dans la vague matérielle suivante", color = CookitMuted, fontSize = 12.sp)
                    }
                    if (!state.policy.canManagePrinters) Icon(Icons.Default.Lock, null, tint = CookitMuted)
                }
                if (state.policy.canManagePrinters || state.demoMode) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.savePrinter(printerHost, printerPort) }) { Text("Enregistrer") }
                        Button(onClick = {
                            vm.savePrinter(printerHost, printerPort)
                            vm.testPrinter()
                        }) { Text(t.testPrinter) }
                    }
                    state.printerMessage?.let { result ->
                        Text(
                            if (result == "ok" || result == "drawer_ok") t.printerReady else t.printerFailed,
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
