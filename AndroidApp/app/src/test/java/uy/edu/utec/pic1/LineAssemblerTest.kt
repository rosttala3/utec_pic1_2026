package uy.edu.utec.pic1

import org.junit.Assert.assertEquals
import org.junit.Test
import uy.edu.utec.pic1.link.LineAssembler

class LineAssemblerTest {
    @Test fun joinsFragmentsAndSplitsMergedLines() {
        val a = LineAssembler()
        assertEquals(emptyList<String>(), a.feed("est".toByteArray()))
        assertEquals(listOf("estado,json"), a.feed("ado,json\r\n".toByteArray()))
        assertEquals(listOf("ok", "t,1,60,24.0,5.00,1200.0"), a.feed("ok\nt,1,60,24.0,5.00,1200.0\n\n".toByteArray()))
    }

    @Test fun multibyteCharacterSplitAcrossChunks() {
        val a = LineAssembler()
        val b = "ñ\n".toByteArray()
        assertEquals(emptyList<String>(), a.feed(b.copyOfRange(0, 1)))
        assertEquals(listOf("ñ"), a.feed(b.copyOfRange(1, b.size)))
    }
}
