package com.sparebeam

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparebeam.server.MediaServerService
import com.sparebeam.server.PairingAccess
import com.sparebeam.storage.ImportedMediaStore
import com.sparebeam.storage.SharedFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF18352C)
private val Green = Color(0xFF236B50)
private val Mint = Color(0xFFB9F2CF)
private val Muted = Color(0xFF66756D)
private val Outline = Color(0xFFDEE6E0)
private val SoftGreen = Color(0xFFEDF4EE)
private val Page = Color(0xFFF6F8F5)
private val CardShape = RoundedCornerShape(24.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Green, onPrimary = Color.White,
                    primaryContainer = SoftGreen, onPrimaryContainer = Ink,
                    background = Page, onBackground = Ink,
                    surface = Color.White, onSurface = Ink,
                    surfaceVariant = SoftGreen, onSurfaceVariant = Muted,
                    outline = Outline, outlineVariant = Outline
                ),
                shapes = Shapes(medium = RoundedCornerShape(16.dp), large = CardShape)
            ) { SpareBeamHome() }
        }
    }
}

@Composable
private fun SpareBeamHome() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var folders by remember { mutableStateOf(SharedFolders.list(context)) }
    var importedFolders by remember { mutableStateOf(ImportedMediaStore.folders(context)) }
    var showCreateFolder by rememberSaveable { mutableStateOf(false) }
    var newFolderName by rememberSaveable { mutableStateOf("") }
    var folderError by remember { mutableStateOf<String?>(null) }
    var pendingImportFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var copying by remember { mutableStateOf(false) }
    var copyingTo by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val running by MediaServerService.running.collectAsState()
    val serverError by MediaServerService.errorMessage.collectAsState()
    val totalFolders = folders.size + importedFolders.size
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                SharedFolders.add(context, it)
                folders = SharedFolders.list(context)
            }.onFailure { message("Could not link this folder. Please try another folder.") }
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        val target = pendingImportFolder
        pendingImportFolder = null
        if (!uris.isNullOrEmpty() && target != null) {
            copying = true
            copyingTo = target
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { ImportedMediaStore.copyInto(context, target, uris) }
                    importedFolders = ImportedMediaStore.folders(context)
                    message("${result.copied} item(s) added to $target${if (result.failed > 0) ". ${result.failed} could not be copied." else "."}")
                } finally { copying = false }
            }
        }
    }
    Scaffold(
        containerColor = Page,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Box(Modifier.size(48.dp).background(Ink, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        BeamIcon(Glyph.BEAM, Mint, Modifier.size(27.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("SpareBeam", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
                        Text("Your spare phone. Your own server.", fontSize = 12.sp, color = Muted)
                    }
                }
            }
            item {
                ServerCard(running, totalFolders > 0) {
                    val action = if (running) MediaServerService.ACTION_STOP else MediaServerService.ACTION_START
                    val intent = Intent(context, MediaServerService::class.java).setAction(action)
                    if (running) context.startService(intent) else context.startForegroundService(intent)
                }
            }
            if (serverError != null && !running) item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                    Text(serverError!!, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (running) item {
                val address = MediaServerService.address(context)
                ConnectionCard(address, PairingAccess.pairingCode(context)) {
                    if (address != null) {
                        clipboard.setText(AnnotatedString(address))
                        message("Address copied")
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your library", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), letterSpacing = (-0.5).sp)
                    Text("$totalFolders ${if (totalFolders == 1) "folder" else "folders"}", color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
                Text("Choose the media you want to share.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    LibraryAction("Create folder", "Copy selected media", Glyph.PLUS, Modifier.weight(1f)) {
                        newFolderName = ""; folderError = null; showCreateFolder = true
                    }
                    LibraryAction("Link folder", "Share existing files", Glyph.LINK, Modifier.weight(1f)) { addFolder.launch(null) }
                }
            }
            if (copying) item {
                Surface(color = SoftGreen, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Adding media to $copyingTo…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if (totalFolders == 0) item {
                Surface(shape = CardShape, color = Color.White, border = BorderStroke(1.dp, Outline)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(64.dp).background(SoftGreen, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                            BeamIcon(Glyph.FOLDER, Green, Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("A home for your media", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("Create your first folder above, then add a few photos or videos. Your originals stay untouched.", color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            if (importedFolders.isNotEmpty()) item { LibraryLabel("ON THIS PHONE") }
            items(importedFolders, key = { "private:${it.name}" }) { folder ->
                FolderCard(folder.name, "${folder.fileCount} ${if (folder.fileCount == 1) "item" else "items"} · On this phone") {
                    FilledTonalButton(
                        enabled = !copying,
                        onClick = { pendingImportFolder = folder.name; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        BeamIcon(Glyph.PLUS, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add photos/videos", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (folders.isNotEmpty()) item { LibraryLabel("LINKED FOLDERS") }
            items(folders, key = { "linked:$it" }) { uri ->
                FolderCard(SharedFolders.displayName(context, uri), "Read-only · Originals stay in place") {
                    TextButton(
                        onClick = { SharedFolders.remove(context, uri); folders = SharedFolders.list(context); message("Folder unlinked. Original files are unchanged.") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Unlink folder") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    BeamIcon(Glyph.SHIELD, Muted, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Made for your trusted Wi-Fi network", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            icon = { BeamIcon(Glyph.FOLDER, Green, Modifier.size(28.dp)) },
            title = { Text("Create folder", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Give your collection a name. You can choose photos and videos to add next.", color = Muted)
                    OutlinedTextField(
                        value = newFolderName, onValueChange = { newFolderName = it; folderError = null },
                        label = { Text("Folder name") }, placeholder = { Text("e.g. Weekend adventures") },
                        singleLine = true, isError = folderError != null,
                        supportingText = if (folderError != null) ({ Text(folderError!!) }) else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(enabled = newFolderName.isNotBlank(), onClick = {
                    val created = ImportedMediaStore.createFolder(context, newFolderName)
                    if (created == null) folderError = "Use a name without slashes, up to 80 characters."
                    else {
                        importedFolders = ImportedMediaStore.folders(context)
                        showCreateFolder = false
                        message("Folder created. Add photos or videos when you’re ready.")
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolder = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ServerCard(running: Boolean, hasFolders: Boolean, onToggle: () -> Unit) {
    Surface(shape = CardShape, color = Ink) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (running) Mint else Color(0xFF97A79F), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(if (running) "SERVER ONLINE" else "SERVER OFFLINE", color = if (running) Mint else Color(0xFFCBD8D1), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                Spacer(Modifier.weight(1f))
                BeamIcon(Glyph.BEAM, Color(0xFF7C9989), Modifier.size(22.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(if (running) "Your library is live." else "Your media.\nYour Wi-Fi.", color = Color.White, fontSize = 29.sp, lineHeight = 34.sp, letterSpacing = (-0.7).sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                if (running) "Connect a device below to browse your media." else "Photos, videos, and memories. Shared on your Wi-Fi, straight from this phone.",
                color = Color(0xFFCBD8D1), fontSize = 14.sp, lineHeight = 21.sp
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onToggle, enabled = running || hasFolders,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink, disabledContainerColor = Color(0xFF3B5147), disabledContentColor = Color(0xFFB0C0B6))
            ) {
                BeamIcon(if (running) Glyph.STOP else Glyph.PLAY, if (running || hasFolders) Ink else Color(0xFFB0C0B6), Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (running) "Stop server" else "Start server", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!hasFolders && !running) {
                Spacer(Modifier.height(12.dp))
                Text("Add your first folder below to get started.", color = Color(0xFFCBD8D1), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ConnectionCard(address: String?, code: String, onCopy: () -> Unit) {
    Surface(shape = CardShape, color = Color.White, border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(20.dp)) {
            Text("Connect a device", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
            Spacer(Modifier.height(6.dp))
            Text("Use a browser on the same Wi-Fi as this phone.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Text("1  Open this address", style = MaterialTheme.typography.labelLarge, color = Muted)
            Spacer(Modifier.height(8.dp))
            Surface(color = Page, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(address ?: "Wi-Fi address unavailable", fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(vertical = 14.dp))
                    }
                    IconButton(enabled = address != null, onClick = onCopy, modifier = Modifier.semantics { contentDescription = "Copy address" }) { BeamIcon(Glyph.COPY, Green) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("2  Enter this pairing code", style = MaterialTheme.typography.labelLarge, color = Muted)
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(code, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, letterSpacing = 5.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Only pair devices you trust.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LibraryLabel(text: String) {
    Text(text, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
}

@Composable
private fun LibraryAction(title: String, subtitle: String, glyph: Glyph, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(16.dp)) {
            BeamIcon(glyph, Green, Modifier.size(23.dp))
            Spacer(Modifier.height(14.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun FolderCard(name: String, detail: String, action: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(SoftGreen, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { BeamIcon(Glyph.FOLDER, Green) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

private enum class Glyph { BEAM, FOLDER, PLUS, LINK, PLAY, STOP, COPY, SHIELD }

@Composable
private fun BeamIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val unit = size.width / 24f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        val stroke = Stroke(1.7f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, p(x, y), p(x2, y2), 1.8f * unit, StrokeCap.Round)
        when (glyph) {
            Glyph.BEAM -> {
                drawRoundRect(tint, p(4f, 4f), Size(15 * unit, 3.6f * unit), CornerRadius(1.8f * unit))
                drawRoundRect(tint, p(8f, 10f), Size(12 * unit, 3.6f * unit), CornerRadius(1.8f * unit))
                drawRoundRect(tint, p(4f, 16f), Size(15 * unit, 3.6f * unit), CornerRadius(1.8f * unit))
            }
            Glyph.FOLDER -> drawPath(Path().apply {
                moveTo(3 * unit, 7 * unit); lineTo(3 * unit, 19 * unit); lineTo(21 * unit, 19 * unit); lineTo(21 * unit, 7 * unit); lineTo(12 * unit, 7 * unit); lineTo(10 * unit, 4 * unit); lineTo(3 * unit, 4 * unit); close()
            }, tint, style = stroke)
            Glyph.PLUS -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            Glyph.LINK -> {
                drawArc(tint, 45f, 270f, false, p(3f, 9f), Size(10 * unit, 10 * unit), style = stroke)
                drawArc(tint, 225f, 270f, false, p(11f, 3f), Size(10 * unit, 10 * unit), style = stroke)
                line(8f, 16f, 16f, 8f)
            }
            Glyph.PLAY -> drawPath(Path().apply { moveTo(8 * unit, 4 * unit); lineTo(20 * unit, 12 * unit); lineTo(8 * unit, 20 * unit); close() }, tint)
            Glyph.STOP -> drawRoundRect(tint, p(5f, 5f), Size(14 * unit, 14 * unit), CornerRadius(3 * unit))
            Glyph.COPY -> {
                drawRoundRect(tint, p(8f, 8f), Size(12 * unit, 13 * unit), CornerRadius(2 * unit), style = stroke)
                line(4f, 16f, 4f, 3f); line(4f, 3f, 15f, 3f)
            }
            Glyph.SHIELD -> {
                drawPath(Path().apply { moveTo(12 * unit, 2 * unit); lineTo(21 * unit, 6 * unit); lineTo(19 * unit, 16 * unit); lineTo(12 * unit, 22 * unit); lineTo(5 * unit, 16 * unit); lineTo(3 * unit, 6 * unit); close() }, tint, style = stroke)
                line(8f, 12f, 11f, 15f); line(11f, 15f, 16f, 9f)
            }
        }
    }
}
