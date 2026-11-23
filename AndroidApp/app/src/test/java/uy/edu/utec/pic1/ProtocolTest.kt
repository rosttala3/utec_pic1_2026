package uy.edu.utec.pic1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.edu.utec.pic1.protocol.AssayConfig
import uy.edu.utec.pic1.protocol.ChamberLine
import uy.edu.utec.pic1.protocol.ChamberStatus
import uy.edu.utec.pic1.protocol.Concentration

class ProtocolTest {
    @Test fun assayCommand() {
        assertEquals("ensayo,5.00,86400,60", AssayConfig(5.0, 86_400, 60).command())
        assertEquals("ensayo,3.50,3600,10", AssayConfig(3.5, 3_600, 10).command())
    }

    @Test fun assayValidation() {
        assertTrue(AssayConfig().validate().isEmpty())
        assertTrue(AssayConfig(concPercent = 0.0).validate().isNotEmpty())
        assertTrue(AssayConfig(concPercent = 30.0).validate().isNotEmpty())
        assertTrue(AssayConfig(concPercent = Double.NaN).validate().isNotEmpty())
        assertTrue(AssayConfig(duracionS = 0).validate().isNotEmpty())
        assertTrue(AssayConfig(duracionS = 8 * 86_400).validate().isNotEmpty())
        assertTrue(AssayConfig(muestreoS = 1).validate().isNotEmpty())
    }

    @Test fun concentrationFormulaMatchesAdr() {
        assertEquals(5.263, Concentration.gramsFor(5.0, 100.0), 0.01)
        assertEquals(5.0, Concentration.percent(5.263, 100.0), 0.01)
    }

    @Test fun parsesDeviceLines() {
        assertEquals(ChamberLine.Ok, ChamberLine.parse("ok"))
        assertEquals(ChamberLine.Err("rango", "mal"), ChamberLine.parse("err,rango,mal"))
        assertEquals(ChamberLine.Sample(5, 300, 24.5, 4.9, 1180.0), ChamberLine.parse("t,5,300,24.5,4.9,1180.0"))
        assertEquals(ChamberLine.StepStart(3, 4, "ajuste", 900), ChamberLine.parse("ev,paso,3,4,ajuste,900"))
        assertEquals(ChamberLine.StepStart(3, 4, "ajuste", null), ChamberLine.parse("ev,paso,3,4,ajuste"))
        assertEquals(ChamberLine.Dose("sal", 14.8, 14.8, "ajuste"), ChamberLine.parse("ev,dosis,sal,14.8,14.8,ajuste"))
        assertEquals(ChamberLine.Abort("reinicio"), ChamberLine.parse("ev,abort,reinicio"))
        assertTrue(ChamberLine.parse("t,5,300,24.5,4.9,1180.0").isEvent)
        assertTrue(!ChamberLine.parse("ok").isEvent)
    }

    @Test fun parsesStatusJson() {
        val st = ChamberStatus.parse("""{"estado":"corriendo","ensayo":3,"seg":120,"seq":9,"temp":25.1,"cond":0,"vol":900,"tanque":"medio","cap_ml":1200,"fase":"ensayo","fase_inicio":900,"conc_obj":5,"duracion_s":86400,"cal":{"agua_ml_s":20,"sal_pasos_g":100}}""")!!
        assertTrue(st.running); assertEquals(3L, st.ensayoId); assertEquals(1200, st.capMl)
        assertEquals("ensayo", st.fase); assertEquals(900L, st.faseInicioS)
        assertEquals(5.0, st.concObjetivo!!, 0.0); assertEquals(86_400L, st.duracionS)
        val idle = ChamberStatus.parse("""{"estado":"reposo","ensayo":3,"seg":0,"seq":9,"temp":25.1,"cond":0,"vol":0,"tanque":"vacio","cap_ml":1200}""")!!
        assertEquals(null, idle.fase); assertEquals(null, idle.concObjetivo)
    }
}
