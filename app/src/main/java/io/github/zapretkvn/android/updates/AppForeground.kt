package io.github.zapretkvn.android.updates

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/** Считает видимые Activity, чтобы фоновые решения updater знали, смотрит ли пользователь в приложение. */
class AppForeground(application: Application) {
    private val started = AtomicInteger()

    val isForeground: Boolean get() = started.get() > 0

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                started.updateAndGet { (it - 1).coerceAtLeast(0) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
