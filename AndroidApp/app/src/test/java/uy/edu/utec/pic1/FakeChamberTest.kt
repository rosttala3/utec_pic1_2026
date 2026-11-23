package uy.edu.utec.pic1

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.edu.utec.pic1.link.FakeChamberLink

@OptIn(ExperimentalCoroutinesApi::class)
class FakeChamberTest {
    private suspend fun TestScope.setup(): Pair<FakeChamberLink, MutableList<String>> {
        val link = FakeChamberLink(backgroundScope)
        val got = mutableListOf<String>()
        backgroundScope.launch { link.lines.collect { got += it } }
        val c = launch { link.connect() }
        advanceTimeBy(1_000); c.join()
        return link to got
    }

    private suspend fun TestScope.cmd(link: FakeChamberLink, line: String) {
        link.send(line); advanceTimeBy(200)
    }

    @Test fun assayPreparesSampleAdjustsAndFinishes() = runTest {
        val (link, got) = setup()
        link.speed.value = 3600
        cmd(link, "ensayo,5,7200,60")
        assertTrue("sin errores: $got", got.none { it.startsWith("err") })
        advanceTimeBy(30_000)
        assertTrue(got.any { it.startsWith("ev,ensayo,1,1") })
        // Las cuatro fases, en orden.
        val fases = got.filter { it.startsWith("ev,paso,") }.map { it.split(',')[4] }
        assertEquals(listOf("llenado", "dosis", "ajuste", "ensayo"), fases)
        assertTrue(got.any { it.startsWith("ev,dosis,agua,") && it.contains(",lleno") })
        assertTrue(got.any { it.startsWith("ev,dosis,sal,") && it.endsWith(",ok") })
        assertTrue("hubo ajuste fino", got.any { it.startsWith("ev,dosis,sal,") && it.endsWith(",ajuste") })
        assertTrue(got.any { it == "ev,fin" })
        // En la fase de ensayo la concentración está dentro de la tolerancia del objetivo.
        val ensayoIdx = got.indexOfFirst { it.startsWith("ev,paso,4") }
        val enEnsayo = got.drop(ensayoIdx).filter { it.startsWith("t,") }.map { it.split(',')[4].toDouble() }
        assertTrue(enEnsayo.isNotEmpty())
        enEnsayo.forEach { assertEquals(5.0, it, 0.3) }
        // La duración cuenta desde que empieza la fase de ensayo.
        val inicio = got.first { it.startsWith("ev,paso,4") }.split(',')[5].toLong()
        val ultimo = got.last { it.startsWith("t,") }.split(',')[2].toLong()
        assertEquals(7_200.0, (ultimo - inicio).toDouble(), 60.0)

        got.clear()
        cmd(link, "historial,10")
        assertTrue(got.first().startsWith("ev,ensayo,1,"))
        assertEquals("ev,sync_fin", got.last())
        assertTrue(got.filter { it.startsWith("t,") }.all { it.split(',')[1].toLong() > 10 })
    }

    @Test fun atomicOperations() = runTest {
        val (link, got) = setup()
        cmd(link, "actuar(bomba,on)")
        assertTrue("bomba en seco rechazada: $got", got.last().startsWith("err,tanque_vacio"))
        cmd(link, "actuar(valvula,on)")
        assertEquals("ok", got.last())
        cmd(link, "estado,json")
        assertTrue(got.last().contains("\"valvula\":1"))
        advanceTimeBy(70_000)   // se llena y la válvula se cierra sola
        assertTrue(got.any { it.startsWith("ev,manual_fin,valvula,") })
        cmd(link, "actuar(sinfin,0)")
        assertTrue(got.last().startsWith("err,rango"))
        cmd(link, "actuar(sinfin,400)")
        assertEquals("ok", got.last())
        cmd(link, "ensayo,5,7200,60")
        assertTrue("sigue sin bloquear el ensayo en el simulador", got.none { it.startsWith("err,sintaxis") })
    }

    @Test fun invalidAssayIsRejected() = runTest {
        val (link, got) = setup()
        for (bad in listOf("ensayo,0,3600,60", "ensayo,5,0,60", "ensayo,5,3600,1", "ensayo,50,3600,60", "ensayo,5")) {
            got.clear(); cmd(link, bad)
            assertTrue("$bad → $got", got.single().startsWith("err,rango"))
        }
    }

    @Test fun busyWhileRunning() = runTest {
        val (link, got) = setup()
        cmd(link, "ensayo,5,3600,60")
        got.clear(); cmd(link, "ensayo,5,3600,60")
        assertTrue(got.single().startsWith("err,ocupado"))
    }

    @Test fun statusReportsPhaseWhileRunning() = runTest {
        val (link, got) = setup()
        cmd(link, "ensayo,3.5,3600,60")
        got.clear(); cmd(link, "estado,json")
        val json = got.single()
        assertTrue(json, json.contains("\"conc_obj\":3.5") && json.contains("\"duracion_s\":3600") && json.contains("\"fase\":\"llenado\""))
    }

    @Test fun stopAborts() = runTest {
        val (link, got) = setup()
        cmd(link, "ensayo,5,3600,60")
        assertTrue(got.contains("ok"))
        got.clear(); cmd(link, "detener")
        assertTrue(got.contains("ev,abort,detener"))
    }

    @Test fun powerCutReportsRebootOnReconnect() = runTest {
        val (link, got) = setup()
        cmd(link, "ensayo,5,3600,60")
        link.simulatePowerCut()
        got.clear()
        val c = launch { link.connect() }; advanceTimeBy(1_000); c.join()
        assertEquals("ev,abort,reinicio", got.single())
    }
}
