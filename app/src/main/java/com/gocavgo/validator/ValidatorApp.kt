package com.gocavgo.validator

import android.app.Application
import com.gocavgo.validator.util.Logging
import com.gocavgo.validator.BuildConfig
import io.sentry.android.core.SentryAndroid
import io.sentry.SentryOptions

class ValidatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // In debug builds, rely on Logcat sink fallback only.
        // In release/production, add Crashlytics sink.
        if (!BuildConfig.DEBUG) {
            SentryAndroid.init(this) { options: SentryOptions ->
                options.dsn = BuildConfig.SENTRY_DSN
                // Keep performance disabled unless needed
                options.tracesSampleRate = 0.0
            }
            Logging.addSink(Logging.SentrySink())
        }
    }
}


