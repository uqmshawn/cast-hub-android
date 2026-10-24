package com.shawn.casthub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var castContext: CastContext
    private lateinit var adapter: VideoAdapter
    private lateinit var emptyView: TextView
    private lateinit var search: EditText
    private lateinit var remoteStatus: TextView

    private var allVideos: List<Video> = emptyList()
    private val compat = HashMap<Long, CompatResult>()
    private val progress = HashMap<Long, Int>()
    private lateinit var converter: Converter
    private val bg = Executors.newFixedThreadPool(2)

    /** Something the user asked for before a TV was connected; done once they pick one. */
    private var pending: (() -> Unit)? = null
    private var subtitlesFor: Long = -1
    private var attached: CastSession? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadVideos() }

    private val pickSubtitles =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && subtitlesFor >= 0) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                Subtitles.set(this, subtitlesFor, uri)
                adapter.notifyDataSetChanged()
                toast("Subtitles added")
            }
        }

    /** Remembers where you stopped in each video ("Continue watching"). */
    private val progressListener = RemoteMediaClient.ProgressListener { pos, dur -> saveProgress(pos, dur) }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = connected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session)
        override fun onSessionEnded(session: CastSession, error: Int) {
            detachTv()
            if (!converter.isBusy) StreamService.stop(this@MainActivity)
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            toast("Couldn't connect to the TV")
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private fun connected(session: CastSession) {
        attachTv(session)
        pending?.invoke()
        pending = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        @Suppress("DEPRECATION")
        castContext = CastContext.getSharedInstance(this)
        converter = Converter(this)
        if (!converter.isBusy && LiveConvert.id < 0) Converted.cleanLive(this)

        emptyView = findViewById(R.id.empty)
        search = findViewById(R.id.search)
        remoteStatus = findViewById(R.id.remoteStatus)

        adapter = VideoAdapter(
            context = this,
            executor = bg,
            compatOf = { v -> compat[v.id] },
            onNeedCheck = { v -> checkCompat(v) },
            hasSubtitles = { v -> Subtitles.get(this, v.id) != null },
            progressOf = { v -> progress[v.id] },
            isConverted = { v -> Converted.exists(this, v.id) },
            resumeOf = { v -> Prefs.resume(this, v.id) },
            onPlay = { v -> playVideo(v) },
            onSubtitles = { v -> subtitlesClicked(v) },
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        search.doAfterTextChanged { applyFilter() }
        findViewById<MaterialButton>(R.id.btnLink).setOnClickListener { showLinkDialog(null) }

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            showPanel(item.itemId)
            true
        }
        setUpRemote()
        buildTvPanel()

        requestPermissionsAndLoad()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        castContext.sessionManager.currentCastSession?.let { if (it.isConnected && attached == null) attachTv(it) }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged() // refresh resume positions
        updateRemoteStatus()
    }

    override fun onPause() {
        castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) converter.cancel()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        CastButtonFactory.setUpMediaRouteButton(applicationContext, menu, R.id.media_route_menu_item)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_refresh) {
            loadVideos()
            return true
        }
        if (item.itemId == R.id.action_clear) {
            if (converter.isBusy) {
                toast("Wait for the current conversion to finish")
            } else {
                val freed = Converted.clearAll(this)
                adapter.notifyDataSetChanged()
                toast("Deleted converted copies (${freed / 1_000_000} MB freed)")
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showPanel(navId: Int) {
        findViewById<View>(R.id.panelVideos).visibility = if (navId == R.id.nav_videos) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panelRemote).visibility = if (navId == R.id.nav_remote) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panelTv).visibility = if (navId == R.id.nav_tv) View.VISIBLE else View.GONE
        if (navId == R.id.nav_tv) buildTvPanel()
        if (navId == R.id.nav_remote) updateRemoteStatus()
    }

    // ---------------- videos ----------------

    private fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasVideoPermission(): Boolean =
        granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasPhotoPermission(): Boolean =
        granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun requestPermissionsAndLoad() {
        if (hasVideoPermission() && hasPhotoPermission()) loadVideos() else permissionLauncher.launch(mediaPermissions())
    }

    private fun loadVideos() {
        if (!hasVideoPermission()) {
            showEmpty("Cast Hub needs access to your videos.\nAllow it in Settings → Apps → Cast Hub → Permissions.")
            return
        }
        bg.execute {
            val list = try { Videos.load(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                allVideos = list
                applyFilter()
                sendLibrary()
                resumePending()
            }
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val shown = if (q.isEmpty()) allVideos else allVideos.filter {
            it.title.lowercase().contains(q) || it.folder.lowercase().contains(q)
        }
        adapter.submit(shown)
        when {
            allVideos.isEmpty() && hasVideoPermission() ->
                showEmpty("No videos on this phone yet.\nDownload or copy movies to the phone, then tap ⟳.")
            shown.isEmpty() -> showEmpty("No matches.")
            else -> emptyView.visibility = View.GONE
        }
    }

    private fun showEmpty(msg: String) {
        emptyView.text = msg
        emptyView.visibility = View.VISIBLE
    }

    private fun checkCompat(v: Video) {
        if (compat.containsKey(v.id)) return
        compat[v.id] = CompatResult(Compat.CHECKING, "")
        bg.execute {
            val r = Videos.check(this, v)
            runOnUiThread {
                compat[v.id] = r
                adapter.refresh(v.id)
            }
        }
    }

    private fun subtitlesClicked(v: Video) {
        if (Subtitles.get(this, v.id) != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Subtitles")
                .setMessage("This video has a subtitle file attached.")
                .setPositiveButton("Replace") { _, _ -> pickSubtitleFile(v) }
                .setNegativeButton("Remove") { _, _ ->
                    Subtitles.set(this, v.id, null)
                    adapter.refresh(v.id)
                }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            pickSubtitleFile(v)
        }
    }

    private fun pickSubtitleFile(v: Video) {
        subtitlesFor = v.id
        toast("Pick the .srt or .vtt subtitle file")
        pickSubtitles.launch(arrayOf("*/*"))
    }

    private fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds / 60) % 60
        val s = seconds % 60
        return if (h > 0) "$h:${"%02d".format(m)}:${"%02d".format(s)}" else "$m:${"%02d".format(s)}"
    }

    private fun metaText(v: Video): String {
        val bits = mutableListOf<String>()
        if (v.durationMs > 0) bits += formatTime(v.durationMs / 1000)
        bits += if (v.size >= 1_000_000_000) String.format("%.1f GB", v.size / 1e9) else "${v.size / 1_000_000} MB"
        return bits.joinToString(" · ")
    }

    // ---------------- casting ----------------

    private fun connectedSession(retry: () -> Unit): CastSession? {
        val s = castContext.sessionManager.currentCastSession
        if (s != null && s.isConnected) return s
        pending = retry
        toast("Tap the cast icon at the top and choose your TV")
        return null
    }

    private fun playVideo(v: Video) {
        if (progress.containsKey(v.id)) {
            if (LiveConvert.isLive(v.id)) castVideo(v, openControls = true, live = true)
            else toast("Still converting — it will play when it's done")
            return
        }
        if (Converted.exists(this, v.id)) {
            castVideo(v)
            return
        }
        val r = compat[v.id]
        if (r?.compat == Compat.MAYBE_NOT) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Play on your Chromecast")
                .setMessage(
                    "${r.note}.\n\nYour 1st-gen Chromecast can't play this format directly, so your phone converts it.\n\n" +
                        "• Play now — starts in a few seconds and converts while it plays. Skipping ahead only " +
                        "works up to the part already converted.\n" +
                        "• Convert first — waits until it's done, then plays with full skipping."
                )
                .setPositiveButton("Play now") { _, _ -> playLive(v, r, fromTv = false) }
                .setNeutralButton("Convert first") { _, _ -> convertThenPlay(v, r, fromTv = false) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            castVideo(v)
        }
    }

    private fun convertThenPlay(v: Video, r: CompatResult, fromTv: Boolean, play: Boolean = true) {
        if (converter.isBusy) {
            val msg = "Another video is converting — wait for it to finish"
            if (fromTv) tvStatus(msg) else if (play) toast(msg)
            return
        }
        Prefs.addPending(this, v.id)
        StreamService.start(this) // keeps converting even if you switch apps or turn the screen off
        progress[v.id] = 0
        adapter.refresh(v.id)
        if (fromTv) tvStatus("Converting \"${v.title}\" on your phone…", 6)
        else if (play) toast("Converting… you can switch apps, it keeps going")
        var lastReported = 0
        val out = Converted.file(this, v.id)
        val tmp = java.io.File(out.parentFile, "${v.id}.part.mp4")
        converter.convert(
            v, r.width, r.height, tmp, fragmented = false, maxShortSide = 1080, bitrate = 6_000_000,
            onProgress = { p ->
                progress[v.id] = p
                adapter.refresh(v.id)
                if (fromTv && p - lastReported >= 20) {
                    lastReported = p
                    tvStatus("Converting… $p%", 5)
                }
            },
            onDone = { converted, err ->
                val ok = converted && tmp.renameTo(out)
                Prefs.removePending(this, v.id)
                progress.remove(v.id)
                adapter.refresh(v.id)
                if (ok) {
                    if (play) {
                        if (!fromTv) toast("Converted — sending to the TV")
                        castVideo(v, openControls = !fromTv)
                    } else {
                        toast("Finished converting \"${v.title}\"")
                    }
                    sendLibrary()
                } else {
                    val msg = "Conversion failed: $err"
                    if (fromTv) tvStatus(msg, 8) else toast(msg)
                }
                conversionFinished()
            },
        )
    }

    /** Stops the background service once nothing is converting or playing, then resumes any queued conversions. */
    private fun conversionFinished() {
        val s = castContext.sessionManager.currentCastSession
        if (s == null || !s.isConnected) StreamService.stop(this)
        resumePending()
    }

    /** Conversions that were interrupted (app closed, phone restarted) carry on, one at a time. */
    private fun resumePending() {
        if (converter.isBusy || allVideos.isEmpty()) return
        val pending = Prefs.pending(this)
        for (id in pending) {
            val v = allVideos.find { it.id == id }
            if (v == null || Converted.exists(this, id)) {
                Prefs.removePending(this, id)
                continue
            }
            bg.execute {
                val r = compat[id]?.takeIf { it.compat != Compat.CHECKING } ?: Videos.check(this, v)
                runOnUiThread {
                    compat[id] = r
                    if (r.compat == Compat.READY) {
                        Prefs.removePending(this, id)
                        resumePending()
                    } else if (!converter.isBusy) {
                        toast("Resuming conversion of \"${v.title}\"")
                        convertThenPlay(v, r, fromTv = false, play = false)
                    }
                }
            }
            return
        }
    }

    /** Converts and plays at the same time: the TV starts after a short buffer while the phone keeps converting. */
    private fun playLive(v: Video, r: CompatResult, fromTv: Boolean) {
        if (converter.isBusy) {
            val msg = "Another video is converting — wait for it to finish"
            if (fromTv) tvStatus(msg) else toast(msg)
            return
        }
        if (connectedSession { playLive(v, r, fromTv) } == null) return
        val live = Converted.liveFile(this, v.id)
        LiveConvert.start(v.id, live)
        Prefs.addPending(this, v.id) // if the app is closed before 100%, it finishes converting next time
        StreamService.start(this)
        progress[v.id] = 0
        adapter.refresh(v.id)
        if (fromTv) tvStatus("Getting \"${v.title}\" ready…", 6) else toast("Starting — the TV will begin in a few seconds")

        var started = false
        converter.convert(
            v, r.width, r.height, live, fragmented = true, maxShortSide = 720, bitrate = 3_000_000,
            onProgress = { p ->
                progress[v.id] = p
                adapter.refresh(v.id)
            },
            onDone = { ok, err ->
                LiveConvert.finish(ok)
                progress.remove(v.id)
                adapter.refresh(v.id)
                if (!ok) {
                    val msg = "Conversion failed: $err"
                    if (fromTv) tvStatus(msg, 8) else toast(msg)
                    LiveConvert.clear()
                    Prefs.removePending(this, v.id)
                    conversionFinished()
                    return@convert
                }
                if (!started) {
                    started = true
                    castVideo(v, openControls = !fromTv, live = true)
                }
                // Keep a normal copy so next time it plays instantly with full skipping.
                converter.remux(live, Converted.file(this, v.id)) { saved ->
                    if (saved) {
                        LiveConvert.clear()
                        live.delete() // the TV keeps reading through its open handle
                        Prefs.removePending(this, v.id)
                        adapter.refresh(v.id)
                        sendLibrary()
                    }
                    conversionFinished()
                }
            },
        )

        // Start the TV once a few seconds of video are ready (or after 25 s at the latest).
        val began = System.currentTimeMillis()
        val h = android.os.Handler(mainLooper)
        h.post(object : Runnable {
            override fun run() {
                if (started || LiveConvert.id != v.id || LiveConvert.failed) return
                // ~3 s of 720p video is enough to start; the phone keeps converting ahead of the TV.
                val ready = LiveConvert.safeBytes() > 1_000_000 || (progress[v.id] ?: 0) >= 2
                if (ready || System.currentTimeMillis() - began > 15_000) {
                    started = true
                    castVideo(v, openControls = !fromTv, live = true)
                } else {
                    h.postDelayed(this, 250)
                }
            }
        })
    }

    private fun castVideo(v: Video, openControls: Boolean = true) = castVideo(v, openControls, live = false)

    private fun castVideo(v: Video, openControls: Boolean, live: Boolean) {
        val session = connectedSession { castVideo(v, openControls, live) } ?: return
        val ip = Net.localIp()
        if (ip == null) {
            toast("Connect your phone to the same Wi-Fi as the TV")
            return
        }
        val port = try { ServerHolder.ensure(applicationContext) } catch (e: Exception) {
            toast(e.message ?: "Couldn't start streaming")
            return
        }
        StreamService.start(this)

        val base = "http://$ip:$port"
        val token = ServerHolder.token
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, v.title)
            if (v.folder.isNotEmpty()) putString(MediaMetadata.KEY_SUBTITLE, v.folder)
        }
        val builder = MediaInfo.Builder("$base/v/$token/${v.id}")
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(if (live || Converted.exists(this, v.id)) "video/mp4" else v.mime ?: "video/mp4")
            .setMetadata(meta)
        if (v.durationMs > 0) builder.setStreamDuration(v.durationMs)

        val hasSubs = Subtitles.get(this, v.id) != null
        if (hasSubs) {
            val track = MediaTrack.Builder(1, MediaTrack.TYPE_TEXT)
                .setName("Subtitles")
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId("$base/s/$token/${v.id}.vtt")
                .setContentType("text/vtt")
                .setLanguage("en-US")
                .build()
            builder.setMediaTracks(listOf(track))
        }
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(builder.build())
            .setAutoplay(true)
        if (hasSubs) request.setActiveTrackIds(longArrayOf(1))

        val resume = if (live) 0 else Prefs.resume(this, v.id)
        if (resume > 0) {
            request.setCurrentTime(resume * 1000)
            toast("Resuming from ${formatTime(resume)}")
        }
        load(session, request.build(), openControls)
    }

    private fun castLink(url: String, title: String, live: Boolean) {
        val session = connectedSession { castLink(url, title, live) } ?: return
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val type = when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mp3") -> "audio/mpeg"
            else -> "video/mp4"
        }
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title.ifBlank { "Web video" })
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(type)
            .setMetadata(meta)
            .build()
        load(session, MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build(), true)
    }

    private fun load(session: CastSession, request: MediaLoadRequestData, openControls: Boolean) {
        val client = session.remoteMediaClient ?: run {
            toast("TV not ready yet, try again")
            return
        }
        client.load(request).setResultCallback { result ->
            if (result.status.isSuccess) {
                if (openControls) startActivity(Intent(this, ExpandedControlsActivity::class.java))
            } else {
                toast("The TV couldn't play this (code ${result.status.statusCode})")
            }
        }
    }

    private fun saveProgress(posMs: Long, durMs: Long) {
        val contentId = attached?.remoteMediaClient?.mediaInfo?.contentId ?: return
        val id = Regex("/v/[^/]+/(\\d+)").find(contentId)?.groupValues?.get(1)?.toLongOrNull() ?: return
        if (durMs <= 0) return
        when {
            posMs >= durMs - 90_000 -> Prefs.setResume(this, id, 0)   // finished (or nearly)
            posMs > 30_000 -> Prefs.setResume(this, id, posMs / 1000)
        }
    }

    // ---------------- TV home screen: two-way link ----------------

    private fun hasTvScreen() = Prefs.appId(this).isNotBlank()

    private fun attachTv(session: CastSession) {
        if (attached === session) return
        detachTv()
        attached = session
        session.remoteMediaClient?.addProgressListener(progressListener, 5000)
        if (hasTvScreen()) {
            try {
                session.setMessageReceivedCallbacks(TV_NAMESPACE) { _, _, message ->
                    runOnUiThread { onTvMessage(message) }
                }
            } catch (_: Exception) {
            }
            sendLibrary()
        }
        updateRemoteStatus()
    }

    private fun detachTv() {
        attached?.remoteMediaClient?.removeProgressListener(progressListener)
        attached = null
        updateRemoteStatus()
    }

    private fun onTvMessage(message: String) {
        val j = try { JSONObject(message) } catch (e: Exception) { return }
        when (j.optString("type")) {
            "hello" -> sendLibrary()
            "play" -> playFromTv(j.optLong("id", -1))
        }
    }

    /** A video was chosen with the remote on the TV. */
    private fun playFromTv(id: Long) {
        val v = allVideos.find { it.id == id } ?: run {
            tvStatus("That video isn't on the phone any more")
            return
        }
        if (progress.containsKey(id)) {
            if (LiveConvert.isLive(id)) castVideo(v, openControls = false, live = true)
            else tvStatus("Still converting — it'll play when it's done")
            return
        }
        if (Converted.exists(this, id)) {
            castVideo(v, openControls = false)
            return
        }
        val r = compat[id]
        if (r == null || r.compat == Compat.CHECKING) {
            bg.execute {
                val res = Videos.check(this, v)
                runOnUiThread {
                    compat[id] = res
                    adapter.refresh(id)
                    playFromTv(id)
                }
            }
            return
        }
        if (r.compat == Compat.MAYBE_NOT) playLive(v, r, fromTv = true) else castVideo(v, openControls = false)
    }

    /** Sends the video list, photo list and resume positions to the TV home screen. */
    private fun sendLibrary() {
        val session = castContext.sessionManager.currentCastSession ?: return
        if (!session.isConnected || !hasTvScreen()) return
        val ip = Net.localIp() ?: return
        val port = try { ServerHolder.ensure(applicationContext) } catch (e: Exception) { return }
        StreamService.start(this)

        val videos = allVideos.take(40)
        val compatSnap = HashMap(compat)
        val photosAllowed = hasPhotoPermission()
        bg.execute {
            val photos = if (photosAllowed) Photos.load(this, 60) else emptyList()
            val resume = Prefs.allResume(this)
            val arr = JSONArray()
            for (v in videos) {
                val needs = compatSnap[v.id]?.compat == Compat.MAYBE_NOT && !Converted.exists(this, v.id)
                arr.put(
                    JSONObject().put("id", v.id).put("t", v.title).put("m", metaText(v))
                        .put("d", v.durationMs / 1000).put("c", if (needs) 1 else 0)
                )
            }
            val res = JSONObject()
            for ((vid, s) in resume) if (videos.any { it.id == vid }) res.put(vid.toString(), s)
            val msg = JSONObject().put("type", "library")
                .put("base", "http://$ip:$port").put("token", ServerHolder.token)
                .put("videos", arr).put("photos", JSONArray(photos)).put("resume", res)
            runOnUiThread { sendTvQuiet(msg) }
        }
    }

    private fun tvStatus(text: String, seconds: Int = 4) =
        sendTvQuiet(JSONObject().put("type", "status").put("text", text).put("seconds", seconds))

    private fun sendTvQuiet(json: JSONObject) {
        val s = castContext.sessionManager.currentCastSession ?: return
        if (!s.isConnected || !hasTvScreen()) return
        try {
            s.sendMessage(TV_NAMESPACE, json.toString())
        } catch (_: Exception) {
        }
    }

    private fun sendTv(json: JSONObject) {
        if (!hasTvScreen()) {
            toast("Set up the TV home screen first (TV tab → TV screen App ID)")
            return
        }
        val session = connectedSession { sendTv(json) } ?: return
        try {
            session.sendMessage(TV_NAMESPACE, json.toString()).setResultCallback { status ->
                if (!status.isSuccess) toast("The TV didn't respond — is the Cast Hub home screen showing?")
            }
        } catch (e: Exception) {
            toast("Couldn't reach the TV: ${e.message}")
        }
    }

    // ---------------- Remote tab ----------------

    private fun setUpRemote() {
        fun bind(id: Int, key: String) = findViewById<View>(id).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendTv(JSONObject().put("type", "key").put("key", key))
        }
        bind(R.id.kUp, "up")
        bind(R.id.kDown, "down")
        bind(R.id.kLeft, "left")
        bind(R.id.kRight, "right")
        bind(R.id.kOk, "ok")
        bind(R.id.kBack, "back")
        bind(R.id.kHome, "home")

        findViewById<View>(R.id.kPlay).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val client = castContext.sessionManager.currentCastSession?.remoteMediaClient
            if (client != null && client.hasMediaSession()) client.togglePlayback()
            else sendTv(JSONObject().put("type", "key").put("key", "ok"))
        }
        findViewById<View>(R.id.kVolUp).setOnClickListener { changeVolume(0.05) }
        findViewById<View>(R.id.kVolDown).setOnClickListener { changeVolume(-0.05) }
    }

    private fun changeVolume(delta: Double) {
        val s = connectedSession { changeVolume(delta) } ?: return
        try {
            s.volume = (s.volume + delta).coerceIn(0.0, 1.0)
        } catch (_: Exception) {
        }
    }

    private fun updateRemoteStatus() {
        if (!::remoteStatus.isInitialized) return
        val s = castContext.sessionManager.currentCastSession
        remoteStatus.text = when {
            !hasTvScreen() -> "Set up the TV home screen in the TV tab to use ▲▼◀▶ / OK.\nPlay/pause and volume work already."
            s != null && s.isConnected -> "Connected to ${s.castDevice?.friendlyName ?: "your TV"}"
            else -> "Tap the cast icon at the top to connect to your TV"
        }
    }

    // ---------------- TV tab ----------------

    private fun buildTvPanel() {
        val box = findViewById<LinearLayout>(R.id.tvActions)
        box.removeAllViews()
        fun action(title: String, sub: String, onClick: () -> Unit) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_action, box, false)
            v.findViewById<TextView>(R.id.actionTitle).text = title
            v.findViewById<TextView>(R.id.actionSub).text = sub
            v.setOnClickListener { onClick() }
            box.addView(v)
        }
        fun section(title: String) {
            box.addView(TextView(this).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                textSize = 13f
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(pad / 2, pad * 2, 0, pad / 2)
            })
        }

        val appId = Prefs.appId(this)
        if (appId.isBlank()) {
            section("SET UP")
            action("TV screen App ID", "Not set — using Google's standard player. Tap to enter your App ID.") { askAppId() }
            return
        }
        section("ON THE TV")
        action("Home screen", "Clock, weather and your videos & photos") { sendTv(JSONObject().put("type", "home")) }
        action("Photo slideshow", "Your latest photos, full screen") { sendTv(JSONObject().put("type", "slideshow")) }
        action("Clock screensaver", "Big clock, nothing else") { sendTv(JSONObject().put("type", "screensaver")) }
        action("Send a message", "Pops up full screen on the TV") {
            askText("Message for the TV", "e.g. Dinner's ready!", "") { text ->
                sendTv(JSONObject().put("type", "message").put("text", text).put("seconds", 20))
            }
        }
        action("Show a website", "Simple pages only — many sites block this") {
            askText("Show a website", "https://…", "https://") { url ->
                sendTv(JSONObject().put("type", "web").put("url", url))
            }
        }
        action("Refresh videos & photos on TV", "Send the latest list to the home screen") {
            if (connectedSession { sendLibrary() } != null) {
                sendLibrary()
                toast("Sent to the TV")
            }
        }
        section("SETTINGS")
        action("Weather location", Prefs.get(this, "place", "Brisbane")) {
            askText("Weather location", "Suburb or city", Prefs.get(this, "place", "Brisbane")) { place ->
                Prefs.put(this, "place", place)
                sendTv(JSONObject().put("type", "config").put("place", place))
                buildTvPanel()
            }
        }
        action("TV name", Prefs.get(this, "tvName", "Lounge TV")) {
            askText("TV name", "Shown on the home screen", Prefs.get(this, "tvName", "Lounge TV")) { name ->
                Prefs.put(this, "tvName", name)
                sendTv(JSONObject().put("type", "config").put("name", name))
                buildTvPanel()
            }
        }
        val h24 = Prefs.get(this, "h24", "false") == "true"
        action("Clock style", if (h24) "24-hour — tap for 12-hour" else "12-hour — tap for 24-hour") {
            Prefs.put(this, "h24", (!h24).toString())
            sendTv(JSONObject().put("type", "config").put("h24", !h24))
            buildTvPanel()
        }
        action("TV screen App ID", "$appId — tap to change or clear") { askAppId() }
    }

    private fun askAppId() {
        askText(
            "TV screen App ID",
            "8 characters from the Cast Developer Console (leave empty for the standard player)",
            Prefs.appId(this),
        ) { id ->
            Prefs.setAppId(this, id)
            MaterialAlertDialogBuilder(this)
                .setTitle("Restart needed")
                .setMessage("Cast Hub will restart to use the new TV screen. Then tap the cast icon and pick your TV again.")
                .setPositiveButton("Restart") { _, _ ->
                    startActivity(Intent.makeRestartActivityTask(componentName))
                    Runtime.getRuntime().exit(0)
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun askText(title: String, hint: String, initial: String, onOk: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(initial)
            this.hint = hint
            setSelection(text.length)
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(field.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- web links ----------------

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        showLinkDialog(url)
    }

    private fun showLinkDialog(prefill: String?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_link, null)
        val urlField = view.findViewById<EditText>(R.id.linkUrl)
        val titleField = view.findViewById<EditText>(R.id.linkTitle)
        val liveBox = view.findViewById<CheckBox>(R.id.linkLive)
        prefill?.let { urlField.setText(it) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Cast a web link")
            .setView(view)
            .setPositiveButton("Cast") { _, _ ->
                val url = urlField.text.toString().trim()
                if (!url.startsWith("http")) toast("Paste a link starting with http")
                else castLink(url, titleField.text.toString().trim(), liveBox.isChecked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val TV_NAMESPACE = "urn:x-cast:com.shawn.casthub"
    }
}
