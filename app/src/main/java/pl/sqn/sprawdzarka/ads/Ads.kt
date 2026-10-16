package pl.sqn.sprawdzarka.ads

import androidx.annotation.DrawableRes
import pl.sqn.sprawdzarka.R

data class BookAd(
    @DrawableRes val cover: Int,
    val title: String,
    val author: String,
)

/** Reklamowane nowości – okładki z wsqn.pl/media (wersje 1500 px). */
val BOOK_ADS = listOf(
    BookAd(R.drawable.ad_chimera, "Chimera", "Jakub Małecki"),
    BookAd(R.drawable.ad_renegaci, "Renegaci", "Marissa Meyer"),
    BookAd(R.drawable.ad_wiedzma, "Wiedźma z piekła", "Aneta Jadowska"),
    BookAd(R.drawable.ad_siostra, "Zapomniana siostra", "Marcin Mortka"),
    BookAd(R.drawable.ad_jagoda, "Wilcza Jagoda", "Magdalena Kubasiewicz"),
    BookAd(R.drawable.ad_mentira, "Mentira", "Juan Gómez-Jurado"),
)
