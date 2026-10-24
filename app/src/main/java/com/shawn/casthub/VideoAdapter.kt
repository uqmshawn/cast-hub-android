package com.shawn.casthub

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService

class VideoAdapter(
    private val context: Context,
    private val executor: ExecutorService,
    private val compatOf: (Video) -> CompatResult?,
    private val onNeedCheck: (Video) -> Unit,
    private val hasSubtitles: (Video) -> Boolean,
    private val onPlay: (Video) -> Unit,
    private val onSubtitles: (Video) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    private var items: List<Video> = emptyList()
    private val thumbs = LruCache<Long, Bitmap>(80)

    fun submit(list: List<Video>) {
        items = list
        notifyDataSetChanged()
    }

    fun refresh(id: Long) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) notifyItemChanged(i)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val v = items[position]
        h.title.text = v.title
        val bits = mutableListOf<String>()
        if (v.durationMs > 0) bits += formatDuration(v.durationMs)
        bits += formatSize(v.size)
        if (v.folder.isNotEmpty()) bits += v.folder
        h.meta.text = bits.joinToString(" · ")

        val c = compatOf(v)
        if (c == null) onNeedCheck(v)
        when (c?.compat) {
            Compat.READY -> {
                h.badge.text = "✓ Ready  ${c.note}"
                h.badge.setTextColor(ContextCompat.getColor(context, R.color.ok))
            }
            Compat.MAYBE_NOT -> {
                h.badge.text = "⚠ ${c.note}"
                h.badge.setTextColor(ContextCompat.getColor(context, R.color.warn))
            }
            else -> {
                h.badge.text = "Checking format…"
                h.badge.setTextColor(ContextCompat.getColor(context, R.color.muted))
            }
        }

        h.cc.alpha = if (hasSubtitles(v)) 1f else 0.35f
        h.cc.setOnClickListener { onSubtitles(v) }
        h.play.setOnClickListener { onPlay(v) }
        h.itemView.setOnClickListener { onPlay(v) }

        // thumbnail
        h.thumb.tag = v.id
        val cached = thumbs.get(v.id)
        if (cached != null) {
            h.thumb.setImageBitmap(cached)
        } else {
            h.thumb.setImageDrawable(null)
            if (Build.VERSION.SDK_INT >= 29) {
                executor.execute {
                    val bmp = try {
                        context.contentResolver.loadThumbnail(v.uri, Size(320, 180), null)
                    } catch (e: Exception) {
                        null
                    }
                    if (bmp != null) {
                        thumbs.put(v.id, bmp)
                        h.thumb.post { if (h.thumb.tag == v.id) h.thumb.setImageBitmap(bmp) }
                    }
                }
            }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
        val badge: TextView = view.findViewById(R.id.badge)
        val cc: ImageButton = view.findViewById(R.id.cc)
        val play: ImageButton = view.findViewById(R.id.play)
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s / 60) % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m ${s % 60}s"
    }

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1_000_000_000) String.format("%.1f GB", bytes / 1e9) else "${bytes / 1_000_000} MB"
}
