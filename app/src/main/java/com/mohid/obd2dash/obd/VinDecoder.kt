package com.mohid.obd2dash.obd

/**
 * What a VIN will tell you without asking anyone.
 *
 * A VIN is three fields glued together. The first three characters are the
 * world manufacturer identifier, which is assigned centrally and is the same
 * on every car that maker builds. The tenth is the model year. The middle
 * section that actually names the model is manufacturer-specific and is not
 * published anywhere, so no offline decoder can turn a VIN into "Move" —
 * only into "Daihatsu, 2023".
 *
 * That is the honest ceiling here, and it is deliberately where this stops.
 * The remaining half of the name is asked of the driver once per car and then
 * remembered, which costs one dialog and works on a car this table has never
 * heard of, in a country with no VIN service, with no network in a basement
 * car park.
 */
object VinDecoder {

    /** Letters that never appear in a VIN, because they read as 1 and 0. */
    private const val ILLEGAL = "IOQ"

    data class Facts(
        val vin: String,
        val make: String?,
        val modelYear: Int?,
        val region: String?,
    ) {
        /**
         * The best name available from the VIN alone: "Toyota 2016", falling
         * back through the maker on its own to the region that built it.
         */
        val label: String?
            get() = when {
                make != null && modelYear != null -> "$make $modelYear"
                make != null -> make
                modelYear != null && region != null -> "$region $modelYear"
                region != null -> region
                else -> null
            }
    }

    fun isWellFormed(vin: String): Boolean =
        vin.length == 17 && vin.all { (it.isDigit() || it in 'A'..'Z') && it !in ILLEGAL }

    fun decode(vin: String?): Facts? {
        if (vin == null) return null
        val clean = vin.trim().uppercase()
        if (!isWellFormed(clean)) return null
        return Facts(
            vin = clean,
            make = makeFor(clean),
            modelYear = modelYear(clean),
            region = regionFor(clean),
        )
    }

    private fun makeFor(vin: String): String? {
        val wmi = vin.take(3)
        WMI[wmi]?.let { return it }
        // Makers with fewer than 500 cars a year share a WMI and are told
        // apart by characters 12-14, which we have no table for. The first
        // two characters still name the manufacturer group.
        return WMI_PREFIX[vin.take(2)]
    }

    /**
     * The year code repeats every thirty years, so B is both 1981 and 2011.
     * Position 7 breaks the tie: it is numeric on cars from the first cycle
     * and alphabetic on the second, which is the convention every passenger
     * car built since 2010 follows.
     */
    private fun modelYear(vin: String): Int? {
        val base = YEAR_CODES[vin[9]] ?: return null
        val secondCycle = vin[6].isLetter()
        val year = if (secondCycle) base + 30 else base
        // A model year runs ahead of the calendar, but not by decades: a VIN
        // claiming to be a long way in the future is a misread, not a concept car.
        return year.takeIf { it in 1980..CURRENT_YEAR + 2 }
    }

    private fun regionFor(vin: String): String? = REGIONS[vin[0]]

    private const val CURRENT_YEAR = 2026

    private val YEAR_CODES: Map<Char, Int> = buildMap {
        // A-Y with the illegal letters and U and Z left out, then 1-9.
        val letters = "ABCDEFGHJKLMNPRSTVWXY"
        letters.forEachIndexed { i, c -> put(c, 1980 + i) }
        "123456789".forEachIndexed { i, c -> put(c, 2001 + i) }
    }

    private val REGIONS: Map<Char, String> = buildMap {
        "ABCDEFGH".forEach { put(it, "Africa") }
        "JKLMNPR".forEach { put(it, "Asia") }
        "STUVWXYZ".forEach { put(it, "Europe") }
        "12345".forEach { put(it, "North America") }
        "67".forEach { put(it, "Oceania") }
        "89".forEach { put(it, "South America") }
    }

    /**
     * Manufacturer prefixes for the shared-WMI case. Two characters is enough
     * to name the group even when the third belongs to a low-volume brand.
     */
    private val WMI_PREFIX: Map<String, String> = mapOf(
        "JT" to "Toyota",
        "JH" to "Honda",
        "JN" to "Nissan",
        "JM" to "Mazda",
        "JF" to "Subaru",
        "JS" to "Suzuki",
        "JD" to "Daihatsu",
        "JA" to "Mitsubishi",
        "KM" to "Hyundai",
        "KN" to "Kia",
        "WV" to "Volkswagen",
        "WB" to "BMW",
        "WD" to "Mercedes-Benz",
        "WA" to "Audi",
        "WP" to "Porsche",
        "VF" to "Peugeot-Citroen",
        "ZF" to "Fiat",
        "YV" to "Volvo",
        "SA" to "Jaguar Land Rover",
    )

    /**
     * The makers most likely to turn up on a phone running this, which is to
     * say Japanese domestic and export models first, then Korean, European and
     * American. An unknown WMI is not a failure: the region and year still
     * come back, and the driver names the car once.
     */
    private val WMI: Map<String, String> = mapOf(
        // Toyota and Lexus, including the export plants
        "JTD" to "Toyota", "JTE" to "Toyota", "JTF" to "Toyota", "JTG" to "Toyota",
        "JTJ" to "Lexus", "JTH" to "Lexus", "JT2" to "Toyota", "JT3" to "Toyota",
        "JT4" to "Toyota", "JT6" to "Lexus", "JT8" to "Lexus", "JTK" to "Toyota",
        "JTL" to "Toyota", "JTM" to "Toyota", "JTN" to "Toyota",
        "MR0" to "Toyota", "MHF" to "Toyota", "AHT" to "Toyota", "SB1" to "Toyota",
        "2T1" to "Toyota", "4T1" to "Toyota", "4T3" to "Toyota", "5TB" to "Toyota",
        "5TD" to "Toyota", "NMT" to "Toyota",
        // Daihatsu
        "JDA" to "Daihatsu", "JDB" to "Daihatsu", "MHK" to "Daihatsu",
        // Suzuki, including Maruti and Pak Suzuki
        "JS1" to "Suzuki", "JS2" to "Suzuki", "JS3" to "Suzuki", "JSA" to "Suzuki",
        "JST" to "Suzuki", "MA3" to "Suzuki", "TSM" to "Suzuki", "MMS" to "Suzuki",
        // Honda
        "JHM" to "Honda", "JHL" to "Honda", "JHG" to "Honda", "1HG" to "Honda",
        "2HG" to "Honda", "19X" to "Honda", "SHH" to "Honda", "MRH" to "Honda",
        "JHZ" to "Honda", "5FN" to "Honda", "2HK" to "Honda",
        // Nissan
        "JN1" to "Nissan", "JN6" to "Nissan", "JN8" to "Nissan", "VSK" to "Nissan",
        "SJN" to "Nissan", "MDH" to "Nissan", "1N4" to "Nissan", "3N1" to "Nissan",
        // Mitsubishi
        "JA3" to "Mitsubishi", "JA4" to "Mitsubishi", "JMB" to "Mitsubishi",
        "JMY" to "Mitsubishi", "MMB" to "Mitsubishi", "MMC" to "Mitsubishi",
        "4A3" to "Mitsubishi",
        // Mazda
        "JM1" to "Mazda", "JM3" to "Mazda", "JM7" to "Mazda", "4F2" to "Mazda",
        "3MZ" to "Mazda", "JMZ" to "Mazda",
        // Subaru, Isuzu, Hino
        "JF1" to "Subaru", "JF2" to "Subaru", "4S3" to "Subaru", "4S4" to "Subaru",
        "JAA" to "Isuzu", "JAB" to "Isuzu", "JAC" to "Isuzu", "MPA" to "Isuzu",
        "JHH" to "Hino",
        // Korea
        "KMH" to "Hyundai", "KMF" to "Hyundai", "KM8" to "Hyundai", "5NP" to "Hyundai",
        "TMA" to "Hyundai", "MAL" to "Hyundai", "NLH" to "Hyundai",
        "KNA" to "Kia", "KND" to "Kia", "KNE" to "Kia", "KNM" to "Kia", "U5Y" to "Kia",
        "KL1" to "Chevrolet", "KLA" to "Daewoo", "KPT" to "SsangYong",
        // Europe
        "WVW" to "Volkswagen", "WV1" to "Volkswagen", "WV2" to "Volkswagen",
        "3VW" to "Volkswagen", "1VW" to "Volkswagen", "9BW" to "Volkswagen",
        "WAU" to "Audi", "WA1" to "Audi", "TRU" to "Audi",
        "WBA" to "BMW", "WBS" to "BMW", "WBY" to "BMW", "4US" to "BMW", "5UX" to "BMW",
        "WDB" to "Mercedes-Benz", "WDC" to "Mercedes-Benz", "WDD" to "Mercedes-Benz",
        "WDF" to "Mercedes-Benz", "W1K" to "Mercedes-Benz", "W1N" to "Mercedes-Benz",
        "4JG" to "Mercedes-Benz", "WMW" to "MINI",
        "WP0" to "Porsche", "WP1" to "Porsche",
        "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroen", "VF6" to "Renault",
        "ZFA" to "Fiat", "ZFF" to "Ferrari", "ZAR" to "Alfa Romeo", "ZHW" to "Lamborghini",
        "YV1" to "Volvo", "YV4" to "Volvo", "YS3" to "Saab",
        "SAL" to "Land Rover", "SAJ" to "Jaguar", "SCC" to "Lotus", "SCB" to "Bentley",
        "TMB" to "Skoda", "VSS" to "SEAT", "SUP" to "Dacia", "UU1" to "Dacia",
        // North America
        "1FA" to "Ford", "1FB" to "Ford", "1FC" to "Ford", "1FD" to "Ford",
        "1FM" to "Ford", "1FT" to "Ford", "3FA" to "Ford", "WF0" to "Ford",
        "1G1" to "Chevrolet", "1GC" to "Chevrolet", "2G1" to "Chevrolet",
        "3GN" to "Chevrolet", "1GT" to "GMC", "1GY" to "Cadillac",
        "1C3" to "Chrysler", "1C4" to "Jeep", "1J4" to "Jeep", "3C4" to "Chrysler",
        "5YJ" to "Tesla", "7SA" to "Tesla",
        // China
        "LSG" to "Buick", "LSJ" to "MG", "LSV" to "Volkswagen", "LFV" to "Volkswagen",
        "LGW" to "Great Wall", "LB3" to "Geely", "LVS" to "Ford", "LDC" to "Dongfeng",
        "LJD" to "JAC", "LZW" to "Wuling", "LVV" to "Chery", "L6T" to "Geely",
        "LYV" to "Volvo", "LRW" to "Tesla", "LGX" to "BYD", "LC0" to "BYD",
        // South and South East Asia
        "MBJ" to "Toyota", "MB1" to "Ashok Leyland", "MAT" to "Tata", "MAK" to "Honda",
        "MEE" to "Renault", "MZB" to "Suzuki", "PL1" to "Proton", "PM2" to "Perodua",
    )
}
