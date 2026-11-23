package uy.edu.utec.pic1

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import uy.edu.utec.pic1.data.AppDatabase
import uy.edu.utec.pic1.link.BleChamberLink
import uy.edu.utec.pic1.link.FakeChamberLink
import uy.edu.utec.pic1.link.LinkMode
import uy.edu.utec.pic1.link.SwitchLink
import uy.edu.utec.pic1.session.ChamberSession

/** Contenedor de dependencias de la app. El enlace es el simulador o la cámara real por BLE, a elección. */
class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "utecorroe.db").fallbackToDestructiveMigration(true).build() }
    val fakeLink: FakeChamberLink by lazy { FakeChamberLink(appScope) }
    private val prefs by lazy { getSharedPreferences("enlace", MODE_PRIVATE) }
    val link: SwitchLink by lazy {
        val modo = runCatching { LinkMode.valueOf(prefs.getString("modo", null) ?: "") }.getOrDefault(LinkMode.Simulada)
        SwitchLink(appScope, fakeLink, BleChamberLink(appScope), modo) { prefs.edit().putString("modo", it.name).apply() }
    }
    val session: ChamberSession by lazy { ChamberSession(link, db, appScope) }
}
