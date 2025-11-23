package com.gocavgo.validator

import android.app.Application
import com.gocavgo.validator.util.Logging
import com.gocavgo.validator.BuildConfig
import io.sentry.android.core.SentryAndroid
import io.sentry.SentryOptions

class ValidatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Sentry in both debug and release builds when DSN is available
        val sentryDsn = BuildConfig.SENTRY_DSN
        if (sentryDsn.isNotEmpty()) {
            SentryAndroid.init(this) { options: SentryOptions ->
                options.dsn = sentryDsn
                // Keep performance disabled unless needed
                options.tracesSampleRate = 0.0
            }
            Logging.addSink(Logging.SentrySink())
        }
    }
}


