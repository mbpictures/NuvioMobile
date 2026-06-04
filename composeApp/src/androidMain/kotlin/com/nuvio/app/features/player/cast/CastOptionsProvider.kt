package com.nuvio.app.features.player.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Supplies Cast framework configuration. Registered via the
 * `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME` manifest meta-data and
 * discovered reflectively by Google Play services when [com.google.android.gms.cast.framework.CastContext]
 * is first initialized.
 *
 * Uses the default styled media receiver (no custom receiver registration required). This plays
 * streams whose authorization is encoded in the URL (debrid/tokenized links); streams that strictly
 * require request headers are not supported by the default receiver — see [CastMediaRequest].
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}
