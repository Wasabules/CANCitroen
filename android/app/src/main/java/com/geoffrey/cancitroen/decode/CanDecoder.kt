package com.geoffrey.cancitroen.decode

import com.geoffrey.cancitroen.usb.CanFrame

/**
 * Décodeur CAN : prend un [VehicleState] courant + une [CanFrame] reçue,
 * retourne le nouvel état avec les champs mis à jour.
 *
 * Port direct des `decode_XXX(data)` de `scripts/bridge.py` validé sur la
 * Citroën C2 du user (sessions 2026-04-26 → 2026-05-09).
 */
object CanDecoder {

    fun decode(state: VehicleState, frame: CanFrame): VehicleState {
        val data = frame.data
        // Pas de méta framesSeen/lastUpdateTs ajouté en queue : elles
        // changeaient à chaque frame et cassaient l'égalité du data class,
        // forçant Compose à recomposer 10x/sec inutilement.
        return when (frame.id) {
            CanIds.ID_BSI            -> decode036(state, data)
            CanIds.ID_RPM_SPEED      -> decode0B6(state, data)
            CanIds.ID_DASH1          -> decode0F6(state, data)
            CanIds.ID_LIGHTS         -> decode128(state, data)
            CanIds.ID_FUEL_OIL       -> decode161(state, data)
            CanIds.ID_EMF_STATE      -> decode167(state, data)
            CanIds.ID_DASH3          -> decode168(state, data)
            CanIds.ID_RADIO_REMOTE   -> decode21F(state, data)
            CanIds.ID_DOORS          -> decode220(state, data)
            CanIds.ID_CONS_RANGE     -> decode221(state, data)
            CanIds.ID_TRIP1          -> decode261(state, data)
            CanIds.ID_DATETIME       -> decode276(state, data)
            CanIds.ID_MAINTENANCE    -> decode3A7(state, data)
            else                      -> state
        }
    }

    private fun decode036(s: VehicleState, d: ByteArray): VehicleState {
        if (d.size < 5) return s
        val ig = d[4].toInt() and 0x07
        val mode = when (ig) {
            0 -> IgnitionMode.STANDBY
            1 -> IgnitionMode.NORMAL
            2 -> IgnitionMode.STANDBY_SOON
            3 -> IgnitionMode.WAKE_UP
            4 -> IgnitionMode.COM_OFF
            else -> IgnitionMode.UNKNOWN
        }
        return s.copy(
            economyMode = (d[2].toInt() and 0x80) != 0,
            dashboardBrightness = d[3].toInt() and 0x0F,
            blackPanel = (d[3].toInt() and 0x10) != 0,
            nightMode = (d[3].toInt() and 0x20) != 0,
            ignitionMode = mode,
        )
    }

    private fun decode0B6(s: VehicleState, d: ByteArray): VehicleState {
        if (d.size < 4) return s
        // Arrondi à 0,1 comme bridge.py (résolution brute : 1/8 tr/min, 0,01 km/h).
        val rpm = round1(beU16(d, 0) / 8.0)
        val speed = round1(beU16(d, 2) / 100.0)
        return s.copy(rpm = rpm, speed = speed)
    }

    private fun decode0F6(s: VehicleState, d: ByteArray): VehicleState {
        if (d.size < 8) return s
        val keyPos = (d[0].toInt() shr 3) and 0x03
        val keyEnum = when (keyPos) {
            0 -> KeyPosition.STOP
            1 -> KeyPosition.CONTACT
            2 -> KeyPosition.STARTER
            3 -> KeyPosition.FREE
            else -> KeyPosition.STOP
        }
        // bytes 2..4 BE = odomètre × 10 — sentinel 0xFFFFFF = "non initialisé"
        // (BSI cold boot avant sync mémoire). Sans ce filtre, l'odo affiche
        // 1 677 721 km et pollue ensuite la DB Room.
        val rawOdo = beU24(d, 2)
        val rawCoolant = d[1].toInt() and 0xFF
        val rawTExt = d[6].toInt() and 0xFF
        val b7 = d[7].toInt()
        return s.copy(
            contact = keyPos != 0,
            keyPosition = keyEnum,
            // Sentinel 0xFF sur les températures = capteur déconnecté / pas
            // encore lu. Calibration C2 (-53/-102) appliquée ensuite.
            tCoolant = if (rawCoolant == 0xFF) null else rawCoolant - 53,
            odo = if (rawOdo == 0xFFFFFF) null else rawOdo / 10.0,
            tExt = if (rawTExt == 0xFF) null else rawTExt - 102,
            reverseGear = (b7 and 0x80) != 0,
            wiperActive = (b7 and 0x40) != 0,
        )
    }

    private fun decode128(s: VehicleState, d: ByteArray): VehicleState {
        if (d.size < 8) return s
        val b0 = d[0].toInt(); val b1 = d[1].toInt(); val b2 = d[2].toInt()
        val b4 = d[4].toInt()
        val gearCmbCode = (d[6].toInt() shr 4) and 0x0F
        return s.copy(
            handbrake = (b0 and 0x20) != 0,
            warningsOn = (b2 and 0x02) != 0,
            fuelLowWarning = (b0 and 0x10) != 0,
            lights = Lights(
                drl              = (b4 and 0x01) != 0,
                clignoG          = (b4 and 0x02) != 0,
                clignoD          = (b4 and 0x04) != 0,
                antibrouillardAr = (b4 and 0x08) != 0,
                antibrouillardAv = (b4 and 0x10) != 0,
                feuxRoute        = (b4 and 0x20) != 0,
                feuxCroisement   = (b4 and 0x40) != 0,
                feuxPosition     = (b4 and 0x80) != 0,
            ),
            warn = s.warn.copy(
                fuelLow              = (b0 and 0x10) != 0,
                driverBelt           = (b0 and 0x40) != 0,
                passengerBelt        = (b0 and 0x02) != 0,
                passengerAirbagOff   = (b0 and 0x80) != 0,
                dieselPreheat        = (b0 and 0x04) != 0,
                absActive            = (b1 and 0x02) != 0,
                doorOpenAbove10      = (b1 and 0x08) != 0,
                doorOpenBelow10      = (b1 and 0x10) != 0,
                serviceExclamation   = (b1 and 0x80) != 0,
                warningActive        = (b2 and 0x02) != 0,
                espInProgress        = (b2 and 0x08) != 0,
                espInactivated       = (b2 and 0x10) != 0,
            ),
            gearCmb = if (gearCmbCode != 15) GEARS[gearCmbCode] else null,
        )
    }

    private fun decode161(s: VehicleState, d: ByteArray): VehicleState {
        if (d.size < 7) return s
        // 0x00 = pas encore lu, 0xFF = sentinelle capteur (comme T° moteur/ext).
        val rawOil = d[2].toInt() and 0xFF
        val tOil = if (rawOil == 0x00 || rawOil == 0xFF) null else rawOil - 64
        val fuelPct = d[3].toInt() and 0xFF
        val oilPct = d[6].toInt() and 0xFF
        return s.copy(
            tOil = tOil,
            fuelPct = fuelPct,
            fuelLitersEst = if (fuelPct > 0) round1(fuelPct / 100.0 * CanIds.FUEL_TANK_LITERS) else null,
            oilLevelPct = oilPct,
            oilLevelLitersEst = if (oilPct > 0) round2(oilPct / 100.0 * CanIds.OIL_CAPACITY_LITERS) else null,
        )
    }

    private fun decode167(s: VehicleState, d: ByteArray): VehicleState {
        if (d.size < 4) return s
        val pageCode = d[0].toInt() and 0x07
        val page = when (pageCode) {
            0 -> EmfPage.NONE
            1 -> EmfPage.GENERAL
            2 -> EmfPage.TRIP1
            4 -> EmfPage.TRIP2
            7 -> EmfPage.NOT_MGD
            else -> EmfPage.UNKNOWN
        }
        return s.copy(emfPage = page, tripDistTotal = beU16(d, 2))
    }

    private fun decode168(s: VehicleState, d: ByteArray): VehicleState {
        if (d.size < 2) return s
        val b0 = d[0].toInt(); val b1 = d[1].toInt()
        return s.copy(
            warn = s.warn.copy(
                oilPressureAlert  = (b0 and 0x08) != 0,
                oilLevelAlert     = (b0 and 0x10) != 0,
                coolantLevelAlert = (b0 and 0x20) != 0,
                oilTempMax        = (b0 and 0x40) != 0,
                coolantTempMax    = (b0 and 0x80) != 0,
                brakeFluidAlert   = (b0 and 0x04) != 0,
                fapClogged        = (b1 and 0x10) != 0,
                tyrePressureLow   = (b1 and 0x80) != 0,
                tyrePunctured     = (b1 and 0x40) != 0,
                maxRpm1           = (b1 and 0x04) != 0,
                maxRpm2           = (b1 and 0x01) != 0,
            ),
            wiperAuto = (b1 and 0x08) != 0,
        )
    }

    private fun decode21F(s: VehicleState, d: ByteArray): VehicleState {
        if (d.isEmpty()) return s
        val b0 = d[0].toInt() and 0xFF
        val newScroll = if (d.size > 1) d[1].toInt() and 0xFF else 0
        val prev = s.wheelScroll
        val delta = if (prev == null) 0
        else {
            val raw = (newScroll - prev) and 0xFF
            if (raw > 127) raw - 256 else raw
        }
        return s.copy(
            wheelButton = wheelButtonLabel(b0),
            wheelButtonRaw = b0,
            wheelScroll = newScroll,
            wheelScrollDelta = delta,
        )
    }

    private fun decode220(s: VehicleState, d: ByteArray): VehicleState {
        if (d.isEmpty()) return s
        val b = d[0].toInt()
        return s.copy(doors = Doors(
            avg          = (b and 0x80) != 0,
            avd          = (b and 0x40) != 0,
            arg          = (b and 0x20) != 0,
            ard          = (b and 0x10) != 0,
            coffre       = (b and 0x08) != 0,
            capot        = (b and 0x04) != 0,
            vitreAr      = (b and 0x02) != 0,
            trappeCarb   = (b and 0x01) != 0,
        ))
    }

    private fun decode221(s: VehicleState, d: ByteArray): VehicleState {
        if (d.size < 5) return s
        val rawCons  = beU16(d, 1)
        val rawRange = beU16(d, 3)
        return s.copy(
            fuelInst = if (rawCons == 0xFFFF) null else round1(rawCons / 10.0),
            rangeKm  = if (rawRange == 0xFFFF) null else rawRange,
        )
    }

    private fun decode261(s: VehicleState, d: ByteArray): VehicleState {
        if (d.size < 5) return s
        return s.copy(
            tripAvgSpeed = d[0].toInt() and 0xFF,
            tripDist     = round1(beU16(d, 2) / 10.0),
            tripAvgCons  = round1((d[4].toInt() and 0xFF) / 10.0),
        )
    }

    private fun decode276(s: VehicleState, d: ByteArray): VehicleState {
        if (d.size < 5) return s
        val y = 1872 + (d[0].toInt() and 0xFF)
        return s.copy(
            datetimeBsi = "%04d-%02d-%02d %02d:%02d".format(
                y, d[1].toInt() and 0xFF, d[2].toInt() and 0xFF,
                d[3].toInt() and 0xFF, d[4].toInt() and 0xFF
            )
        )
    }

    private fun decode3A7(s: VehicleState, d: ByteArray): VehicleState {
        if (d.size < 7) return s
        return s.copy(
            maintDue           = (d[0].toInt() and 0x80) != 0,
            maintKmRemaining   = beU16(d, 3),
            maintDaysRemaining = beU16(d, 5),
        )
    }

    /** Libellé des boutons appuyés (null au repos : pas d'allocation par trame). */
    private fun wheelButtonLabel(b0: Int): String? {
        if ((b0 and 0xCE) == 0) return null
        val btns = mutableListOf<String>()
        when {
            (b0 and 0x04) != 0 && (b0 and 0x08) != 0 -> btns += "MUTE"
            (b0 and 0x08) != 0 -> btns += "VOL+"
            (b0 and 0x04) != 0 -> btns += "VOL-"
        }
        if ((b0 and 0x02) != 0) btns += "SRC"
        if ((b0 and 0x40) != 0) btns += "◀ PREV"
        if ((b0 and 0x80) != 0) btns += "NEXT ▶"
        return btns.joinToString(" ")
    }

    private val GEARS = arrayOf("P","R","N","D","6","5","4","3","2","1","-","-","-","-","-","?")

    // ── Helpers ──
    private fun beU16(d: ByteArray, offset: Int) =
        ((d[offset].toInt() and 0xFF) shl 8) or (d[offset + 1].toInt() and 0xFF)
    private fun beU24(d: ByteArray, offset: Int) =
        ((d[offset].toInt() and 0xFF) shl 16) or
        ((d[offset + 1].toInt() and 0xFF) shl 8) or
        (d[offset + 2].toInt() and 0xFF)
    // Arrondi au plus proche (et non troncature) : même résultat que round() en Python.
    private fun round1(v: Double) = Math.round(v * 10) / 10.0
    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
