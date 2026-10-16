package pl.sqn.sprawdzarka

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analyzer: BarcodeAnalyzer
    private var resetJob: Job? = null
    private val beeper = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

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

        // Zaokrąglone rogi podglądu – SurfaceView nie respektuje przycięcia, stąd TextureView.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        findViewById<View>(R.id.cameraContainer).apply {
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        analyzer = BarcodeAnalyzer { code -> runOnUiThread { onBarcode(code) } }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestPermission.launch(Manifest.permission.CAMERA)

        // Do testów: adb shell am start -n pl.sqn.sprawdzarka/.MainActivity --es ean <kod>
        intent.getStringExtra(EXTRA_EAN)?.let(::onBarcode)
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

    private fun onBarcode(code: String) {
        // Ignoruj ten sam kod skanowany ciągle, dopóki leży przed kamerą.
        val now = SystemClock.elapsedRealtime()
        if (code == lastCode && now - lastCodeAt < SAME_CODE_PAUSE_MS) return
        lastCode = code
        lastCodeAt = now

        resetJob?.cancel()
        resetJob = lifecycleScope.launch {
            val product = repository.findByBarcode(code)
            // Krótki "pik" po odczycie; inny dźwięk, gdy produktu nie ma w bazie.
            if (product != null) beeper.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            else beeper.startTone(ToneGenerator.TONE_PROP_NACK, 300)
            codeView.text = code
            if (product != null) {
                nameView.text = product.title
                nameView.setTextColor(getColor(R.color.text_primary))
                authorView.text = product.author
                authorView.visibility = View.VISIBLE
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
    }

    companion object {
        private const val TAG = "Sprawdzarka"
        private const val EXTRA_EAN = "ean"
        private const val DISPLAY_TIME_MS = 6_000L
        private const val HIGHLIGHT_TIME_MS = 600L
        private const val SAME_CODE_PAUSE_MS = 3_000L
    }
}
