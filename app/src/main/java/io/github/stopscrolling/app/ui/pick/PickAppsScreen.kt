package io.github.stopscrolling.app.ui.pick

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import io.github.stopscrolling.app.MainViewModel
import io.github.stopscrolling.app.data.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickAppsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val guarded by vm.guardedApps.collectAsState()
    val home by vm.home.collectAsState()
    var allApps by remember { mutableStateOf(emptyList<io.github.stopscrolling.app.data.InstalledApp>()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) { vm.installedApps() }
    }

    val guardedSet = guarded.map { it.packageName }.toSet()
    val filtered = allApps.filter {
        query.isBlank() || it.label.contains(query, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择要管理的应用") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索应用") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val already = app.packageName in guardedSet
                    val usedMs = home.usageByPackage[app.packageName] ?: 0L
                    AppRow(
                        label = app.label,
                        icon = app.icon,
                        usedToday = UsageStatsHelper.formatDuration(usedMs),
                        already = already,
                        onAdd = {
                            vm.addGuardedApp(app.label, app.packageName, 30)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    label: String,
    icon: android.graphics.drawable.Drawable?,
    usedToday: String,
    already: Boolean,
    onAdd: () -> Unit
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "今日 $usedToday",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (already) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已添加",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Button(onClick = onAdd) { Text("添加") }
            }
        }
    }
}

@Composable
private fun AppIcon(drawable: android.graphics.drawable.Drawable?) {
    val size = 40.dp
    val bitmap: android.graphics.Bitmap? = remember(drawable) {
        when (drawable) {
            is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
            is android.graphics.drawable.AdaptiveIconDrawable -> {
                val bg = drawable.background
                val fg = drawable.foreground
                val layers = if (bg != null && fg != null) {
                    android.graphics.drawable.LayerDrawable(arrayOf(bg, fg))
                } else null
                layers?.let {
                    android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                        .also { bmp ->
                            val canvas = android.graphics.Canvas(bmp)
                            it.setBounds(0, 0, 96, 96)
                            it.draw(canvas)
                        }
                }
            }
            else -> null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    } else {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
            drawCircle(androidx.compose.ui.graphics.Color(0xFF9AA6B2))
        }
    }
}
