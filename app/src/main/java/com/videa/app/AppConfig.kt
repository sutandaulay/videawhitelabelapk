package com.videa.app

object AppConfig {
    // 1. Branding
    const val SCHOOL_NAME = "SMK ANNUR"
    const val TAGLINE = "Smart School Ecosystem"
    const val SUB_TAGLINE = "More Than Education, Growing Minds & Shaping Character"

    // 2. URLs
    const val PPDB_PATH = "/ppdb"
    val BASE_URL = BuildConfig.APP_URL

    // 3. Security Zones (Paths that trigger Exam Mode)
    val MILITARY_ZONES = listOf("/room")
    val ASSESSMENT_ZONES = listOf("/assesment", "/assessment", "/exam", "/ujian", "/pengerjaan", "/test", "/quiz")

    // 4. Timeouts & Intervals
    const val SPLASH_SCREEN_TIME = 15000L // 15 seconds
    const val SECURITY_CHECK_INTERVAL = 3000L

    // 5. Aesthetic Constants (Colors are in colors.xml)
    const val LUXURY_GOLD = "#D4AF37"
    const val LUXURY_NAVY = "#1A233A"
}