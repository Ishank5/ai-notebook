package com.humblesolutions.ainotetaker.model

/**
 * One level of the cascade.
 *
 * BOOTH IS THE ONE LINK THAT IS NOT A CHILD OF THE LINK ABOVE IT. The client's
 * `Area Locality Booth.xlsx` has 95 of 181 booths serving more than one
 * locality, and 34 localities split across several booths — locality↔booth is
 * many-to-many, so booths hang off the WARD, beside localities rather than
 * beneath them, and record the localities they serve in `localityIds`.
 *
 * `filterField` says so: a booth's options are the ones that CONTAIN the chosen
 * locality, not the ones whose parent is it. A booth still comes after a
 * locality in the form, just not under one in the tree.
 */
enum class GeoLink(val key: String, val type: String, val labelKey: String, val filterField: String? = null) {
    ZONE("zoneId", "zone", "f.zone"),
    AREA("areaId", "area", "f.area"),
    WARD("wardId", "ward", "f.ward"),
    LOCALITY("localityId", "locality", "f.locality"),
    BOOTH("boothId", "booth", "f.booth", filterField = "localityIds");

    companion object {
        fun fromType(type: String?): GeoLink? = entries.firstOrNull { it.type == type }
    }
}

/**
 * A zone, area, ward, locality or booth.
 *
 * `ancestors` is root → leaf and EXCLUDES the unit itself, matching what the
 * seed writes and what the rules read: one document settles a whole chain.
 */
data class GeoUnit(
    val id: String = "",
    val type: String = "",
    val parentId: String? = null,
    val ancestors: List<String> = emptyList(),
    val order: Int = 0,
    val name: LocalisedName = LocalisedName(),
    val code: String? = null,
    val active: Boolean = true,
    /** Booth only: the localities it serves. */
    val localityIds: List<String> = emptyList(),
    /** Booth only: every ward it touches — 29 real booths straddle two. */
    val wardIds: List<String> = emptyList(),
    /** Booth only: total votes at that booth. */
    val voters: Int? = null,

    /**
     * Where to put the pin. Null until somebody places it.
     *
     * NOT A BOUNDARY, AND THE DIFFERENCE IS THE WHOLE DESIGN. Locality
     * outlines do not exist for Indian cities at this level and would have to
     * be drawn by hand 476 times; a point can be found for nearly all of them
     * in an afternoon. So the map is pins, and tapping a pin opens the
     * locality rather than tapping an area.
     *
     * A locality's point comes from a geocoder; a ward's, an area's and the
     * zone's are the AVERAGE of the level below, because "Ward No. 10" is not
     * a place any map has heard of — asked directly, Google put it in Kharar.
     */
    val center: GeoPointValue? = null,

    /** `geocode`, `derived` from the level below, or `manual`. */
    val centerSource: String? = null,

    /**
     * How much to trust the pin: `high`, `neighbourhood`, `suspect`,
     * `derived`, `manual`.
     *
     * CARRIED TO THE SCREEN RATHER THAN HIDDEN, because a pin that is a few
     * hundred metres out is genuinely useful and a pin on the wrong road is
     * not, and only the map can decide how to say so. `neighbourhood` means
     * several sub-colonies of one place share a point — right area, wrong
     * street — which is the common case here and is fine.
     */
    val centerConfidence: String? = null,

    /**
     * Booth only: the roll revision its number belongs to ("2025").
     *
     * BOOTH NUMBERS ARE REISSUED AT EVERY REVISION of the electoral roll —
     * 155 parts in 2012, 211 in 2022, 204 in 2023 — while a locality keeps
     * its name. So a booth is a number AND a year, and anything filed by
     * booth alone is filed under something that moves. Null on every other
     * level, and on a booth that predates this field (read as "the current
     * roll's").
     */
    val edition: String? = null,
) {
    val link: GeoLink? get() = GeoLink.fromType(type)

    /** True when this unit can be drawn on a map at all. */
    val isPlaced: Boolean get() = center != null
}

/**
 * One name in three scripts.
 *
 * A missing translation renders as the English rather than as a blank — the
 * client's geography arrived in English only and the Hindi and Punjabi are
 * filled in later, so an empty string here would blank the picker.
 */
data class LocalisedName(
    val en: String = "",
    val hi: String = "",
    val pa: String = "",
) {
    fun forLanguage(code: String): String = when (code) {
        "hi" -> hi.ifBlank { en }
        "pa" -> pa.ifBlank { en }
        else -> en
    }
}
