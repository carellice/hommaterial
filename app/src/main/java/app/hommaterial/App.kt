package app.hommaterial

import android.app.Application
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

/** A text of the app in the language of the phone, from anywhere in the code. */
fun str(@StringRes id: Int, vararg args: Any): String =
    if (args.isEmpty()) App.instance.getString(id) else App.instance.getString(id, *args)

/** Like [str], for texts that change with [count]. The count is also their first argument. */
fun plural(@PluralsRes id: Int, count: Int): String = App.instance.resources.getQuantityString(id, count, count)
