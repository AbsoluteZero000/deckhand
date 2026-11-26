package io.github.absolutezero000.deckhand

import android.app.Application
import io.github.absolutezero000.deckhand.data.CardWriter
import io.github.absolutezero000.deckhand.data.DeckFetcher
import io.github.absolutezero000.deckhand.data.DeckhandDb
import io.github.absolutezero000.deckhand.data.Repository
import io.github.absolutezero000.deckhand.data.Settings
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        repository = Repository(this, DeckhandDb.open(this).dao(), Settings(this), DeckFetcher(http), CardWriter(http))
    }
}
