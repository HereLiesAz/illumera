package com.hereliesaz.illumera.ui.player.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageNormalizationTest {

    @Test
    fun nullBlankAndUndeterminedNormalizeToUnd() {
        assertEquals("und", normalizeLanguageToIso2(null))
        assertEquals("und", normalizeLanguageToIso2(""))
        assertEquals("und", normalizeLanguageToIso2("   "))
        assertEquals("und", normalizeLanguageToIso2("und"))
        assertEquals("und", normalizeLanguageToIso2("x"))
    }

    @Test
    fun iso2CodesAreNormalizedForCaseAndWhitespace() {
        assertEquals("en", normalizeLanguageToIso2(" EN "))
        assertEquals("fr", normalizeLanguageToIso2("fr"))
    }

    @Test
    fun iso3AndLegacyBibliographicCodesNormalizeToIso2() {
        assertEquals("en", normalizeLanguageToIso2("eng"))
        assertEquals("fr", normalizeLanguageToIso2("fre"))
        assertEquals("de", normalizeLanguageToIso2("ger"))
        assertEquals("el", normalizeLanguageToIso2("gre"))
        assertEquals("zh", normalizeLanguageToIso2("zho"))
    }

    @Test
    fun addonAliasesNormalizeToCanonicalCodes() {
        assertEquals("pt-BR", normalizeLanguageToIso2("pob"))
        assertEquals("pt-BR", normalizeLanguageToIso2("ptbr"))
        assertEquals("pt", normalizeLanguageToIso2("ptpt"))
        assertEquals("es", normalizeLanguageToIso2("spn"))
        assertEquals("es-419", normalizeLanguageToIso2("spl"))
        assertEquals("sr", normalizeLanguageToIso2("scc"))
        assertEquals("hr", normalizeLanguageToIso2("scr"))
    }

    @Test
    fun regionalVariantsArePreservedOrCollapsedIntentionally() {
        assertEquals("pt-BR", normalizeLanguageToIso2("pt_BR"))
        assertEquals("es-419", normalizeLanguageToIso2("es-MX"))
        assertEquals("es-419", normalizeLanguageToIso2("es-419"))
        assertEquals("en", normalizeLanguageToIso2("en-US"))
    }

    @Test
    fun chineseAddonVariantsCollapseToChinese() {
        assertEquals("zh", normalizeLanguageToIso2("zh-Hant"))
        assertEquals("zh", normalizeLanguageToIso2("zh_CN"))
        assertEquals("zh", normalizeLanguageToIso2("zht"))
        assertEquals("zh", normalizeLanguageToIso2("chs"))
    }

    @Test
    fun fullEnglishLanguageNamesResolve() {
        assertEquals("en", normalizeLanguageToIso2("English"))
        assertEquals("fr", normalizeLanguageToIso2("French"))
        assertEquals("es", normalizeLanguageToIso2("Spanish"))
    }

    @Test
    fun unknownMultiCharacterTagsRemainStableInsteadOfBecomingUnd() {
        assertEquals("xx", normalizeLanguageToIso2("XX"))
        assertEquals("qaa", normalizeLanguageToIso2("qaa"))
    }

    @Test
    fun normalizedLanguageNameKeyRemovesPunctuationAndCompressesWhitespace() {
        assertEquals("portuguese brazil", normalizedLanguageNameKey("  Portuguese (Brazil)  "))
        assertEquals("chinese traditional", normalizedLanguageNameKey("Chinese---Traditional"))
        assertNull(normalizedLanguageNameKey(" !!! "))
        assertNull(normalizedLanguageNameKey(null))
    }
}
