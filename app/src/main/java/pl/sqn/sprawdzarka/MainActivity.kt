package pl.sqn.sprawdzarka

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.sqn.sprawdzarka.ads.AdSlideshowView
import pl.sqn.sprawdzarka.data.AssetPriceRepository
import pl.sqn.sprawdzarka.data.PriceRepository
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val repository: PriceRepository by lazy { AssetPriceRepository(this) }
    private val priceFormat = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale("pl", "PL")))

    private lateinit var previewView: PreviewView
    private lateinit var nameView: TextView
    private lateinit var priceView: TextView
    private lateinit var codeView: TextView
    private lateinit var authorView: TextView
    private lateinit var accentLine: View
    private lateinit var idleState: View
    private lateinit var resultState: View
    private lateinit var scanFrame: ScanFrameView
    private lateinit var adCard: AdSlideshowView
    private lateinit var screensaver: View
    private lateinit var saverSlideshow: AdSlideshowView
    private lateinit var cameraContainer: ViewGroup
    private lateinit var cameraHome: ViewGroup
    private var cameraHomeIndex = 0
    private lateinit var saverCameraSlot: ViewGroup
    private lateinit var priceBox: View
    private lateinit var promoBanner: View
    private lateinit var doomEgg: View
    private var doomArmed = false
    private val doomTrigger = Runnable { showDoomEgg() }
    private var hintPulse: ObjectAnimator? = null
    private val scanLogger by lazy { ScanLogger(this) }
    private val startScreensaver = Runnable { showScreensaver() }
    /** Dotyk, który zamknął wygaszacz – reszta tego gestu nie trafia do ekranu pod spodem. */
    private var swallowGesture = false
    private var saverOn = false

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analyzer: BarcodeAnalyzer
    private var resetJob: Job? = null
    private val beeper = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private var startupPlayer: MediaPlayer? = null

    private var lastCode: String? = null
    private var lastCodeAt = 0L

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, R.string.no_permission, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        nameView = findViewById(R.id.name)
        priceView = findViewById(R.id.price)
        codeView = findViewById(R.id.code)
        authorView = findViewById(R.id.author)
        hideSystemBars()
        accentLine = findViewById(R.id.accentLine)
        idleState = findViewById(R.id.idleState)
        resultState = findViewById(R.id.resultState)
        scanFrame = findViewById(R.id.scanFrame)
        adCard = findViewById(R.id.adCard)
        screensaver = findViewById(R.id.screensaver)
        saverSlideshow = findViewById(R.id.saverSlideshow)
        saverCameraSlot = findViewById(R.id.saverCameraSlot)
        priceBox = findViewById(R.id.priceBox)
        promoBanner = findViewById(R.id.promoBanner)
        doomEgg = findViewById(R.id.doomEgg)
        doomEgg.setOnClickListener { hideDoomEgg() }
        setupPromoHold(promoBanner)
        setupHistoryHold(priceBox)
        setupLogoTaps(findViewById(R.id.logo))
        playStartupSound()

        // Zaokrąglone rogi podglądu – SurfaceView nie respektuje przycięcia, stąd TextureView.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        cameraContainer = findViewById<ViewGroup>(R.id.cameraContainer).apply {
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        cameraHome = cameraContainer.parent as ViewGroup
        cameraHomeIndex = cameraHome.indexOfChild(cameraContainer)

        cameraExecutor = Executors.newSingleThreadExecutor()
        analyzer = BarcodeAnalyzer { code -> runOnUiThread { onBarcode(code) } }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestPermission.launch(Manifest.permission.CAMERA)

        // Do testów: adb shell am start -n pl.sqn.sprawdzarka/.MainActivity --es ean <kod>
        intent.getStringExtra(EXTRA_EAN)?.let(::onBarcode)
    }

    /** Krótki dźwięk powitalny – przy każdym uruchomieniu aplikacji. */
    private fun playStartupSound() {
        startupPlayer = MediaPlayer.create(this, R.raw.startup_sound)?.apply {
            setOnCompletionListener { it.release(); startupPlayer = null }
            start()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            // Przedni aparat zwykle nie ma autofokusu – kod trzeba trzymać dalej,
            // więc analizujemy w najwyższej dostępnej rozdzielczości.
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(4000, 3000),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }

            // Przedni aparat; awaryjnie tylny, gdy urządzenie nie ma przedniego.
            val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
            Log.i(TAG, "Analiza w rozdzielczości ${analysis.resolutionInfo?.resolution}")
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        adCard.start()
        if (saverOn) saverSlideshow.start()
        restartIdleTimer()
        enableKioskLock()
    }

    /**
     * Przypina aplikację na ekranie (Android Screen Pinning), żeby nie dało się
     * z niej wyjść przyciskami home/wstecz/przegląd. Wymaga, żeby na tablecie był
     * ustawiony PIN/wzór/hasło blokady ekranu – wtedy odpięcie (przytrzymanie
     * wstecz+przegląd) poprosi o ten kod.
     */
    private fun enableKioskLock() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        if (activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
                startLockTask()
            } catch (e: Exception) {
                Log.w(TAG, "Nie udało się włączyć trybu kiosku (screen pinning)", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        adCard.stop()
        saverSlideshow.stop()
        screensaver.removeCallbacks(startScreensaver)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowGesture = saverOn
            if (swallowGesture) hideScreensaver()
        }
        restartIdleTimer()
        checkDoomGesture(ev)
        return if (swallowGesture) true else super.dispatchTouchEvent(ev)
    }

    /** Ukryty żart: trzymanie jednocześnie dwoma palcami "Sprawdź cenę" i "3 za 2". */
    private fun checkDoomGesture(ev: MotionEvent) {
        if (doomEgg.visibility == View.VISIBLE) return
        val onPrice = (0 until ev.pointerCount).any { isPointerOn(priceBox, ev.getX(it), ev.getY(it)) }
        val onPromo = (0 until ev.pointerCount).any { isPointerOn(promoBanner, ev.getX(it), ev.getY(it)) }
        val bothHeld = onPrice && onPromo && ev.pointerCount >= 2
        if (bothHeld && !doomArmed) {
            doomArmed = true
            priceBox.postDelayed(doomTrigger, DOOM_HOLD_MS)
        } else if (!bothHeld && doomArmed) {
            doomArmed = false
            priceBox.removeCallbacks(doomTrigger)
        }
    }

    private fun isPointerOn(view: View, x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + view.width && y >= loc[1] && y <= loc[1] + view.height
    }

    private fun showDoomEgg() {
        doomArmed = false
        beeper.startTone(ToneGenerator.TONE_SUP_ERROR, 400)
        doomEgg.alpha = 0f
        doomEgg.visibility = View.VISIBLE
        doomEgg.animate().alpha(1f).setDuration(200).start()
        doomEgg.postDelayed({ hideDoomEgg() }, DOOM_DISPLAY_MS)
    }

    private fun hideDoomEgg() {
        if (doomEgg.visibility != View.VISIBLE) return
        doomEgg.animate().alpha(0f).setDuration(250)
            .withEndAction { doomEgg.visibility = View.GONE }
            .start()
    }

    /** Ukryte wejście w tryb reklam: przytrzymanie paska promocji przez kilka sekund. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupPromoHold(banner: View) {
        val trigger = Runnable {
            banner.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            showScreensaver()
        }
        banner.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    banner.animate().scaleX(0.97f).scaleY(0.97f).setDuration(PROMO_HOLD_MS).start()
                    banner.postDelayed(trigger, PROMO_HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    banner.removeCallbacks(trigger)
                    banner.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }
            }
            true
        }
    }

    /** Przytrzymanie boksu z ceną: podgląd historii i statystyk skanowań. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupHistoryHold(box: View) {
        val trigger = Runnable {
            box.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            showScanStats()
        }
        box.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    box.animate().scaleX(0.98f).scaleY(0.98f).setDuration(HISTORY_HOLD_MS).start()
                    box.postDelayed(trigger, HISTORY_HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    box.removeCallbacks(trigger)
                    box.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }
            }
            true
        }
    }

    private fun showScanStats() {
        lifecycleScope.launch {
            val stats = scanLogger.readStats()
            val body = buildString {
                append("Łącznie zeskanowano: ${stats.total} kod")
                append(if (stats.total == 1) "\n" else "ów\n")
                if (stats.topLabel != null) {
                    append("\nNajczęściej: ${stats.topLabel} (${stats.topCount}x)\n")
                }
                if (stats.firstTime != null && stats.lastTime != null) {
                    append("\nOkres użytkowania: ${formatTimestamp(stats.firstTime)} – ${formatTimestamp(stats.lastTime)}\n")
                }
                append("\nOstatnie skany:\n")
                if (stats.recent.isEmpty()) {
                    append("(brak)")
                } else {
                    stats.recent.forEach { r ->
                        val label = if (r.found) r.title else "${r.code} (nie znaleziono)"
                        append("${r.time.substringAfter(' ')}  $label\n")
                    }
                }
            }
            val textView = TextView(this@MainActivity).apply {
                text = body
                setPadding(48, 32, 48, 32)
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Statystyki skanowań")
                .setView(ScrollView(this@MainActivity).apply {
                    addView(textView)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(420))
                })
                .setPositiveButton("Zamknij", null)
                .setNegativeButton("Wyczyść statystyki") { _, _ -> confirmClearScanStats() }
                .show()
        }
    }

    private fun confirmClearScanStats() {
        AlertDialog.Builder(this)
            .setTitle("Wyczyścić statystyki?")
            .setMessage("Cała historia skanów zostanie trwale usunięta. Tej operacji nie można cofnąć.")
            .setPositiveButton("Usuń") { _, _ ->
                lifecycleScope.launch {
                    scanLogger.clearAll()
                    Toast.makeText(this@MainActivity, "Statystyki wyczyszczone", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** "2026-10-17 16:18:30" -> "17.10.2026 16:18". */
    private fun formatTimestamp(raw: String): String {
        val parts = raw.split(' ')
        if (parts.size != 2) return raw
        val (datePart, timePart) = parts
        val dateBits = datePart.split('-')
        if (dateBits.size != 3) return raw
        val (year, month, day) = dateBits
        val hhmm = timePart.substringBeforeLast(':')
        return "$day.$month.$year $hhmm"
    }

    /** Ukryta aktualizacja cen: 5 szybkich stuknięć w logo. */
    private fun setupLogoTaps(logo: View) {
        var taps = 0
        var lastTapAt = 0L
        var updating = false
        logo.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            taps = if (now - lastTapAt < LOGO_TAP_GAP_MS) taps + 1 else 1
            lastTapAt = now
            if (taps < LOGO_TAPS || updating) return@setOnClickListener
            taps = 0
            updating = true
            Toast.makeText(this, R.string.prices_updating, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val message = try {
                    getString(R.string.prices_updated, repository.updatePrices())
                } catch (e: Exception) {
                    Log.w(TAG, "Aktualizacja cen nieudana", e)
                    getString(R.string.prices_failed, e.message ?: e.javaClass.simpleName)
                }
                updating = false
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restartIdleTimer() {
        screensaver.removeCallbacks(startScreensaver)
        screensaver.postDelayed(startScreensaver, IDLE_TIMEOUT_MS)
    }

    private fun showScreensaver() {
        screensaver.removeCallbacks(startScreensaver)
        if (saverOn) return
        saverOn = true
        adCard.stop()
        // Przenosimy żywy podgląd kamery do mini-okienka, żeby było widać gdzie skanować.
        cameraHome.removeView(cameraContainer)
        saverCameraSlot.addView(
            cameraContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        screensaver.alpha = 0f
        screensaver.visibility = View.VISIBLE
        screensaver.animate().cancel()
        screensaver.animate().alpha(1f).setDuration(600).start()
        saverSlideshow.start()
        hintPulse = ObjectAnimator.ofFloat(findViewById(R.id.saverHint), View.ALPHA, 1f, 0.35f).apply {
            duration = 1600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun hideScreensaver() {
        if (!saverOn) return
        saverOn = false
        saverSlideshow.stop()
        // Podgląd kamery wraca na swoje stałe miejsce w środkowej kolumnie.
        saverCameraSlot.removeView(cameraContainer)
        cameraHome.addView(
            cameraContainer,
            cameraHomeIndex,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        hintPulse?.cancel()
        screensaver.animate().cancel()
        screensaver.animate().alpha(0f).setDuration(250)
            .withEndAction { screensaver.visibility = View.GONE }
            .start()
        adCard.start()
        restartIdleTimer()
    }

    private fun onBarcode(code: String) {
        // Ignoruj ten sam kod skanowany ciągle, dopóki leży przed kamerą.
        val now = SystemClock.elapsedRealtime()
        if (code == lastCode && now - lastCodeAt < SAME_CODE_PAUSE_MS) return
        lastCode = code
        lastCodeAt = now
        // Skan budzi ekran z wygaszacza i od razu pokazuje cenę.
        hideScreensaver()
        restartIdleTimer()

        resetJob?.cancel()
        resetJob = lifecycleScope.launch {
            val product = repository.findByBarcode(code)
            launch { scanLogger.log(code, product) }
            // Krótki "pik" po odczycie; inny dźwięk, gdy produktu nie ma w bazie.
            if (product != null) beeper.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            else beeper.startTone(ToneGenerator.TONE_PROP_NACK, 300)
            codeView.text = code
            if (product != null) {
                nameView.text = product.title
                nameView.setTextColor(getColor(R.color.text_primary))
                authorView.text = product.author
                authorView.visibility = if (product.author.isEmpty()) View.GONE else View.VISIBLE
                priceView.text = formatPrice(product.grossPrice)
                priceView.visibility = View.VISIBLE
                accentLine.visibility = View.VISIBLE
            } else {
                nameView.setText(R.string.not_found)
                nameView.setTextColor(getColor(R.color.text_secondary))
                priceView.visibility = View.GONE
                accentLine.visibility = View.GONE
                authorView.setText(R.string.not_found_subtitle)
                authorView.visibility = View.VISIBLE
            }
            showResult()
            scanFrame.highlighted = true
            delay(HIGHLIGHT_TIME_MS)
            scanFrame.highlighted = false
            delay(DISPLAY_TIME_MS - HIGHLIGHT_TIME_MS)
            showIdle()
        }
    }

    /** "24,99 zł" z mniejszym dopiskiem waluty. */
    private fun formatPrice(price: BigDecimal): CharSequence {
        val amount = priceFormat.format(price)
        return SpannableString("$amount zł").apply {
            setSpan(RelativeSizeSpan(0.4f), amount.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Tryb kiosku: bez paska stanu i nawigacji (pokazują się po przesunięciu od krawędzi). */
    private fun hideSystemBars() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showResult() {
        crossfade(show = resultState, hide = idleState)
    }

    private fun showIdle() {
        crossfade(show = idleState, hide = resultState)
    }

    private fun crossfade(show: View, hide: View) {
        hide.animate().cancel()
        hide.visibility = View.INVISIBLE
        show.animate().cancel()
        show.alpha = 0f
        show.translationY = 24 * resources.displayMetrics.density
        show.visibility = View.VISIBLE
        show.animate().alpha(1f).translationY(0f).setDuration(250).start()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        analyzer.close()
        beeper.release()
        startupPlayer?.release()
    }

    companion object {
        private const val TAG = "Sprawdzarka"
        private const val EXTRA_EAN = "ean"
        private const val DISPLAY_TIME_MS = 6_000L
        private const val HIGHLIGHT_TIME_MS = 600L
        private const val SAME_CODE_PAUSE_MS = 3_000L
        private const val IDLE_TIMEOUT_MS = 5 * 60_000L
        private const val PROMO_HOLD_MS = 3_000L
        private const val HISTORY_HOLD_MS = 1_200L
        private const val DOOM_HOLD_MS = 1_200L
        private const val DOOM_DISPLAY_MS = 3_000L
        private const val LOGO_TAPS = 5
        private const val LOGO_TAP_GAP_MS = 600L
    }
}
