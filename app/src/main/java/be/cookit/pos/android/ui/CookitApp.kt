package be.cookit.pos.android.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import be.cookit.pos.android.data.DemoRepository
import be.cookit.pos.android.domain.*
import be.cookit.pos.android.ui.theme.*
import java.util.Locale

private enum class Screen(val label: String) {
    DASHBOARD("Accueil"),
    POS("Caisse"),
    ORDERS("Commandes"),
    KDS("Cuisine"),
    DELIVERY("Livraison"),
    CASH("Fond de caisse"),
    SETTINGS("Réglages")
}

@Composable
fun CookitApp() {
    var signedIn by remember { mutableStateOf(false) }
    if (!signedIn) {
        LoginScreen(onDemo = { signedIn = true })
        return
    }

    var screen by remember { mutableStateOf(Screen.POS) }
    val widthDp = LocalConfiguration.current.screenWidthDp
    val tablet = widthDp >= 840

    if (tablet) {
        Row(Modifier.fillMaxSize().background(CookitCanvas)) {
            SideNavigation(screen = screen, onSelect = { screen = it })
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar()
                ScreenContent(screen)
            }
        }
    } else {
        Scaffold(
            topBar = { TopBar(compact = true) },
            bottomBar = {
                NavigationBar {
                    listOf(Screen.POS, Screen.ORDERS, Screen.CASH, Screen.SETTINGS).forEach {
                        NavigationBarItem(
                            selected = screen == it,
                            onClick = { screen = it },
                            icon = { Icon(iconFor(it), contentDescription = null) },
                            label = { Text(it.label) }
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
            ) { ScreenContent(screen) }
        }
    }
}

@Composable
private fun LoginScreen(onDemo: () -> Unit) {
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
                        Text("Android • Native preview", color = CookitMuted)
                    }
                }
                HorizontalDivider(color = CookitLine)
                OutlinedTextField(
                    value = "cashier@restaurant.test",
                    onValueChange = {},
                    label = { Text("E-mail") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = "••••••••",
                    onValueChange = {},
                    label = { Text("Mot de passe") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = onDemo,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Voir la démo POS", fontWeight = FontWeight.Bold) }
                Text(
                    "La vague A3 branchera cet écran sur /auth/login et le bootstrap RBAC Cookit.",
                    color = CookitMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SideNavigation(screen: Screen, onSelect: (Screen) -> Unit) {
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
                        contentDescription = item.label,
                        tint = if (selected) CookitOrange else Color(0xFFD7DDD9)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.label,
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
private fun TopBar(compact: Boolean = false) {
    Surface(color = Color.White, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(if (compact) 68.dp else 78.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    DemoRepository.user.restaurant,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 17.sp else 20.sp
                )
                Text(
                    "${DemoRepository.user.branch} • ${DemoRepository.user.role.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    color = CookitMuted,
                    fontSize = 12.sp
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CookitSoftGreen
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(8.dp)).background(CookitGreen))
                    Spacer(Modifier.width(7.dp))
                    Text("En ligne", color = CookitGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            if (!compact) {
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = {}) {
                    BadgedBox(badge = { Badge { Text("2") } }) {
                        Icon(Icons.Default.Notifications, null)
                    }
                }
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = CookitSoftOrange
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("AM", color = CookitOrange, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen) {
    when (screen) {
        Screen.POS -> PosScreen()
        Screen.ORDERS -> OrdersScreen()
        Screen.CASH -> CashScreen()
        Screen.DASHBOARD -> DashboardScreen()
        Screen.KDS -> PlaceholderScreen("Cuisine", "KDS / tickets cuisine")
        Screen.DELIVERY -> PlaceholderScreen("Livraison", "Commandes internes et plateformes")
        Screen.SETTINGS -> SettingsScreen()
    }
}

@Composable
private fun InboundBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFFF7E8),
        border = BorderStroke(1.dp, Color(0xFFFFD99A))
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = CookitOrange) {
                Icon(
                    Icons.Default.NotificationsActive,
                    null,
                    tint = Color.White,
                    modifier = Modifier.padding(9.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("2 nouvelles commandes", fontWeight = FontWeight.ExtraBold)
                Text("QR Table #1248 • Site web #1247", color = CookitMuted, fontSize = 12.sp)
            }
            TextButton(onClick = {}) { Text("Voir") }
        }
    }
}

@Composable
private fun PosScreen() {
    var selectedCategory by remember { mutableLongStateOf(1L) }
    var cart by remember {
        mutableStateOf(
            listOf(
                CartLine(DemoRepository.products[0], 2),
                CartLine(DemoRepository.products[6], 1)
            )
        )
    }
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp >= 1050

    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        InboundBanner()

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Nouvelle commande", fontWeight = FontWeight.Black, fontSize = 26.sp)
            Spacer(Modifier.weight(1f))
            OrderTypeChip("Sur place", true)
            OrderTypeChip("À emporter", false)
            OrderTypeChip("Livraison", false)
        }

        if (wide) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ProductPane(
                    modifier = Modifier.weight(1.65f),
                    selectedCategory = selectedCategory,
                    onCategory = { selectedCategory = it },
                    onAdd = { product ->
                        val existing = cart.firstOrNull { it.product.id == product.id }
                        cart = if (existing == null) cart + CartLine(product, 1)
                        else cart.map {
                            if (it.product.id == product.id) it.copy(quantity = it.quantity + 1) else it
                        }
                    }
                )
                CartPane(
                    modifier = Modifier.weight(0.9f),
                    cart = cart,
                    onPlus = { id ->
                        cart = cart.map { if (it.product.id == id) it.copy(quantity = it.quantity + 1) else it }
                    },
                    onMinus = { id ->
                        cart = cart.mapNotNull {
                            if (it.product.id != id) it
                            else if (it.quantity <= 1) null else it.copy(quantity = it.quantity - 1)
                        }
                    }
                )
            }
        } else {
            ProductPane(
                modifier = Modifier.fillMaxSize(),
                selectedCategory = selectedCategory,
                onCategory = { selectedCategory = it },
                onAdd = { product -> cart = cart + CartLine(product, 1) }
            )
        }
    }
}

@Composable
private fun OrderTypeChip(label: String, selected: Boolean) {
    Surface(
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
    selectedCategory: Long,
    onCategory: (Long) -> Unit,
    onAdd: (Product) -> Unit
) {
    Column(modifier) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DemoRepository.categories) { category ->
                FilterChip(
                    selected = category.id == selectedCategory,
                    onClick = { onCategory(category.id) },
                    label = { Text("${category.emoji} ${category.name}") }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        val products = DemoRepository.products.filter {
            selectedCategory == 1L || it.categoryId == selectedCategory
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 180.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(products, key = { it.id }) { product ->
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
            .height(178.dp)
            .clickable { onAdd(product) },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CookitLine)
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = CookitCanvas
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(product.emoji, fontSize = 24.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.AddCircle, null, tint = CookitOrange)
            }
            Spacer(Modifier.height(12.dp))
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
private fun CartPane(
    modifier: Modifier,
    cart: List<CartLine>,
    onPlus: (Long) -> Unit,
    onMinus: (Long) -> Unit
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Panier", fontSize = 22.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(12.dp), color = CookitCanvas) {
                    Text("Table 7", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
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
                        FilledTonalIconButton(onClick = { onMinus(line.product.id) }) {
                            Icon(Icons.Default.Remove, null)
                        }
                        Text("${line.quantity}", Modifier.padding(horizontal = 8.dp), fontWeight = FontWeight.Black)
                        FilledTonalIconButton(onClick = { onPlus(line.product.id) }) {
                            Icon(Icons.Default.Add, null)
                        }
                    }
                    HorizontalDivider(color = CookitLine)
                }
            }

            val subtotal = cart.sumOf { it.total }
            SummaryLine("Sous-total", subtotal)
            SummaryLine("TVA incluse", subtotal * 0.12, muted = true)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = CookitLine)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Total", fontSize = 20.sp, fontWeight = FontWeight.Black)
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
                onClick = {},
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(17.dp)
            ) {
                Icon(Icons.Default.Payments, null)
                Spacer(Modifier.width(8.dp))
                Text("Encaisser", fontWeight = FontWeight.Black)
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
private fun OrdersScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Commandes", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("QR, web, POS et livraison dans une vue unique.", color = CookitMuted)
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(DemoRepository.orders) { order ->
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
private fun CashScreen() {
    var quantities by remember { mutableStateOf(DemoRepository.denominations.associate { it.label to 0 }) }
    val total = DemoRepository.denominations.sumOf { d -> d.value * (quantities[d.label] ?: 0) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ouverture de caisse", fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("Comptage du fond de caisse avant le service.", color = CookitMuted)
            }
            Surface(shape = RoundedCornerShape(16.dp), color = CookitSoftOrange) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), horizontalAlignment = Alignment.End) {
                    Text("FOND DE CAISSE", color = CookitOrange, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
            items(DemoRepository.denominations) { denomination ->
                val qty = quantities[denomination.label] ?: 0
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, CookitLine)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(denomination.label, Modifier.weight(1f), fontWeight = FontWeight.Black)
                        IconButton(onClick = {
                            quantities = quantities.toMutableMap().also {
                                it[denomination.label] = (qty - 1).coerceAtLeast(0)
                            }
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
            OutlinedButton(onClick = {}, modifier = Modifier.height(54.dp)) {
                Icon(Icons.Default.PointOfSale, null)
                Spacer(Modifier.width(8.dp))
                Text("Ouvrir le tiroir")
            }
            Button(onClick = {}, modifier = Modifier.height(54.dp)) {
                Text("Démarrer le service", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun DashboardScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Bonjour Amina", fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text("Voici l'activité de la branche aujourd'hui.", color = CookitMuted)
        Spacer(Modifier.height(18.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { MetricCard("CA aujourd'hui", "1 284,50 €", "+12,4%", Icons.Default.TrendingUp) }
            item { MetricCard("Commandes", "67", "5 entrantes", Icons.Default.ReceiptLong) }
            item { MetricCard("En cuisine", "8", "3 à servir", Icons.Default.SoupKitchen) }
            item { MetricCard("Livraisons", "6", "2 en route", Icons.Default.DeliveryDining) }
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
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Réglages", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Les options sensibles suivent native_policy.", color = CookitMuted)
        SettingsRow(Icons.Default.Print, "Imprimantes", "Star / ESC-POS / mapping cuisine", locked = true)
        SettingsRow(Icons.Default.Wifi, "Réseau", "Connexion et diagnostic LAN")
        SettingsRow(Icons.Default.Person, "Compte", "Amina • Cashier")
        SettingsRow(Icons.Default.Security, "Permissions", "Profil cashier • politique serveur")
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
