package com.sowz.aestudio.core

import org.junit.Assert.*
import org.junit.Test

class AepxProjectInspectorTest {
    @Test
    fun countsCompositionLayersFootageAndEffectNodes() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <AfterEffectsProject>
                <Composition name="Main">
                    <AVLayer name="Video 1">
                        <Effect matchName="ADBE Gaussian Blur 2" />
                    </AVLayer>
                    <FootageItem name="clip.mp4" />
                </Composition>
            </AfterEffectsProject>
        """.trimIndent().toByteArray()
        val result = AepxProjectInspector.inspect(xml)
        assertEquals("AfterEffectsProject", result.rootElement)
        assertEquals(1, result.compositionCount)
        assertEquals(1, result.layerCount)
        assertEquals(1, result.footageCount)
        assertEquals(listOf("ADBE Gaussian Blur 2"), result.effectElements)
    }

    @Test
    fun rejectsDocumentTypeDeclarations() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE x [<!ENTITY ext SYSTEM "file:///private/secret">]>
            <AfterEffectsProject>&ext;</AfterEffectsProject>
        """.trimIndent().toByteArray()
        assertThrows( Exception::class.java ) {
            AepxProjectInspector.inspect(xml)
        }
    }

    @Test
    fun rejectsUnrelatedXmlAndExcessiveNesting() {
        assertThrows(IllegalArgumentException::class.java) {
            AepxProjectInspector.inspect("<Other/>".toByteArray())
        }
        val deep = "<AfterEffectsProject>" + "<Layer>".repeat(100) +
            "</Layer>".repeat(100) + "</AfterEffectsProject>"
        assertThrows(IllegalArgumentException::class.java) {
            AepxProjectInspector.inspect(deep.toByteArray())
        }
    }
}
