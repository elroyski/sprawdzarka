package pl.sqn.sprawdzarka

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.sqn.sprawdzarka.data.Product
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dopisuje każdy skan do pliku CSV (jeden plik na miesiąc).
 * Pliki: Android/data/pl.sqn.sprawdzarka/files/skany/skany-RRRR-MM.csv
 * Pobranie: adb pull /sdcard/Android/data/pl.sqn.sprawdzarka/files/skany
 */
class ScanLogger(context: Context) {

    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "skany")
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)

    suspend fun log(code: String, product: Product?) = withContext(Dispatchers.IO) {
        val now = Date()
        val line = listOf(
            timeFormat.format(now),
            code,
            if (product != null) "1" else "0",
            product?.title.orEmpty(),
            product?.author.orEmpty(),
            product?.grossPrice?.toPlainString().orEmpty(),
        ).joinToString(";") { it.replace(';', ',').replace('\n', ' ') }
        try {
            dir.mkdirs()
            val file = File(dir, "skany-${monthFormat.format(now)}.csv")
            if (!file.exists()) file.writeText("czas;kod;znaleziono;tytul;autor;cena\n")
            file.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w("ScanLogger", "Nie udało się zapisać skanu", e)
        }
        Log.i("ScanLogger", line)
    }
}
