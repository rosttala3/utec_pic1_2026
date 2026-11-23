package uy.edu.utec.pic1.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import uy.edu.utec.pic1.App
import uy.edu.utec.pic1.data.Muestra
import uy.edu.utec.pic1.link.LinkMode
import uy.edu.utec.pic1.protocol.AssayConfig

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModel(private val app: App) : ViewModel() {
    private val session = app.session
    val live = session.live
    val speed: StateFlow<Int> = app.fakeLink.speed

    val samples: StateFlow<List<Muestra>> = live.map { it.ensayo?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else app.db.muestras().observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mode = app.link.mode
    fun selectMode(m: LinkMode) = app.link.select(m)
    fun connect() = session.connect()
    fun setSpeed(x: Int) { app.fakeLink.speed.value = x }
    fun dismissAbort() = session.dismissAbort()
    suspend fun stop() = session.stop()
    suspend fun startAssay(c: AssayConfig) = session.startAssay(c)
}
