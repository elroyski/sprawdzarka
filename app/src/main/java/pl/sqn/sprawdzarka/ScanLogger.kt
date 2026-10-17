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

    /** Kasuje całą zapisaną historię skanów (wszystkie pliki miesięczne). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.startsWith("skany-") && f.name.endsWith(".csv") }
            ?.forEach { it.delete() }
        Unit
    }

    /** Statystyki ze wszystkich zapisanych miesięcy – do podglądu w aplikacji. */
    suspend fun readStats(recentLimit: Int = 25): ScanStats = withContext(Dispatchers.IO) {
        val files = dir.listFiles { f -> f.name.startsWith("skany-") && f.name.endsWith(".csv") }
            ?.sortedBy { it.name }
            .orEmpty()

        val records = mutableListOf<ScanRecord>()
        for (file in files) {
            file.useLines { lines ->
                lines.drop(1).forEach { line ->
                    val parts = line.split(';')
                    if (parts.size >= 4) {
                        records += ScanRecord(
                            time = parts[0],
                            code = parts[1],
                            found = parts[2] == "1",
                            title = parts[3],
                        )
                    }
                }
            }
        }

        val topEntry = records
            .groupingBy { record -> record.title.ifEmpty { record.code } }
            .eachCount()
            .maxByOrNull { it.value }

        ScanStats(
            total = records.size,
            topLabel = topEntry?.key,
            topCount = topEntry?.value ?: 0,
            firstTime = records.firstOrNull()?.time,
            lastTime = records.lastOrNull()?.time,
            recent = records.takeLast(recentLimit).asReversed(),
        )
    }
}

data class ScanRecord(val time: String, val code: String, val found: Boolean, val title: String)

data class ScanStats(
    val total: Int,
    val topLabel: String?,
    val topCount: Int,
    val firstTime: String?,
    val lastTime: String?,
    val recent: List<ScanRecord>,
)
