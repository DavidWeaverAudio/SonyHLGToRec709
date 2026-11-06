package com.weaveraudio.sonycam.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weaveraudio.sonycam.LutStore
import com.weaveraudio.sonycam.MediaSaver
import com.weaveraudio.sonycam.SettingsStore
import com.weaveraudio.sonycam.color.ColorPipeline
import com.weaveraudio.sonycam.color.CubeLut
import com.weaveraudio.sonycam.color.ExposureStage
import com.weaveraudio.sonycam.color.GradeSettings
import com.weaveraudio.sonycam.color.LutInput
import com.weaveraudio.sonycam.color.TransformMode
import com.weaveraudio.sonycam.color.VideoCodec
import com.weaveraudio.sonycam.image.BitmapSignal
import com.weaveraudio.sonycam.image.ImageGrader
import com.weaveraudio.sonycam.video.VideoFrameGrabber
import com.weaveraudio.sonycam.video.VideoGrader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

sealed interface ItemStatus {
    data object Idle : ItemStatus
    data class Working(val progress: Float) : ItemStatus
    data class Done(val note: String?) : ItemStatus
    data class Failed(val message: String) : ItemStatus
}

data class MediaEntry(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val name: String,
    val thumbnail: ImageBitmap? = null,
    val status: ItemStatus = ItemStatus.Idle,
)

/** The camera signal of whatever is being previewed, kept so regrading is instant. */
private class PreviewSource(
    val entryId: Long,
    val width: Int,
    val height: Int,
    val signal: FloatArray,
    val original: ImageBitmap,
)

data class PreviewState(
    val original: ImageBitmap? = null,
    val graded: ImageBitmap? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val durationUs: Long = 0,
    val positionUs: Long = 0,
    /** For videos: whether the GPU reads the decoder's YUV directly (null for photos). */
    val exactYuv: Boolean? = null,
)

data class UiState(
    val items: List<MediaEntry> = emptyList(),
    val selectedId: Long? = null,
    val settings: GradeSettings = GradeSettings(),
    val luts: List<String> = emptyList(),
    val lutLoaded: Boolean = false,
    val preview: PreviewState = PreviewState(),
    val exporting: Boolean = false,
    val message: String? = null,
) {
    val selected: MediaEntry? get() = items.firstOrNull { it.id == selectedId }
    val hasVideo: Boolean get() = items.any { it.isVideo }
    val overallProgress: Float
        get() = if (items.isEmpty()) 0f else items.sumOf {
            when (val s = it.status) {
                is ItemStatus.Working -> s.progress.toDouble()
                is ItemStatus.Done, is ItemStatus.Failed -> 1.0
                ItemStatus.Idle -> 0.0
            }
        }.toFloat() / items.size
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val lutStore = LutStore(app)
    private val settingsStore = SettingsStore(app)
    private val ids = AtomicLong(1)

    // One thread for preview frame grabs: each grab owns an EGL context while it runs.
    private val grabDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val items = MutableStateFlow<List<MediaEntry>>(emptyList())
    private val selectedId = MutableStateFlow<Long?>(null)
    private val settings = MutableStateFlow(settingsStore.load())
    private val luts = MutableStateFlow(lutStore.list())
    private val cube = MutableStateFlow<CubeLut?>(null)
    private val previewSource = MutableStateFlow<PreviewSource?>(null)
    private val preview = MutableStateFlow(PreviewState())
    private val previewPositionUs = MutableStateFlow(0L)
    private val exporting = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private var exportJob: Job? = null

    val state: StateFlow<UiState> = combine(
        combine(items, selectedId, settings, luts, cube) { i, s, st, l, c ->
            UiState(items = i, selectedId = s, settings = st, luts = l, lutLoaded = c != null)
        },
        preview, exporting, message,
    ) { base, p, e, m -> base.copy(preview = p, exporting = e, message = m) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        // Keep the parsed LUT in sync with the chosen file.
        viewModelScope.launch {
            settings.map { it.lutName }.distinctUntilChanged().collectLatest { name ->
                cube.value = name?.let {
                    withContext(Dispatchers.IO) {
                        runCatching { lutStore.load(it) }
                            .onFailure { e -> message.value = "Couldn't read LUT: ${e.message}" }
                            .getOrNull()
                    }
                }
            }
        }
        // Load the camera signal for the selected item (and video position).
        viewModelScope.launch {
            combine(selectedId, previewPositionUs) { id, pos -> id to pos }
                .distinctUntilChanged()
                .collectLatest { (id, pos) ->
                    val entry = items.value.firstOrNull { it.id == id }
                    if (entry == null) {
                        previewSource.value = null
                        preview.value = PreviewState()
                        return@collectLatest
                    }
                    preview.update { it.copy(loading = true, error = null) }
                    try {
                        val loaded = loadPreviewSource(entry, pos)
                        previewSource.value = loaded.source
                        preview.update {
                            it.copy(
                                original = loaded.source.original,
                                durationUs = loaded.durationUs,
                                positionUs = pos,
                                exactYuv = loaded.exactYuv,
                                error = null,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "Preview failed", t)
                        previewSource.value = null
                        preview.update {
                            PreviewState(error = t.message ?: t.javaClass.simpleName)
                        }
                    }
                }
        }
        // Regrade the preview whenever the source or the grade changes.
        viewModelScope.launch {
            combine(
                previewSource,
                settings.map { it.copy(videoCodec = VideoCodec.HEVC) }.distinctUntilChanged(),
                cube,
            ) { src, s, c -> Triple(src, s, c) }
                .collectLatest { (src, s, c) ->
                    if (src == null) return@collectLatest
                    val graded = withContext(Dispatchers.Default) { gradePreview(src, s, c) }
                    preview.update { it.copy(graded = graded, loading = false) }
                }
        }
    }

    private class LoadedPreview(val source: PreviewSource, val durationUs: Long, val exactYuv: Boolean?)

    private suspend fun loadPreviewSource(entry: MediaEntry, positionUs: Long): LoadedPreview {
        val app = getApplication<Application>()
        val bitmap: Bitmap
        var durationUs = 0L
        var exactYuv: Boolean? = null
        if (entry.isVideo) {
            val frame = withContext(grabDispatcher) {
                VideoFrameGrabber.grab(app, entry.uri, positionUs, PREVIEW_DIM)
            }
            bitmap = frame.bitmap
            durationUs = frame.durationUs
            exactYuv = frame.rawYuv
        } else {
            bitmap = withContext(Dispatchers.IO) { ImageGrader.decode(app, entry.uri, PREVIEW_DIM) }
        }
        return withContext(Dispatchers.Default) {
            val signal = BitmapSignal(bitmap)
            bitmap.recycle()
            val floats = ImageGrader.toFloats(signal)
            val original = ImageGrader.fromFloats(signal.width, signal.height, floats)
            LoadedPreview(
                PreviewSource(entry.id, signal.width, signal.height, floats, original.asImageBitmap()),
                durationUs,
                exactYuv,
            )
        }
    }

    private suspend fun gradePreview(src: PreviewSource, s: GradeSettings, c: CubeLut?): ImageBitmap {
        val lut = ColorPipeline.bake(s, if (s.mode == TransformMode.LUT) c else null, ColorPipeline.PREVIEW_SIZE)
        val px = src.width * src.height
        val out = src.signal.copyOf()
        val chunks = 8
        val per = (px + chunks - 1) / chunks
        coroutineScope {
            (0 until chunks).map { i ->
                async {
                    val start = i * per
                    val count = minOf(per, px - start)
                    if (count > 0) lut.applyInPlace(out, count, start)
                }
            }.awaitAll()
        }
        return ImageGrader.fromFloats(src.width, src.height, out).asImageBitmap()
    }

    // ---- Items ----

    fun addItems(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        val existing = items.value.map { it.uri }.toSet()
        val added = uris.filter { it !in existing }.map { uri ->
            val type = app.contentResolver.getType(uri) ?: ""
            MediaEntry(
                id = ids.getAndIncrement(),
                uri = uri,
                isVideo = type.startsWith("video/"),
                name = MediaSaver.displayName(app, uri),
            )
        }
        items.update { it + added }
        if (selectedId.value == null) added.firstOrNull()?.let { select(it.id) }
        added.forEach { entry ->
            viewModelScope.launch(Dispatchers.IO) {
                val thumb = runCatching {
                    app.contentResolver.loadThumbnail(entry.uri, Size(240, 240), null).asImageBitmap()
                }.recoverCatching {
                    if (entry.isVideo) {
                        android.media.MediaMetadataRetriever().run {
                            try {
                                setDataSource(app, entry.uri)
                                getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)!!
                            } finally {
                                release()
                            }
                        }.asImageBitmap()
                    } else {
                        ImageGrader.decode(app, entry.uri, 240).asImageBitmap()
                    }
                }.getOrNull()
                if (thumb != null) updateItem(entry.id) { it.copy(thumbnail = thumb) }
            }
        }
    }

    fun select(id: Long) {
        if (selectedId.value == id) return
        previewPositionUs.value = 0L
        selectedId.value = id
    }

    fun remove(id: Long) {
        if (exporting.value) return
        items.update { list -> list.filterNot { it.id == id } }
        if (selectedId.value == id) selectedId.value = items.value.firstOrNull()?.id
    }

    fun clear() {
        if (exporting.value) return
        items.value = emptyList()
        selectedId.value = null
    }

    fun setPreviewPosition(fraction: Float) {
        val duration = preview.value.durationUs
        if (duration > 0) previewPositionUs.value = (duration * fraction.coerceIn(0f, 1f)).toLong()
    }

    // ---- Grade settings ----

    private fun updateSettings(transform: (GradeSettings) -> GradeSettings) {
        settings.update(transform)
        settingsStore.save(settings.value)
    }

    fun setMode(mode: TransformMode) = updateSettings { it.copy(mode = mode) }
    fun setExposure(ev: Float) = updateSettings { it.copy(exposureEv = ev) }
    fun setExposureStage(stage: ExposureStage) = updateSettings { it.copy(exposureStage = stage) }
    fun setLutInput(input: LutInput) = updateSettings { it.copy(lutInput = input) }
    fun setCodec(codec: VideoCodec) = updateSettings { it.copy(videoCodec = codec) }
    fun selectLut(name: String) = updateSettings { it.copy(lutName = name, mode = TransformMode.LUT) }

    fun importLut(uri: Uri) {
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { lutStore.import(uri) }
                luts.value = lutStore.list()
                // Force a reload even if a file of the same name was already selected.
                updateSettings { it.copy(lutName = null) }
                selectLut(name)
                message.value = "Imported $name"
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                message.value = "Not a usable .cube LUT: ${t.message}"
            }
        }
    }

    fun deleteLut(name: String) {
        lutStore.delete(name)
        luts.value = lutStore.list()
        if (settings.value.lutName == name) updateSettings { it.copy(lutName = null) }
    }

    fun clearMessage() {
        message.value = null
    }

    // ---- Export ----

    fun export() {
        if (exporting.value || items.value.isEmpty()) return
        val s = settings.value
        val c = cube.value
        if (s.mode == TransformMode.LUT && c == null) {
            message.value = "Choose or import a .cube LUT first"
            return
        }
        val app = getApplication<Application>()
        exportJob = viewModelScope.launch {
            exporting.value = true
            items.update { list -> list.map { it.copy(status = ItemStatus.Idle) } }
            var ok = 0
            var failed = 0
            try {
                val lut = withContext(Dispatchers.Default) {
                    ColorPipeline.bake(s, if (s.mode == TransformMode.LUT) c else null, ColorPipeline.EXPORT_SIZE)
                }
                for (entry in items.value) {
                    updateItem(entry.id) { it.copy(status = ItemStatus.Working(0f)) }
                    var lastReported = -1
                    val progress: (Float) -> Unit = { p ->
                        val pct = (p * 100).toInt()
                        if (pct != lastReported) {
                            lastReported = pct
                            updateItem(entry.id) { it.copy(status = ItemStatus.Working(p)) }
                        }
                    }
                    try {
                        val note = if (entry.isVideo) {
                            VideoGrader.export(app, entry.uri, lut, s.videoCodec, progress).note
                        } else {
                            ImageGrader.export(app, entry.uri, lut, progress)
                            null
                        }
                        ok++
                        updateItem(entry.id) { it.copy(status = ItemStatus.Done(note)) }
                    } catch (e: CancellationException) {
                        updateItem(entry.id) { it.copy(status = ItemStatus.Idle) }
                        throw e
                    } catch (t: Throwable) {
                        Log.e(TAG, "Export failed for ${entry.name}", t)
                        failed++
                        val msg = if (t is OutOfMemoryError) "Not enough memory for this file" else
                            (t.message ?: t.javaClass.simpleName)
                        updateItem(entry.id) { it.copy(status = ItemStatus.Failed(msg)) }
                    }
                }
                message.value = buildString {
                    append("Saved $ok file${if (ok == 1) "" else "s"} to ${MediaSaver.FOLDER}")
                    if (failed > 0) append(" · $failed failed")
                }
            } catch (e: CancellationException) {
                message.value = "Export cancelled"
            } finally {
                exporting.value = false
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    private fun updateItem(id: Long, transform: (MediaEntry) -> MediaEntry) {
        items.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    override fun onCleared() {
        grabDispatcher.close()
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val PREVIEW_DIM = 1600
    }
}
