package com.example.netguardlite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.netguardlite.data.AppInfo
import com.example.netguardlite.data.AppRepository
import com.example.netguardlite.data.TrafficMonitor
import com.example.netguardlite.vpn.AllowListStore
import com.example.netguardlite.vpn.ConnectionLog
import com.example.netguardlite.vpn.LocalVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                NetGuardApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetGuardApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    val allowedPackages = remember { mutableStateMapOf<String, Boolean>() }
    var usageMap by remember { mutableStateOf<Map<Int, TrafficMonitor.UsageSnapshot>>(emptyMap()) }
    var protectionEnabled by remember { mutableStateOf(AllowListStore.isProtectionEnabled(context)) }
    var networkScope by remember { mutableStateOf(AllowListStore.getNetworkScope(context)) }
    var selectedTab by remember { mutableStateOf(0) }
    var showNetworkDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            AllowListStore.setProtectionEnabled(context, true)
            protectionEnabled = true
            val intent = Intent(context, LocalVpnService::class.java).apply {
                action = LocalVpnService.ACTION_START
            }
            context.startForegroundService(intent)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun mergeAllowed(list: List<AppInfo>) {
        val savedAllowed = AllowListStore.getAllowedPackages(context)
        list.forEach { app ->
            if (!allowedPackages.containsKey(app.packageName)) {
                allowedPackages[app.packageName] = savedAllowed.contains(app.packageName)
            }
        }
    }

    suspend fun refreshAppsInBackground() {
        val fresh = withContext(Dispatchers.IO) { AppRepository.getInternetCapableApps(context) }
        apps = fresh
        mergeAllowed(fresh)
    }

    // عرض فوري من الكاش المحفوظ، ثم تحديث حقيقي بالخلفية بدون تجميد الواجهة
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val cached = AppRepository.loadCachedApps(context)
        if (cached.isNotEmpty()) {
            apps = cached
            mergeAllowed(cached)
        }

        refreshAppsInBackground()
    }

    // مراقبة تثبيت/حذف التطبيقات لحظياً وتحديث القائمة تلقائياً
    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                scope.launch { refreshAppsInBackground() }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
            }
        }
    }

    LaunchedEffect(apps) {
        if (apps.isNotEmpty()) {
            val uids = apps.map { it.uid }.distinct()
            TrafficMonitor.observe(uids).collect { snapshot ->
                usageMap = snapshot
            }
        }
    }

    fun sendServiceAction(action: String) {
        val intent = Intent(context, LocalVpnService::class.java).apply { this.action = action }
        if (action == LocalVpnService.ACTION_START) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun toggleProtection(enable: Boolean) {
        if (enable) {
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                AllowListStore.setProtectionEnabled(context, true)
                protectionEnabled = true
                sendServiceAction(LocalVpnService.ACTION_START)
            }
        } else {
            AllowListStore.setProtectionEnabled(context, false)
            protectionEnabled = false
            sendServiceAction(LocalVpnService.ACTION_STOP)
        }
    }

    fun onAppToggle(packageName: String, checked: Boolean) {
        allowedPackages[packageName] = checked
        AllowListStore.setAllowed(context, packageName, checked)
        if (protectionEnabled) sendServiceAction(LocalVpnService.ACTION_UPDATE)
    }

    fun onNetworkScopeSelected(scope2: String) {
        networkScope = scope2
        AllowListStore.setNetworkScope(context, scope2)
        showNetworkDialog = false
        if (protectionEnabled) sendServiceAction(LocalVpnService.ACTION_UPDATE)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NetGuard Lite") },
                actions = {
                    IconButton(onClick = { showNetworkDialog = true }) {
                        Text(
                            when (networkScope) {
                                AllowListStore.SCOPE_WIFI_ONLY -> "WiFi"
                                AllowListStore.SCOPE_MOBILE_ONLY -> "بيانات"
                                else -> "الكل"
                            },
                            fontSize = 12.sp
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (protectionEnabled) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = null,
                            tint = if (protectionEnabled) Color(0xFF2E7D32) else Color.Gray
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = protectionEnabled,
                            onCheckedChange = { toggleProtection(it) }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("التطبيقات") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("سجل المحاولات (${ConnectionLog.entries.size})") }
                )
            }

            if (protectionEnabled) {
                Surface(color = Color(0xFFE8F5E9)) {
                    Text(
                        "الحماية شغالة: أي تطبيق مو مفعّل محجوب تماماً عن الإنترنت",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = Color(0xFF1B5E20)
                    )
                }
            }

            when (selectedTab) {
                0 -> AppsTab(
                    apps = apps,
                    allowedPackages = allowedPackages,
                    usageMap = usageMap,
                    protectionEnabled = protectionEnabled,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onToggle = ::onAppToggle
                )
                1 -> LogTab(context = context)
            }
        }
    }

    if (showNetworkDialog) {
        NetworkScopeDialog(
            current = networkScope,
            onDismiss = { showNetworkDialog = false },
            onSelect = ::onNetworkScopeSelected
        )
    }
}

@Composable
fun AppsTab(
    apps: List<AppInfo>,
    allowedPackages: Map<String, Boolean>,
    usageMap: Map<Int, TrafficMonitor.UsageSnapshot>,
    protectionEnabled: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("ابحث عن تطبيق...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "مسح البحث")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (apps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        val filteredApps = remember(apps, searchQuery) {
            if (searchQuery.isBlank()) {
                apps
            } else {
                apps.filter {
                    it.appName.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
                }
            }
        }

        if (filteredApps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("ما فيه نتائج مطابقة", color = Color.Gray, fontSize = 13.sp)
            }
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredApps, key = { it.packageName }) { app ->
                val isActive = usageMap[app.uid]?.isActiveNow == true
                val isAllowed = allowedPackages[app.packageName] ?: false

                AppRow(
                    app = app,
                    isActiveNow = isActive,
                    isAllowed = isAllowed,
                    protectionEnabled = protectionEnabled,
                    onToggle = { checked -> onToggle(app.packageName, checked) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun LogTab(context: android.content.Context) {
    val entries = ConnectionLog.entries
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "محاولات الاتصال من التطبيقات المحجوبة (آخر 50)",
                fontSize = 12.sp,
                color = Color.Gray
            )
            TextButton(onClick = { ConnectionLog.clear() }) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("مسح", fontSize = 12.sp)
            }
        }

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "ما فيه محاولات مسجلة بعد",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { "${it.packageName}-${it.timestampMillis}" }) { entry ->
                    LogRow(context = context, entry = entry, timeFormat = timeFormat)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun LogRow(
    context: android.content.Context,
    entry: com.example.netguardlite.vpn.ConnectionAttempt,
    timeFormat: SimpleDateFormat
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = remember(entry.packageName) {
            try {
                context.packageManager.getApplicationIcon(entry.packageName)
                    .toBitmap(width = 96, height = 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }

        Box(modifier = Modifier.size(36.dp).clip(CircleShape)) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = entry.appName)
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.LightGray))
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(entry.appName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                "${entry.protocol} → ${entry.destination}",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }

        Text(
            timeFormat.format(Date(entry.timestampMillis)),
            fontSize = 11.sp,
            color = Color(0xFFC62828)
        )
    }
}

@Composable
fun NetworkScopeDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تفعيل الحماية على") },
        text = {
            Column {
                NetworkScopeOption(
                    label = "الكل (WiFi + بيانات الجوال)",
                    selected = current == AllowListStore.SCOPE_BOTH,
                    onClick = { onSelect(AllowListStore.SCOPE_BOTH) }
                )
                NetworkScopeOption(
                    label = "WiFi فقط",
                    selected = current == AllowListStore.SCOPE_WIFI_ONLY,
                    onClick = { onSelect(AllowListStore.SCOPE_WIFI_ONLY) }
                )
                NetworkScopeOption(
                    label = "بيانات الجوال فقط",
                    selected = current == AllowListStore.SCOPE_MOBILE_ONLY,
                    onClick = { onSelect(AllowListStore.SCOPE_MOBILE_ONLY) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("تم") }
        }
    )
}

@Composable
fun NetworkScopeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
fun AppRow(
    app: AppInfo,
    isActiveNow: Boolean,
    isAllowed: Boolean,
    protectionEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // تحميل الأيقونة كسول - بس للتطبيقات الظاهرة فعلياً بالشاشة
        val bitmap = remember(app.packageName) {
            try {
                context.packageManager.getApplicationIcon(app.packageName)
                    .toBitmap(width = 96, height = 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }

        Box(modifier = Modifier.size(40.dp).clip(CircleShape)) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = app.appName)
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.LightGray))
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(app.appName, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            val statusText = when {
                !protectionEnabled -> "الحماية متوقفة"
                isAllowed && isActiveNow -> "مسموح - يستخدم الإنترنت الآن"
                isAllowed -> "مسموح"
                else -> "محجوب"
            }
            val statusColor = when {
                !protectionEnabled -> Color.Gray
                isAllowed && isActiveNow -> Color(0xFF2E7D32)
                isAllowed -> Color(0xFF1565C0)
                else -> Color(0xFFC62828)
            }
            Text(statusText, fontSize = 12.sp, color = statusColor)
        }

        if (protectionEnabled && isAllowed && isActiveNow) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
            Spacer(modifier = Modifier.width(10.dp))
        }

        Switch(checked = isAllowed, onCheckedChange = onToggle)
    }
}
