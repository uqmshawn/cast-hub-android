package com.shawn.casthub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var castContext: CastContext
    private lateinit var adapter: VideoAdapter
    private lateinit var emptyView: TextView
    private lateinit var search: EditText

    private var allVideos: List<Video> = emptyList()
    private val compat = HashMap<Long, CompatResult>()
    private val progress = HashMap<Long, Int>()
    private lateinit var converter: Converter
    private val bg = Executors.newFixedThreadPool(2)

    /** Something the user asked to play before a TV was connected; played once they pick one. */
    private var pending: (() -> Unit)? = null
    private var subtitlesFor: Long = -1

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

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            pending?.invoke()
            pending = null
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            pending?.invoke()
            pending = null
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            StreamService.stop(this@MainActivity)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        @Suppress("DEPRECATION")
        castContext = CastContext.getSharedInstance(this)
        converter = Converter(this)

        emptyView = findViewById(R.id.empty)
        search = findViewById(R.id.search)

        adapter = VideoAdapter(
            context = this,
            executor = bg,
            compatOf = { v -> compat[v.id] },
            onNeedCheck = { v -> checkCompat(v) },
            hasSubtitles = { v -> Subtitles.get(this, v.id) != null },
            progressOf = { v -> progress[v.id] },
            isConverted = { v -> Converted.exists(this, v.id) },
            onPlay = { v -> playVideo(v) },
            onSubtitles = { v -> subtitlesClicked(v) },
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        search.doAfterTextChanged { applyFilter() }
        findViewById<MaterialButton>(R.id.btnLink).setOnClickListener { showLinkDialog(null) }

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
    }

    override fun onPause() {
        castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
        super.onPause()
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
        if (item.itemId == R.id.action_tv) {
            showTvMenu()
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

    // ---------------- videos ----------------

    private fun videoPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasVideoPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsAndLoad() {
        if (hasVideoPermission()) loadVideos() else permissionLauncher.launch(videoPermissions())
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
            toast("Still converting — it will play when it's done")
            return
        }
        if (Converted.exists(this, v.id)) {
            castVideo(v)
            return
        }
        val r = compat[v.id]
        if (r?.compat == Compat.MAYBE_NOT) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Convert for your Chromecast?")
                .setMessage(
                    "${r.note}.\n\nYour 1st-gen Chromecast can't play this format. Cast Hub will make a " +
                        "compatible copy on this phone (usually quicker than the video's length), then play it on the TV."
                )
                .setPositiveButton("Convert & play") { _, _ -> convertThenPlay(v, r) }
                .setNeutralButton("Try anyway") { _, _ -> castVideo(v) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            castVideo(v)
        }
    }

    private fun convertThenPlay(v: Video, r: CompatResult) {
        if (converter.isBusy) {
            toast("Another video is converting — wait for it to finish")
            return
        }
        progress[v.id] = 0
        adapter.refresh(v.id)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toast("Converting… keep Cast Hub open")
        converter.convert(
            v, r.width, r.height,
            onProgress = { p ->
                progress[v.id] = p
                adapter.refresh(v.id)
            },
            onDone = { ok, err ->
                progress.remove(v.id)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                adapter.refresh(v.id)
                if (ok) {
                    toast("Converted — sending to the TV")
                    castVideo(v)
                } else {
                    toast("Conversion failed: $err")
                }
            },
        )
    }

    override fun onDestroy() {
        if (isFinishing) converter.cancel()
        super.onDestroy()
    }

    private fun castVideo(v: Video) {
        val session = connectedSession { castVideo(v) } ?: return
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
            .setContentType(if (Converted.exists(this, v.id)) "video/mp4" else v.mime ?: "video/mp4")
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
        load(session, request.build())
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
        load(session, MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
    }

    private fun load(session: CastSession, request: MediaLoadRequestData) {
        val client = session.remoteMediaClient ?: run {
            toast("TV not ready yet, try again")
            return
        }
        client.load(request).setResultCallback { result ->
            if (result.status.isSuccess) {
                startActivity(Intent(this, ExpandedControlsActivity::class.java))
            } else {
                toast("The TV couldn't play this (code ${result.status.statusCode})")
            }
        }
    }

    // ---------------- TV home screen (custom receiver) ----------------

    private fun showTvMenu() {
        if (Prefs.appId(this).isBlank()) {
            askAppId()
            return
        }
        val items = arrayOf(
            "Show home screen on TV",
            "Send a message to the TV",
            "Show a website on the TV",
            "Weather location",
            "TV name",
            "Clock: 12 / 24 hour",
            "Receiver App ID",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("TV home screen")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> sendTv(org.json.JSONObject().put("type", "home"))
                    1 -> askText("Message for the TV", "e.g. Dinner's ready!", "") { text ->
                        sendTv(org.json.JSONObject().put("type", "message").put("text", text).put("seconds", 20))
                    }
                    2 -> askText("Show a website", "https://…", "https://") { url ->
                        sendTv(org.json.JSONObject().put("type", "web").put("url", url))
                    }
                    3 -> askText("Weather location", "Suburb or city", Prefs.get(this, "place", "Brisbane")) { place ->
                        Prefs.put(this, "place", place)
                        sendTv(org.json.JSONObject().put("type", "config").put("place", place))
                    }
                    4 -> askText("TV name", "Shown on the home screen", Prefs.get(this, "tvName", "Lounge TV")) { name ->
                        Prefs.put(this, "tvName", name)
                        sendTv(org.json.JSONObject().put("type", "config").put("name", name))
                    }
                    5 -> {
                        val h24 = Prefs.get(this, "h24", "false") != "true"
                        Prefs.put(this, "h24", h24.toString())
                        sendTv(org.json.JSONObject().put("type", "config").put("h24", h24))
                    }
                    6 -> askAppId()
                }
            }
            .show()
    }

    private fun askAppId() {
        askText(
            "Receiver App ID",
            "8-character ID from the Cast Developer Console (blank = standard player)",
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

    private fun sendTv(json: org.json.JSONObject) {
        val session = connectedSession { sendTv(json) } ?: return
        try {
            session.sendMessage(TV_NAMESPACE, json.toString()).setResultCallback { status ->
                if (!status.isSuccess) toast("The TV didn't respond — is the Cast Hub home screen running?")
            }
        } catch (e: Exception) {
            toast("Couldn't reach the TV: ${e.message}")
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
