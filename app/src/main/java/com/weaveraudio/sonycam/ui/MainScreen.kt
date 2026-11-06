package com.weaveraudio.sonycam.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weaveraudio.sonycam.MediaSaver
import com.weaveraudio.sonycam.color.ExposureStage
import com.weaveraudio.sonycam.color.LutInput
import com.weaveraudio.sonycam.color.TransformMode
import com.weaveraudio.sonycam.color.VideoCodec
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> vm.addItems(uris) }
    val pickLut = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importLut(uri) }
    val browseFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addItems(uris) }
    val launchPicker = {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    val launchBrowser = { browseFiles.launch(arrayOf("image/*", "video/*")) }

    val view = LocalView.current
    DisposableEffect(state.exporting) {
        view.keepScreenOn = state.exporting
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HLG → Rec.709", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (state.items.isNotEmpty() && !state.exporting) {
                        TextButton(onClick = vm::clear) { Text("Clear") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            PreviewPane(state, onPick = launchPicker, onBrowse = launchBrowser)
            state.selected?.let { entry ->
                if (entry.isVideo && state.preview.durationUs > 0) {
                    VideoScrubber(
                        durationUs = state.preview.durationUs,
                        positionUs = state.preview.positionUs,
                        enabled = !state.exporting,
                        onSeek = vm::setPreviewPosition,
                    )
                }
                StatusLine(entry)
                if (entry.isVideo && state.preview.exactYuv == false) {
                    Hint(
                        "This phone's GPU can't read raw video YUV, so the driver's colour " +
                            "conversion is used; very saturated colours may shift slightly."
                    )
                }
            }
            if (state.items.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ThumbnailStrip(
                    state = state,
                    onSelect = vm::select,
                    onRemove = vm::remove,
                    onAdd = launchPicker,
                    onBrowse = launchBrowser,
                )
            }

            SectionLabel("Transform")
            Segmented(
                options = listOf("HLG → 709", "LUT (.cube)"),
                selectedIndex = if (state.settings.mode == TransformMode.BUILT_IN) 0 else 1,
                onSelect = { vm.setMode(if (it == 0) TransformMode.BUILT_IN else TransformMode.LUT) },
            )
            if (state.settings.mode == TransformMode.BUILT_IN) {
                Hint("BT.2100 HLG / BT.2020 → Rec.709 with highlight roll-off and gamut compression.")
            } else {
                Spacer(Modifier.height(10.dp))
                LutPicker(
                    luts = state.luts,
                    selected = state.settings.lutName,
                    loaded = state.lutLoaded,
                    onSelect = vm::selectLut,
                    onDelete = vm::deleteLut,
                    onImport = { pickLut.launch(arrayOf("*/*")) },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "LUT input curve (used for exposure before the LUT)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Segmented(
                    options = LutInput.entries.map { it.label },
                    selectedIndex = state.settings.lutInput.ordinal,
                    onSelect = { vm.setLutInput(LutInput.entries[it]) },
                )
            }

            ExposureControls(
                ev = state.settings.exposureEv,
                stage = state.settings.exposureStage,
                onEv = vm::setExposure,
                onStage = vm::setExposureStage,
            )

            if (state.hasVideo) {
                SectionLabel("Video codec")
                Segmented(
                    options = VideoCodec.entries.map { it.label },
                    selectedIndex = state.settings.videoCodec.ordinal,
                    onSelect = { vm.setCodec(VideoCodec.entries[it]) },
                )
                Hint("Output is 8-bit SDR Rec.709 MP4. Audio is converted to AAC.")
            }

            Spacer(Modifier.height(24.dp))
            if (state.exporting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { state.overallProgress },
                        modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = vm::cancelExport) { Text("Cancel") }
                }
                Hint("Keep the app open while exporting.")
            } else {
                val n = state.items.size
                Button(
                    onClick = vm::export,
                    enabled = n > 0,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        if (n == 0) "Export" else "Export $n item${if (n == 1) "" else "s"}",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Hint(
                "Photos → Pictures/${MediaSaver.FOLDER} (JPEG) · " +
                    "Videos → Movies/${MediaSaver.FOLDER} (MP4)"
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PreviewPane(state: UiState, onPick: () -> Unit, onBrowse: () -> Unit) {
    var showOriginal by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(shape)
            .background(Color(0xFF08090B))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pointerInput(state.selectedId) {
                detectTapGestures(onPress = {
                    showOriginal = true
                    tryAwaitRelease()
                    showOriginal = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val p = state.preview
        val image = if (showOriginal) p.original else (p.graded ?: p.original)
        when {
            state.items.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Pick photos or videos shot in HLG",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = onPick) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Choose from gallery")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBrowse) { Text("Browse files") }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Browse files keeps camera file names and reaches SD-card readers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            p.error != null && image == null -> Text(
                p.error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(20.dp),
            )
            image != null -> Image(
                bitmap = image,
                contentDescription = if (showOriginal) "Original" else "Graded preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (image != null) {
            Text(
                if (showOriginal) "ORIGINAL" else "GRADED",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (showOriginal) Color.White else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text(
                "Hold to compare",
                fontSize = 11.sp,
                color = Color(0xCCFFFFFF),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .background(Color(0x88000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (p.loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(22.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun VideoScrubber(
    durationUs: Long,
    positionUs: Long,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) value else (positionUs.toFloat() / durationUs).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text(
            formatTime((shown * durationUs).toLong()),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = shown,
            onValueChange = { dragging = true; value = it },
            onValueChangeFinished = { dragging = false; onSeek(value) },
            enabled = enabled,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(
            formatTime(durationUs),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusLine(entry: MediaEntry) {
    val (text, color) = when (val s = entry.status) {
        is ItemStatus.Failed -> "Failed: ${s.message}" to MaterialTheme.colorScheme.error
        is ItemStatus.Done -> (s.note?.let { "Saved · $it" } ?: "Saved") to MaterialTheme.colorScheme.primary
        is ItemStatus.Working -> "Exporting… ${(s.progress * 100).roundToInt()}%" to
            MaterialTheme.colorScheme.onSurfaceVariant
        ItemStatus.Idle -> entry.name to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbnailStrip(
    state: UiState,
    onSelect: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onAdd: () -> Unit,
    onBrowse: () -> Unit,
) {
    var addMenu by remember { mutableStateOf(false) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.items, key = { it.id }) { entry ->
            val selected = entry.id == state.selectedId
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .size(68.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape,
                    )
                    .combinedClickable(
                        onClick = { onSelect(entry.id) },
                        onLongClick = { onRemove(entry.id) },
                    ),
            ) {
                entry.thumbnail?.let {
                    Image(it, contentDescription = entry.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (entry.isVideo) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .size(16.dp)
                            .background(Color(0x99000000), CircleShape),
                    )
                }
                when (val s = entry.status) {
                    is ItemStatus.Working -> Box(
                        Modifier.fillMaxSize().background(Color(0x99000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.size(30.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                    is ItemStatus.Done -> Badge(Icons.Filled.Check, MaterialTheme.colorScheme.primary)
                    is ItemStatus.Failed -> Badge(Icons.Filled.Warning, MaterialTheme.colorScheme.error)
                    ItemStatus.Idle -> Unit
                }
            }
        }
        item {
            Box(
                Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { addMenu = true }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add media", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("From gallery") }, onClick = { addMenu = false; onAdd() })
                    DropdownMenuItem(text = { Text("Browse files") }, onClick = { addMenu = false; onBrowse() })
                }
            }
        }
    }
    Hint("Tap to preview · long-press to remove")
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Badge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
) {
    Icon(
        icon,
        contentDescription = null,
        tint = Color.Black,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(18.dp)
            .background(color, CircleShape)
            .padding(2.dp),
    )
}

@Composable
private fun LutPicker(
    luts: List<String>,
    selected: String?,
    loaded: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    selected == null -> "Choose a LUT…"
                    !loaded -> "$selected (loading…)"
                    else -> selected
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            luts.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { open = false; onSelect(name) },
                    leadingIcon = if (name == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                    } else null,
                    trailingIcon = {
                        IconButton(onClick = { onDelete(name) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete $name")
                        }
                    },
                )
            }
            if (luts.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Import .cube file…") },
                onClick = { open = false; onImport() },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )
        }
    }
    if (luts.isEmpty()) {
        Hint("Import a .cube LUT, e.g. one of Sony's official LUTs or your own HLG→709 LUT.")
    }
}

@Composable
private fun ExposureControls(
    ev: Float,
    stage: ExposureStage,
    onEv: (Float) -> Unit,
    onStage: (ExposureStage) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Exposure",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            String.format(Locale.US, "%+.1f EV", ev),
            fontFamily = FontFamily.Monospace,
            color = if (ev == 0f) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = { onEv(0f) }, enabled = ev != 0f) { Text("Reset") }
    }
    Slider(
        value = ev,
        onValueChange = { onEv((it * 10f).roundToInt() / 10f) },
        valueRange = -4f..2f,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-2f, -1.5f, -1f, -0.5f).forEach { preset ->
            OutlinedButton(
                onClick = { onEv(preset) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text(String.format(Locale.US, "%.1f", preset), fontSize = 13.sp) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Segmented(
        options = listOf("Before transform", "After transform"),
        selectedIndex = stage.ordinal,
        onSelect = { onStage(ExposureStage.entries[it]) },
    )
    Hint(
        if (stage == ExposureStage.BEFORE) {
            "Changes exposure in scene light before the transform, like stopping down the " +
                "lens: highlights that were rolled off come back."
        } else {
            "Scales the finished Rec.709 image in linear light. Whites above the new level " +
                "turn grey rather than recovering detail."
        }
    )
}

@Composable
private fun Segmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

private fun formatTime(us: Long): String {
    val total = us / 1_000_000
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
