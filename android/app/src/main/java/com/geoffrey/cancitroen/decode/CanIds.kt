package com.geoffrey.cancitroen.decode

/** Tous les IDs CAN AEE2004 utilisés par l'app, calibrés sur Citroën C2. */
object CanIds {
    // Lecture (RX) — état véhicule
    const val ID_BSI            = 0x036  // ignition mode, eco mode, brightness
    const val ID_RPM_SPEED      = 0x0B6  // RPM, vitesse, dist trip CMB
    const val ID_DASH1          = 0x0F6  // T° moteur, odo, T° ext, marche AR
    const val ID_LIGHTS         = 0x128  // feux, frein à main, témoins combiné
    const val ID_FUEL_OIL       = 0x161  // T° huile, niveau carburant %, niveau huile %
    const val ID_EMF_STATE      = 0x167  // page EMF courante, total distance
    const val ID_DASH3          = 0x168  // alertes critiques moteur (oil/coolant/FAP/pneus)
    const val ID_RADIO_REMOTE   = 0x21F  // commandes au volant (VOL+/-, SOURCE, SEEK+/-)
    const val ID_DOORS          = 0x220  // portes, capot, coffre, vitre AR, trappe carb.
    const val ID_CONS_RANGE     = 0x221  // conso instantanée + autonomie
    const val ID_TRIP1          = 0x261  // trip slot 1 (vit moy, dist, conso moy)
    const val ID_TRIP2          = 0x2A1  // trip slot 2
    const val ID_DATETIME       = 0x276  // date BSI broadcast (rare sur C2)
    const val ID_MAINTENANCE    = 0x3A7  // maintenance (km/jours avant entretien)

    // Émission (TX)
    const val ID_SET_CLOCK_BSI  = 0x39B  // 5 bytes : année-1872, mois, jour, heure, minute
    const val ID_EMF_BUTTONS    = 0x3E5  // 6 bytes : boutons EMF (PSAWifiDisplayControl)

    // ── Constantes de calibration véhicule (C2 1.1L TU1JP) ──
    // Capacité officielle Citroën C2 = 41 L (cohérent avec FuelSettings).
    const val FUEL_TANK_LITERS   = 41.0
    const val OIL_CAPACITY_LITERS = 3.0  // vidange + filtre TU1JP
}
