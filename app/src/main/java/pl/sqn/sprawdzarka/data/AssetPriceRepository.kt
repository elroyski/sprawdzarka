package pl.sqn.sprawdzarka.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * Tymczasowe źródło: lista tytułów w assets/produkty.tsv
 * (kolumny: kod, tytuł, autor, cena). Do zastąpienia repozytorium Subiekta.
 */
class AssetPriceRepository(private val context: Context) : PriceRepository {

    private var products: Map<String, Product>? = null

    override suspend fun findByBarcode(barcode: String): Product? =
        loadProducts()[barcode.trim()]

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
    }
}
