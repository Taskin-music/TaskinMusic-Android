/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.os.Build

object FamilyDeviceManager {
    /**
     * Identifies the family member name based on the hardware device model/identifiers.
     * Mappings:
     * - SM-S721B/DS (Samsung Galaxy S24 FE) -> Ata Berk
     * - SM-A325F/DS (Samsung Galaxy A32)    -> Nilay
     * - SM-A356E/DS (Samsung Galaxy A35 5G) -> Beren
     * - M2101K7BG   (Redmi Note 10S)        -> Günkut
     */
    fun getFamilyMemberName(): String? {
        val candidates = listOf(
            Build.MODEL,
            Build.PRODUCT,
            Build.DEVICE,
            Build.HARDWARE,
        ).filterNotNull()

        for (candidate in candidates) {
            val resolved = resolveMember(candidate)
            if (resolved != null) return resolved
        }

        return null
    }

    /**
     * Resolves member name from an identifier string, tolerating dual-SIM suffixes (/DS),
     * casing variations, hyphens, and whitespace.
     */
    fun resolveMember(raw: String): String? {
        if (raw.isBlank()) return null
        val cleaned = raw.uppercase()
            .replace("/DS", "")
            .replace("-", "")
            .replace(" ", "")
            .trim()

        return when {
            // Ata Berk: SM-S721B / S721B (Samsung Galaxy S24 FE)
            cleaned.contains("SMS721B") || cleaned.contains("S721B") -> "Ata Berk"

            // Nilay: SM-A325F / A325F (Samsung Galaxy A32)
            cleaned.contains("SMA325F") || cleaned.contains("A325F") -> "Nilay"

            // Beren: SM-A356E / A356E (Samsung Galaxy A35 5G)
            cleaned.contains("SMA356E") || cleaned.contains("A356E") -> "Beren"

            // Günkut: M2101K7BG (Redmi Note 10S)
            cleaned.contains("M2101K7BG") -> "Günkut"

            // Emulator / Android Studio test device fallback
            cleaned.contains("GOLDFISH") || cleaned.contains("RANCHU") ||
            cleaned.contains("SDK") || cleaned.contains("EMULATOR") -> "Ata Berk"

            else -> null
        }
    }
}
