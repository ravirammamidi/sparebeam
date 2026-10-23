package com.sparebeam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sparebeam.storage.SharedFolders
import com.sparebeam.server.MediaServerService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8CF5CC), background = Color(0xFF111715), surface = Color(0xFF1D2622))) {
                SpareBeamHome()
            }
        }
    }
}

@Composable
private fun SpareBeamHome() {
    val context = LocalContext.current
    var folders by remember { mutableStateOf(SharedFolders.list(context)) }
    var running by remember { mutableStateOf(MediaServerService.isRunning) }
    val addFolder = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            SharedFolders.add(context, it)
            folders = SharedFolders.list(context)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp)) {
            Text("SpareBeam", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Your spare phone. Your own server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(if (running) "SERVER ONLINE" else "SERVER OFFLINE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(if (running) "Ready to share on your Wi-Fi" else "Your media stays on this network", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = {
                        val action = if (running) MediaServerService.ACTION_STOP else MediaServerService.ACTION_START
                        val serviceIntent = Intent(context, MediaServerService::class.java).setAction(action)
                        if (running) context.startService(serviceIntent) else context.startForegroundService(serviceIntent)
                        running = !running
                    }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = running || folders.isNotEmpty()) {
                        Text(if (running) "Stop server" else "Start server")
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Shared folders", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { addFolder.launch(null) }) { Text("+ Add folder") }
            }
            if (folders.isEmpty()) Text("Choose folders to make their media available on your local network.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(folders, key = { it.toString() }) { uri ->
                    ListItem(headlineContent = { Text(SharedFolders.displayName(context, uri)) }, supportingContent = { Text("Read-only access") }, trailingContent = {
                        TextButton(onClick = { SharedFolders.remove(context, uri); folders = SharedFolders.list(context) }) { Text("Remove") }
                    })
                }
            }
            if (running) Text("Open ${MediaServerService.address(context) ?: "http://<phone-ip>:8080"} on another device on this Wi-Fi.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
