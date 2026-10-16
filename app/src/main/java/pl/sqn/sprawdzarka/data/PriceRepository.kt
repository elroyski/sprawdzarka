package pl.sqn.sprawdzarka.data

import java.math.BigDecimal

data class Product(val title: String, val author: String, val grossPrice: BigDecimal)

/** Źródło cen. Docelowo implementacja pobierająca dane z bazy Subiekta. */
interface PriceRepository {
    /** Zwraca produkt dla kodu EAN albo null, gdy nie istnieje. */
    suspend fun findByBarcode(barcode: String): Product?

    /** Pobiera aktualne ceny; zwraca liczbę wczytanych pozycji. Rzuca wyjątek przy błędzie. */
    suspend fun updatePrices(): Int
}
