package com.mediagrab

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try { YoutubeDL.getInstance().init(this); FFmpeg.getInstance().init(this) }
        catch (e: Exception) { e.printStackTrace() }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        setContent { MaterialTheme { Screen(shared) } }
    }
}

fun saveToDownloads(ctx: Context, f: File) {
    val mime = when (f.extension.lowercase()) {
        "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"
        "gif" -> "image/gif"; "jpg", "jpeg" -> "image/jpeg"; else -> "application/octet-stream"
    }
    val v = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, f.name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/MediaGrab")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
    ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Screen(initial: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initial) }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var fmt by remember { mutableStateOf("MP4") }
    var quality by remember { mutableStateOf(1080) }
    var keepPoster by remember { mutableStateOf(true) }
    var gifStart by remember { mutableStateOf("0") }
    var gifLen by remember { mutableStateOf("5") }

    val platform = when {
        "tiktok" in url -> "TikTok"; "youtu" in url -> "YouTube"
        url.isBlank() -> ""; else -> "Autre site"
    }

    fun analyse() = scope.launch {
        busy = true; status = "Analyse…"; info = null
        try { info = withContext(Dispatchers.IO) { YoutubeDL.getInstance().getInfo(url.trim()) }; status = "" }
        catch (e: Exception) { status = "Erreur : ${e.message}" }
        busy = false
    }

    fun download() = scope.launch {
        busy = true; progress = 0f; status = "Téléchargement…"
        try {
            withContext(Dispatchers.IO) {
                val tmp = File(ctx.cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
                val r = YoutubeDLRequest(url.trim())
                r.addOption("-o", "${tmp.absolutePath}/%(title).80B.%(ext)s")
                r.addOption("--no-playlist")
                if (keepPoster) { r.addOption("--write-thumbnail"); r.addOption("--convert-thumbnails", "jpg") }
                when (fmt) {
                    "MP4" -> {
                        r.addOption("-f", "bv*[height<=$quality]+ba/b[height<=$quality]/b")
                        r.addOption("--merge-output-format", "mp4")
                    }
                    "MP3", "WAV" -> {
                        r.addOption("-x")
                        r.addOption("--audio-format", fmt.lowercase())
                        r.addOption("--audio-quality", "0")
                    }
                    "GIF" -> {
                        val s = gifStart.toIntOrNull() ?: 0
                        val l = gifLen.toIntOrNull() ?: 5
                        r.addOption("-f", "b[height<=480]/b")
                        r.addOption("--download-sections", "*$s-${s + l}")
                        r.addOption("--recode-video", "gif")
                        r.addOption("--postprocessor-args", "VideoConvertor:-vf fps=12,scale=480:-1:flags=lanczos")
                    }
                }
                YoutubeDL.getInstance().execute(r) { p, _, _ -> progress = p / 100f }
                tmp.listFiles()?.forEach { saveToDownloads(ctx, it) }
            }
            status = "✅ Terminé ! Dossier Téléchargements/MediaGrab"
        } catch (e: Exception) { status = "Erreur : ${e.message}" }
        busy = false
    }

    Column(
        Modifier.statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MediaGrab", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(),
            label = { Text("Lien TikTok / YouTube") }, supportingText = { if (platform.isNotEmpty()) Text("Détecté : $platform") })
        Button({ analyse() }, enabled = !busy && url.isNotBlank()) { Text("Analyser") }

        info?.let { i ->
            AsyncImage(i.thumbnail, null, Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Crop)
            Text(i.title ?: "", style = MaterialTheme.typography.titleMedium)
            Text("Auteur : ${i.uploader}  •  Durée : ${i.duration / 60}:${"%02d".format(i.duration % 60)}")
            Text("Vues : ${i.viewCount}  •  Likes : ${i.likeCount}  •  Date : ${i.uploadDate}")

            Text("Format")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("MP4", "MP3", "WAV", "GIF").forEach {
                    FilterChip(fmt == it, { fmt = it }, { Text(it) })
                }
            }
            if (fmt == "MP4") {
                Text("Qualité")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2160, 1440, 1080, 720, 480, 360).forEach {
                        FilterChip(quality == it, { quality = it }, { Text("${it}p") })
                    }
                }
            }
            if (fmt == "GIF") {
                Text("Extrait en GIF (secondes)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(gifStart, { gifStart = it }, Modifier.weight(1f), label = { Text("Début") })
                    OutlinedTextField(gifLen, { gifLen = it }, Modifier.weight(1f), label = { Text("Durée") })
                }
            }
            Row { Switch(keepPoster, { keepPoster = it }); Spacer(Modifier.width(8.dp)); Text("Enregistrer aussi l'affiche (JPG)") }
            Button({ download() }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Télécharger") }
        }
        if (busy && progress > 0f) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status)
        TextButton({
            scope.launch {
                status = "Mise à jour de yt-dlp…"
                status = try { withContext(Dispatchers.IO) { YoutubeDL.getInstance().updateYoutubeDL(ctx) }; "yt-dlp à jour" }
                catch (e: Exception) { "Erreur : ${e.message}" }
            }
        }) { Text("Mettre à jour yt-dlp (si un site ne marche plus)") }
    }
}
