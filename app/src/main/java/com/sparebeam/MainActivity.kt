package com.sparebeam

import android.content.Intent
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sparebeam.storage.ImportedMediaStore
import com.sparebeam.storage.SharedFolders
import com.sparebeam.server.MediaServerService
import com.sparebeam.server.PairingAccess

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
    var importedFolders by remember { mutableStateOf(ImportedMediaStore.folders(context)) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var pendingImportFolder by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var copying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val running by MediaServerService.running.collectAsState()
    val serverError by MediaServerService.errorMessage.collectAsState()
    val addFolder = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            SharedFolders.add(context, it)
            folders = SharedFolders.list(context)
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        val targetFolder = pendingImportFolder
        pendingImportFolder = null
        if (!uris.isNullOrEmpty() && targetFolder != null) {
            copying = true
            importStatus = "Copying ${uris.size} selected item(s)…"
            scope.launch {
                val result = withContext(Dispatchers.IO) { ImportedMediaStore.copyInto(context, targetFolder, uris) }
                importedFolders = ImportedMediaStore.folders(context)
                copying = false
                importStatus = "Copied ${result.copied} item(s) to $targetFolder${if (result.failed > 0) "; ${result.failed} could not be copied" else ""}."
            }
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
                    }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = running || folders.isNotEmpty() || importedFolders.isNotEmpty()) {
                        Text(if (running) "Stop server" else "Start server")
                    }
                }
            }
            serverError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
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
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        Text("SpareBeam folders", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { newFolderName = ""; showCreateFolder = true }) { Text("+ Create folder") }
                    }
                    Text("Copies stay in SpareBeam’s private storage. Only media you select is copied; originals are unchanged.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (importedFolders.isEmpty()) item {
                    Text("Create a folder, then choose Add photos/videos. Your copies appear in the browser library whenever the server is running.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                }
                items(importedFolders, key = { "private:${it.name}" }) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.name) },
                        supportingContent = { Text("${folder.fileCount} copied item(s) · stored in SpareBeam") },
                        trailingContent = {
                            TextButton(enabled = !copying, onClick = {
                                pendingImportFolder = folder.name
                                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }) { Text("Add photos/videos") }
                        }
                    )
                }
            }
            importStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            if (running) Text("Open ${MediaServerService.address(context) ?: "http://<phone-ip>:8080"} on another device on this Wi-Fi.", style = MaterialTheme.typography.bodySmall)
            if (running) {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Pairing code", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(PairingAccess.pairingCode(context), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Enter this code in the browser on a device you trust.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text("Create a SpareBeam folder") },
            text = {
                Column {
                    Text("This folder is private to SpareBeam. You’ll choose which photos or videos to copy into it.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, label = { Text("Folder name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val created = ImportedMediaStore.createFolder(context, newFolderName)
                    if (created == null) importStatus = "Choose a valid folder name."
                    else {
                        importedFolders = ImportedMediaStore.folders(context)
                        importStatus = "Folder created. Add photos or videos when you’re ready."
                        showCreateFolder = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolder = false }) { Text("Cancel") } }
        )
    }
}
