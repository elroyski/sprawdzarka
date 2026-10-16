package pl.sqn.sprawdzarka.ads

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import pl.sqn.sprawdzarka.R

/**
 * Rotujące reklamy książek. Strona jest układem [R.styleable.AdSlideshowView_pageLayout]
 * z widokami o id: adCover, adTitle, adAuthor oraz opcjonalnie adBackground (rozmyte tło)
 * i adDots (kropki postępu). Dwie strony leżą na sobie i zamieniają się przy każdej zmianie.
 */
class AdSlideshowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val intervalMs: Long
    private val pages: List<View>
    private var front = 0
    private var index = -1
    private var running = false
    private val density = resources.displayMetrics.density

    private val advance = object : Runnable {
        override fun run() {
            showNext(animated = true)
            postDelayed(this, intervalMs)
        }
    }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.AdSlideshowView)
        val layout = a.getResourceId(R.styleable.AdSlideshowView_pageLayout, 0)
        intervalMs = a.getInt(R.styleable.AdSlideshowView_intervalMs, 6000).toLong()
        a.recycle()
        val inflater = LayoutInflater.from(context)
        pages = List(2) { inflater.inflate(layout, this, false).also(::addView) }
        pages[1].visibility = INVISIBLE
        pages.forEach { page ->
            page.findViewById<ImageView>(R.id.adBackground)?.let { bg ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bg.setRenderEffect(RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP))
                }
            }
        }
    }

    /** Startuje rotację od kolejnej okładki. */
    fun start() {
        if (running) return
        running = true
        showNext(animated = index >= 0)
        postDelayed(advance, intervalMs)
    }

    fun stop() {
        running = false
        removeCallbacks(advance)
        pages.forEach { it.findViewById<View>(R.id.adCover).animate().cancel() }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun showNext(animated: Boolean) {
        index = (index + 1) % BOOK_ADS.size
        val incoming = pages[1 - front]
        val outgoing = pages[front]
        bind(incoming, BOOK_ADS[index])
        front = 1 - front

        val shift = 80 * density
        outgoing.animate().cancel()
        incoming.animate().cancel()
        incoming.visibility = VISIBLE
        incoming.bringToFront()
        if (animated) {
            outgoing.animate()
                .alpha(0f).translationX(-shift).setDuration(500)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { outgoing.visibility = INVISIBLE }
                .start()
            incoming.alpha = 0f
            incoming.translationX = shift
            incoming.animate()
                .alpha(1f).translationX(0f).setDuration(700).setStartDelay(150)
                .setInterpolator(DecelerateInterpolator())
                .start()
            animateCoverIn(incoming.findViewById(R.id.adCover))
        } else {
            outgoing.visibility = INVISIBLE
            incoming.alpha = 1f
            incoming.translationX = 0f
            animateCoverIn(incoming.findViewById(R.id.adCover))
        }
    }

    /** Okładka "wskakuje" lekko obrócona, potem powoli się przybliża (efekt Kena Burnsa). */
    private fun animateCoverIn(cover: View) {
        cover.animate().cancel()
        cover.scaleX = 0.85f
        cover.scaleY = 0.85f
        cover.rotation = 4f
        cover.animate()
            .scaleX(1f).scaleY(1f).rotation(0f)
            .setDuration(800).setStartDelay(150)
            .setInterpolator(OvershootInterpolator(1.2f))
            .withEndAction {
                cover.animate()
                    .scaleX(1.06f).scaleY(1.06f).rotation(-1f)
                    .setDuration(intervalMs - 1000).setStartDelay(0)
                    .setInterpolator(LinearInterpolator())
                    .start()
            }
            .start()
    }

    private fun bind(page: View, ad: BookAd) {
        page.findViewById<ImageView>(R.id.adCover).setImageResource(ad.cover)
        page.findViewById<ImageView>(R.id.adBackground)?.setImageResource(ad.cover)
        page.findViewById<TextView>(R.id.adTitle).text = ad.title
        page.findViewById<TextView>(R.id.adAuthor).text = ad.author
        page.findViewById<LinearLayout>(R.id.adDots)?.let(::bindDots)
    }

    private fun bindDots(dots: LinearLayout) {
        if (dots.childCount != BOOK_ADS.size) {
            dots.removeAllViews()
            val size = (8 * density).toInt()
            repeat(BOOK_ADS.size) {
                dots.addView(View(context).apply {
                    setBackgroundResource(R.drawable.dot)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginStart = size / 2
                        marginEnd = size / 2
                    }
                })
            }
        }
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).isSelected = i == index
        }
    }
}
