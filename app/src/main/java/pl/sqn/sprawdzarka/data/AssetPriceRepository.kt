package pl.sqn.sprawdzarka.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tymczasowe źródło: lista tytułów w assets/produkty.tsv
 * (kolumny: kod, tytuł, autor, cena). Do zastąpienia repozytorium Subiekta.
 *
 * Ceny można nadpisać plikiem [PRICES_URL] (wiersze "ean;cena"). Ostatni poprawnie
 * pobrany plik leży w pamięci aplikacji i działa także po restarcie, bez internetu.
 */
class AssetPriceRepository(private val context: Context) : PriceRepository {

    private var products: Map<String, Product>? = null
    private var prices: Map<String, BigDecimal>? = null
    private val pricesFile get() = File(context.filesDir, PRICES_FILE)

    override suspend fun findByBarcode(barcode: String): Product? {
        val code = barcode.trim()
        val product = loadProducts()[code]
        val price = loadPrices()[code] ?: return product
        return product?.copy(grossPrice = price) ?: Product("", "", price)
    }

    override suspend fun updatePrices(): Int = withContext(Dispatchers.IO) {
        val text = download(PRICES_URL)
        val parsed = parsePrices(text)
        if (parsed.isEmpty()) throw IOException("Plik nie zawiera żadnych cen")
        // Zapis przez plik tymczasowy, żeby przerwany zapis nie zepsuł poprzednich cen.
        val tmp = File(context.filesDir, "$PRICES_FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(pricesFile)) throw IOException("Nie udało się zapisać cen")
        prices = parsed
        parsed.size
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.useCaches = false
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Serwer zwrócił kod ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun loadPrices(): Map<String, BigDecimal> =
        prices ?: withContext(Dispatchers.IO) {
            pricesFile.takeIf { it.exists() }?.let { parsePrices(it.readText()) }.orEmpty()
        }.also { prices = it }

    private suspend fun loadProducts(): Map<String, Product> =
        products ?: withContext(Dispatchers.IO) {
            context.assets.open(FILE).bufferedReader().useLines { lines ->
                lines.drop(1)
                    .map { it.split('\t') }
                    .filter { it.size >= 4 }
                    .associate { (code, title, author, price) ->
                        code.trim() to Product(title.trim(), author.trim(), BigDecimal(price.trim()))
                    }
            }
        }.also { products = it }

    private companion object {
        const val FILE = "produkty.tsv"
        const val PRICES_FILE = "targi.csv"
        const val PRICES_URL = "https://wsqn.pl/targi.csv"

        /** "ean;cena" – pomija nagłówek i błędne wiersze; cena z przecinkiem lub kropką. */
        fun parsePrices(text: String): Map<String, BigDecimal> =
            text.removePrefix("﻿").lineSequence()
                .mapNotNull { line ->
                    val parts = line.split(';').map { it.trim().trim('"') }
                    if (parts.size < 2) return@mapNotNull null
                    val code = parts[0]
                    val price = parts[1].replace(" ", "").replace(',', '.').toBigDecimalOrNull()
                    if (code.isEmpty() || !code.all(Char::isDigit) || price == null || price.signum() <= 0) null
                    else code to price
                }
                .toMap()
    }
}
