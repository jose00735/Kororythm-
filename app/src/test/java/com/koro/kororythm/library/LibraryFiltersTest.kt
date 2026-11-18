package com.koro.kororythm.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFiltersTest {

    @Test fun decades() {
        assertEquals(1990, Decades.of(1994))
        assertEquals(2000, Decades.of(2000))
        assertEquals(Decades.UNKNOWN, Decades.of(null))
        assertEquals("1990s", Decades.label(1990))
        assertTrue(Decades.matches(emptySet(), null))
        assertTrue(Decades.matches(setOf(1990, 2000), 2004))
        assertFalse(Decades.matches(setOf(1990, 2000), 2012))
        assertTrue(Decades.matches(setOf(Decades.UNKNOWN), null))
    }

    @Test fun sameSongDespiteVideoTitleNoise() {
        assertTrue(SongMatch.same("This Is My Life", "Edward Maya ft Vika Jigulina", "This Is My Life (Official Video)", "Edward Maya"))
        assertTrue(SongMatch.same("La Gota Fría", "Carlos Vives", "la gota fria [HD]", "Carlos Vives & Alfredo Gutiérrez"))
        assertTrue(SongMatch.same("Nightcall", null, "Nightcall", "Kavinsky"))
        assertTrue(SongMatch.same("Negro y Azul: Ballad of Heisenberg", "Los Cuates de Sinaloa", "Negro y Azul", "Los Cuates de Sinaloa"))
        assertFalse(SongMatch.same("Nightcall", "Kavinsky", "Nightcall", "London Grammar"))
        assertFalse(SongMatch.same("Home", "Edward Maya", "Stereo Love", "Edward Maya"))
        assertFalse(SongMatch.same("Love", "A", "Lovers Rock Medley", "A")) // short titles must match exactly
        assertEquals("edward maya", SongMatch.normArtist("Edward Maya ft Vika Jigulina"))
        assertEquals("silvestre dangond", SongMatch.normArtist("Silvestre Dangond y Juancho de la Espriella"))
    }
}
