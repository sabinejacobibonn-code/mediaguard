package com.mediaguard.analysis

/**
 * TextAnalysisModule
 * Analysiert Text auf Manipulationsmuster.
 * Score 0–100: 0 = neutral, 100 = hochmanipulativ
 */
class TextAnalysisModule {

    // ── Wortlisten ───────────────────────────────────────────────────────────

    private val fearTriggers = listOf(
        "gefahr", "angst", "bedrohung", "katastrophe", "panik", "notfall",
        "alarm", "warnung", "krise", "chaos", "kollaps", "untergang",
        "schockierend", "erschreckend", "entsetzlich", "horror", "tragödie",
        "danger", "crisis", "threat", "warning", "catastrophe", "panic"
    )

    private val angerTriggers = listOf(
        "lüge", "lügner", "betrug", "betrüger", "skandal", "versagen",
        "schande", "empörend", "unverschämt", "inakzeptabel", "wut",
        "hass", "verrat", "heuchler", "korrupt", "kriminell",
        "disgrace", "outrage", "fraud", "corrupt", "betrayal"
    )

    private val guiltTriggers = listOf(
        "deine schuld", "du bist verantwortlich", "du hättest", "warum hast du nicht",
        "alle anderen", "nur du nicht", "schämst du dich", "verantwortungslos",
        "your fault", "you should have", "how could you", "shameful"
    )

    private val fomoTriggers = listOf(
        "jetzt handeln", "sofort", "letzte chance", "nur noch", "verpasse nicht",
        "bevor es zu spät", "limitiert", "exklusiv", "nur heute", "läuft ab",
        "act now", "last chance", "don't miss", "expires", "limited time",
        "nur noch wenige", "ausverkauft", "bald weg"
    )

    private val absoluteTerms = listOf(
        "immer", "nie", "niemals", "jeder", "alle", "niemand", "100%",
        "garantiert", "bewiesen", "definitiv", "absolut", "zweifelsfrei",
        "unwiderlegbar", "always", "never", "everyone", "nobody", "proven",
        "guaranteed", "definitely", "undeniably", "without doubt"
    )

    private val usVsThem = listOf(
        "die elite", "die globalist", "die medien lügen", "mainstream media",
        "wir gegen die", "der tiefe staat", "die wahre wahrheit",
        "was sie dir nicht sagen", "was die medien verschweigen",
        "die agenda", "die schlafschafe", "erwach", "die wahrheit über",
        "they don't want you to know", "mainstream won't tell", "deep state",
        "wake up", "sheeple", "the elites", "hidden agenda"
    )

    private val clickbaitPatterns = listOf(
        "die wahrheit über", "was niemand weiß", "du wirst nicht glauben",
        "schockierend:", "enthüllt:", "geheim:", "verboten:",
        "was sie verbergen", "insider enthüllt", "exklusiv:",
        "you won't believe", "the truth about", "shocking:", "revealed:",
        "secret:", "forbidden:", "insiders reveal", "exclusive:"
    )

    private val oversimplification = listOf(
        "ist ganz einfach", "so funktioniert das wirklich", "die lösung ist",
        "das einzige was du brauchst", "ein trick", "ein einfacher weg",
        "so einfach ist das", "es ist so simpel",
        "it's simple", "just do this", "one trick", "the only solution",
        "all you need to do"
    )

    // ── Hauptanalyse ─────────────────────────────────────────────────────────

    data class TextAnalysisResult(
        val score: Int,                    // 0–100
        val fearScore: Int,
        val angerScore: Int,
        val guiltScore: Int,
        val fomoScore: Int,
        val absoluteScore: Int,
        val usVsThemScore: Int,
        val clickbaitScore: Int,
        val oversimplificationScore: Int,
        val missingSourcesScore: Int,
        val detectedPatterns: List<String> // Für UI-Anzeige
    )

    fun analyze(text: String): TextAnalysisResult {
        if (text.isBlank()) return emptyResult()

        val lower = text.lowercase()
        val detected = mutableListOf<String>()

        val fear = countMatches(lower, fearTriggers).also {
            if (it > 0) detected.add("⚠️ Angst-Trigger ($it)")
        }
        val anger = countMatches(lower, angerTriggers).also {
            if (it > 0) detected.add("😠 Wut-Trigger ($it)")
        }
        val guilt = countMatches(lower, guiltTriggers).also {
            if (it > 0) detected.add("😔 Schuld-Trigger ($it)")
        }
        val fomo = countMatches(lower, fomoTriggers).also {
            if (it > 0) detected.add("⏰ FOMO-Muster ($it)")
        }
        val absolute = countMatches(lower, absoluteTerms).also {
            if (it > 0) detected.add("🔒 Absolute Aussagen ($it)")
        }
        val usVsThem = countMatches(lower, usVsThem).also {
            if (it > 0) detected.add("⚔️ Wir-gegen-Die ($it)")
        }
        val clickbait = countMatches(lower, clickbaitPatterns).also {
            if (it > 0) detected.add("🎣 Clickbait ($it)")
        }
        val overSimp = countMatches(lower, oversimplification).also {
            if (it > 0) detected.add("🎯 Übervereinfachung ($it)")
        }
        val missingSources = detectMissingSources(lower).also {
            if (it > 50) detected.add("📎 Keine Quellen angegeben")
        }

        // Gewichtete Einzelscores (cap bei 100)
        val fearS = minOf(fear * 15, 100)
        val angerS = minOf(anger * 15, 100)
        val guiltS = minOf(guilt * 20, 100)
        val fomoS = minOf(fomo * 20, 100)
        val absoluteS = minOf(absolute * 10, 100)
        val usVsThemS = minOf(usVsThem * 25, 100)
        val clickbaitS = minOf(clickbait * 30, 100)
        val oversimpS = minOf(overSimp * 15, 100)

        // Gesamt-Score (gewichtet)
        // Gewichte: 0.15+0.10+0.10+0.15+0.10+0.20+0.15+0.05 = 1.00 (exakt normiert)
        // missingSources fließt nicht in den Score ein (nur als Indikator in detectedPatterns)
        val total = (
            fearS     * 0.15 +
            angerS    * 0.10 +
            guiltS    * 0.10 +
            fomoS     * 0.15 +
            absoluteS * 0.10 +
            usVsThemS * 0.20 +
            clickbaitS * 0.15 +
            oversimpS  * 0.05
        ).toInt().coerceIn(0, 100)

        return TextAnalysisResult(
            score = total,
            fearScore = fearS,
            angerScore = angerS,
            guiltScore = guiltS,
            fomoScore = fomoS,
            absoluteScore = absoluteS,
            usVsThemScore = usVsThemS,
            clickbaitScore = clickbaitS,
            oversimplificationScore = oversimpS,
            missingSourcesScore = missingSources,
            detectedPatterns = detected
        )
    }

    private fun countMatches(text: String, patterns: List<String>): Int {
        return patterns.count { text.contains(it) }
    }

    private fun detectMissingSources(text: String): Int {
        // Punkte wenn keine URLs, keine Zitate, keine Quellen-Nennung
        var missingScore = 0
        val hasUrl = text.contains("http") || text.contains("www.") || text.contains(".de") || text.contains(".com")
        val hasQuote = text.contains("\"") || text.contains("„") || text.contains("laut ") || text.contains("lt. ")
        val hasSource = text.contains("studie") || text.contains("bericht") || text.contains("quelle") ||
                        text.contains("research") || text.contains("source") || text.contains("according to")

        if (!hasUrl) missingScore += 30
        if (!hasQuote) missingScore += 20
        if (!hasSource && text.length > 100) missingScore += 20

        return missingScore.coerceIn(0, 100)
    }

    private fun emptyResult() = TextAnalysisResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList())
}
