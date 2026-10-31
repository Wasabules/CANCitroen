package com.geoffrey.cancitroen.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Simulation acoustique d'un moteur, sans dépendance à Android.
 *
 * Un vrai moteur n'a rien de périodique : chaque explosion diffère de la précédente
 * et l'échappement est turbulent. On suit donc le trajet du gaz : chaque cylindre
 * garde sa propre pression, qui se vide à l'ouverture de la soupape (durée fixée
 * par l'alésage et la course), remonte son collecteur, rejoint le tube puis le
 * silencieux. S'y ajoutent l'admission et les bruits mécaniques.
 *
 * Calibrée sur de vrais enregistrements (voir docs/app/architecture.md). Tout évolue
 * échantillon par échantillon : le résultat ne dépend pas de la taille des blocs.
 */
class EngineSoundCore(private val sampleRate: Int, seed: Int = 0x2F6E2B1) {

    /** Lus au rythme de contrôle, écrits par le thread du véhicule. */
    @Volatile var profile: EngineProfile = EngineProfiles.FOUR_CYL_SPORT
    @Volatile var targetRpm: Float = 0f
    @Volatile var volume: Float = 0.7f

    private val sr = sampleRate.toFloat()
    private var active = profile
    private var rng = if (seed == 0) 1 else seed

    // ── Contrôle (tous les CONTROL_SAMPLES) ──
    private var controlLeft = 0
    private val controlDt = CONTROL_SAMPLES / sr
    private var rpm = 0f
    private var rpmInput = 0f
    private var slowRpm = 0f
    private var load = LOAD_CRUISE
    private var rpmFrac = 0f
    private var phaseInc = 0f
    private var gain = 0f
    private var gainStep = 0f
    private var eventBus = 0f
    private var eventGain = 0f
    private var eventGainStep = 0f
    private var overrun = 0f
    private var overrunSeconds = 0f
    private var blockEnergy = 0f
    private var levelAge = 0f
    private var levelEnergy = INITIAL_LEVEL * INITIAL_LEVEL
    private var levelGain = LEVEL_TARGET / INITIAL_LEVEL
    private var wobble = 0f
    private var openPressure = 0f
    private var bodyGain = 1f
    private var hashLevel = 0f

    // ── Cylindres ──
    private var phase = 0f
    private var cylinders = 0
    private var firing = FloatArray(0)
    private var firingGain = FloatArray(0)
    private var pressure = FloatArray(0)
    private var timing = FloatArray(0)
    private var nextTiming = FloatArray(0)
    private var exhaustOpen = BooleanArray(0)
    private var intakeOpen = BooleanArray(0)
    private var header = emptyArray<FloatArray>()
    private var headerDelay = IntArray(0)
    private var headerSlot = IntArray(0)
    private var blowRate = 0f
    private var backStroke = 0f

    // ── Échappement ──
    private var lastCollector = 0f
    private val pipe = FloatArray((sr * MAX_PIPE_S).toInt() + 2)
    private var pipeWrite = 0
    private var pipeDelay = 1
    private var pipeDamp = 0f
    private var chambers = emptyArray<AllPass>()
    private val muffler = Svf()
    private val bodyLow = Svf()
    private val turbulence = Svf()
    private val rumble = Svf()

    // ── Admission et mécanique ──
    private val intakeBand = Svf()
    private val hashBand = Svf()
    private val valveBand = Svf()
    private val valveBandHigh = Svf()
    private var valveEnv = 0f
    private val valveDecay = exp(-1f / (0.0009f * sr))
    private val knockBand = Svf()
    private var knockEnv = 0f
    private val knockDecay = exp(-1f / (0.0022f * sr))

    // ── Habitacle et sortie ──
    private val room = arrayOf(AllPass(sr, 0.0031f, 0.55f), AllPass(sr, 0.0073f, 0.5f), AllPass(sr, 0.0127f, 0.45f))
    private val tone = Svf()
    private val highPass = Svf()
    private var limiterPeak = 0f
    private var limiterGain = 1f
    private val limiterRelease = exp(-1f / (0.12f * sr))
    private val limiterAttack = 1f - exp(-1f / (0.0005f * sr))
    private val limiterRecover = 1f - exp(-1f / (0.08f * sr))

    // ── Turbo ──
    private var boost = 0f
    private var turbine = 0f
    private var wastegate = 0f
    private var releaseEnv = 0f
    private var releaseRise = 0f
    private var flutterPhase = 0f
    private val dumpDecay = exp(-1f / (0.2f * sr))
    private val flutterDecay = exp(-1f / (0.42f * sr))
    private val whistleBand = Svf()
    private val airBand = Svf()
    private val wastegateBand = Svf()
    private val releaseBand = Svf()

    // ── Détonations ──
    private var popEnv = 0f
    private var popDecay = 0f
    private var popAttack = 1f
    private var popHeat = 0f
    private var popCooldown = 0f
    private val popBlast = Svf()
    private val popBody = Svf()
    private val popBodyHigh = Svf()

    init {
        highPass.set(28f, 0.707f, sr)
        turbulence.set(900f, 0.6f, sr)
        rumble.set(130f, 0.7f, sr)
        intakeBand.set(850f, 0.55f, sr)
        hashBand.set(5200f, 0.5f, sr)
        valveBand.set(3600f, 1.1f, sr)
        valveBandHigh.set(7400f, 1.4f, sr)
        knockBand.set(2100f, 1f, sr)
        whistleBand.set(1900f, 16f, sr)
        airBand.set(3200f, 0.7f, sr)
        wastegateBand.set(5200f, 1f, sr)
        releaseBand.set(2600f, 0.8f, sr)
        bodyLow.set(170f, 0.8f, sr)
        applyProfile(profile)
    }

    /** True quand la sortie est muette et le restera : la piste peut être mise en pause. */
    val isSilent: Boolean
        get() = gain < SILENCE_GAIN && (targetRpm < MIN_RPM || volume == 0f)

    fun render(out: FloatArray, count: Int = out.size) {
        for (i in 0 until count) out[i] = nextSample()
    }

    private fun nextSample(): Float {
        if (controlLeft == 0) {
            control()
            controlLeft = CONTROL_SAMPLES
        }
        controlLeft--
        val raw = if (active.electric) electricSample() else combustionSample()
        highPass.process(raw)
        val clean = raw - highPass.lp - highPass.k * highPass.bp
        blockEnergy += clean * clean
        val shaped = clean * gain + eventBus * eventGain
        eventGain += eventGainStep
        gain += gainStep
        return limit(shaped)
    }

    private fun control() {
        val p = profile
        val wanted = targetRpm
        val engineOn = wanted >= MIN_RPM && volume > 0f

        // Changement de profil : silence bref, pour ne pas mélanger deux états.
        val switching = p !== active
        if (switching && gain < SILENCE_GAIN) applyProfile(p)

        // Moteur qui démarre : directement au ralenti, sans montée parasite depuis zéro.
        if (rpm < MIN_RPM && engineOn) {
            rpm = min(wanted, p.idleRpm)
            rpmInput = rpm
            slowRpm = rpm
        }
        // Deux étages : le bus donne des marches, que le multiplicateur agrandit.
        rpmInput += (wanted - rpmInput) * smoothing(RPM_TAU_S)
        rpm += (rpmInput - rpm) * smoothing(RPM_TAU_S)
        val previousSlow = slowRpm
        slowRpm += (rpm - slowRpm) * smoothing(SLOPE_TAU_S)
        val slope = (slowRpm - previousSlow) / controlDt

        // Le régime qui monte trahit l'accélérateur, et il suffit sur la vraie
        // voiture, où la position de la pédale n'est pas lue sur le bus.
        val wantedLoad = (LOAD_CRUISE + slope / FULL_LOAD_RPM_PER_S).coerceIn(0f, 1f)
        load += (wantedLoad - load) * smoothing(if (wantedLoad > load) LOAD_ATTACK_S else LOAD_RELEASE_S)

        val a = active
        rpmFrac = ((rpm - a.idleRpm) / (a.redlineRpm - a.idleRpm)).coerceIn(0f, 1f)

        // Le moteur ne tourne jamais rond : lente dérive, plus marquée au ralenti.
        wobble += (random() * RPM_DRIFT - wobble) * smoothing(DRIFT_TAU_S)
        val drift = 1f + wobble * (1f - 0.7f * rpmFrac)
        phaseInc = max(rpm, 0f) * drift / 120f / sr

        // Pied levé, moteur lancé : l'injection se coupe, le moteur n'est plus qu'une pompe.
        val cutting = engineOn && wantedLoad < OVERRUN_LOAD && rpm > a.idleRpm * OVERRUN_MIN_IDLE_RATIO
        overrunSeconds = if (cutting) overrunSeconds + controlDt else 0f
        val overrunTarget = if (overrunSeconds > OVERRUN_DELAY_S) 1f else 0f
        overrun += (overrunTarget - overrun) * smoothing(OVERRUN_TAU_S)
        popHeat *= exp(-controlDt / POP_HEAT_TAU_S)
        popCooldown = max(popCooldown - controlDt, 0f)
        openPressure = (0.28f + 0.72f * load) * (1f - OVERRUN_PRESSURE_DROP * overrun)
        bodyGain = 1f + 0.5f * (1f - rpmFrac)
        hashLevel = (0.12f + 0.88f * sqrt(rpmFrac)) * (0.55f + 0.45f * load)

        val cutoff = a.exhaust.cutoffHz * (0.72f + 0.55f * load) * (0.7f + 0.5f * rpmFrac)
        muffler.set(min(cutoff, sr * 0.3f), 0.6f, sr)
        if (a.electric) tone.set(min(a.lowpassIdleHz + (a.lowpassRedlineHz - a.lowpassIdleHz) * rpmFrac, sr * 0.3f), 0.707f, sr)
        if (a.turbo) updateTurbo(wantedLoad)

        // Niveau automatique très lent : harmonise toutes les configurations possibles,
        // sans gommer une accélération. Figé tant que le moteur ne tourne pas.
        if (engineOn && !switching) {
            // Rapide au démarrage pour trouver le bon niveau, lent ensuite.
            levelAge += controlDt
            val levelTau = if (levelAge < LEVEL_SETTLE_S) LEVEL_SETTLE_TAU_S else LEVEL_TAU_S
            levelEnergy += (blockEnergy / CONTROL_SAMPLES - levelEnergy) * smoothing(levelTau)
            levelGain = (LEVEL_TARGET / sqrt(max(levelEnergy, 1e-8f))).coerceIn(LEVEL_GAIN_MIN, LEVEL_GAIN_MAX)
        }
        blockEnergy = 0f

        val gainTarget = when {
            !engineOn || switching -> 0f
            else -> volume * levelGain * a.exhaust.level * (0.72f + 0.28f * rpmFrac) * (0.62f + 0.38f * load)
        }
        val smoothed = gain + (gainTarget - gain) * smoothing(GAIN_TAU_S)
        gainStep = (smoothed - gain) / CONTROL_SAMPLES
        // Ces bruits ont leur propre niveau, indépendant du niveau automatique du moteur.
        val eventTarget = if (engineOn && !switching) volume else 0f
        val eventSmoothed = eventGain + (eventTarget - eventGain) * smoothing(GAIN_TAU_S)
        eventGainStep = (eventSmoothed - eventGain) / CONTROL_SAMPLES
    }

    private fun smoothing(tauSeconds: Float) = 1f - exp(-controlDt / tauSeconds)

    private fun applyProfile(p: EngineProfile) {
        active = p
        phase = 0f
        levelAge = 0f
        levelEnergy = INITIAL_LEVEL * INITIAL_LEVEL
        levelGain = LEVEL_TARGET / INITIAL_LEVEL
        pipe.fill(0f)
        pipeDamp = 0f
        cylinders = p.firing.size
        firing = p.firing
        firingGain = p.firingGain
        pressure = FloatArray(cylinders)
        timing = FloatArray(cylinders)
        nextTiming = FloatArray(cylinders)
        exhaustOpen = BooleanArray(cylinders)
        intakeOpen = BooleanArray(cylinders)
        headerDelay = IntArray(cylinders) { ((p.headerM[it] / SOUND_HOT_M_S) * sr).toInt().coerceAtLeast(1) }
        header = Array(cylinders) { FloatArray(headerDelay[it] + 1) }
        headerSlot = IntArray(cylinders)
        pipeDelay = ((2f * p.exhaust.pipeM / SOUND_HOT_M_S) * sr).toInt().coerceIn(1, pipe.size - 1)
        // Bouffée d'échappement : le cylindre se vide d'autant plus vite que la soupape
        // est grande face au volume à vider, soit une durée proportionnelle à la course.
        val blowdownSeconds = (p.strokeM / BLOWDOWN_SPEED_M_S).coerceAtLeast(0.0005f)
        blowRate = 1f / (blowdownSeconds * sr)
        backStroke = 0.12f * sqrt(max(p.boreM * p.boreM * p.strokeM, 1e-6f) / REFERENCE_CYLINDER)
        popBlast.set(p.exhaust.popCutoffHz, 0.7f, sr)
        popBody.set(p.popBodyHz, POP_BODY_Q, sr)
        popBodyHigh.set(p.popBodyHz * 2.3f, POP_BODY_HIGH_Q, sr)
        chambers = Array(p.exhaust.chambers) { AllPass(sr, CHAMBER_S[it], CHAMBER_G[it]) }
    }

    private fun combustionSample(): Float {
        val p = active
        eventBus = 0f
        phase += phaseInc
        if (phase >= 1f) phase -= 1f

        var collector = 0f
        var jet = 0f
        var intake = 0f
        var valve = false
        for (c in 0 until cylinders) {
            var theta = phase + firing[c] + timing[c]
            if (theta >= 1f) theta -= 1f
            if (theta < 0f) theta += 1f

            var flow = 0f
            if (theta >= EVO && theta < EVC) {
                if (!exhaustOpen[c]) {
                    exhaustOpen[c] = true
                    // Aucune combustion n'est identique à la précédente.
                    val spread = 1f + COMBUSTION_SPREAD * (random() + random()) * (1f - 0.5f * rpmFrac)
                    pressure[c] = max(openPressure * firingGain[c] * spread, 0.02f)
                    valve = true
                    nextTiming[c] = random() * TIMING_SPREAD
                    maybePop(p)
                    if (p.diesel) knockEnv = max(knockEnv, spread)
                }
                val lift = sinFast((theta - EVO) / (EVC - EVO) * 0.5f)
                val escape = pressure[c] * lift
                pressure[c] = max(pressure[c] - escape * blowRate, 0f)
                // La bouffée, puis le piston qui chasse ce qui reste.
                flow = escape + backStroke * lift * openPressure
            } else if (exhaustOpen[c]) {
                exhaustOpen[c] = false
                valve = true
            }

            if (theta >= IVO && theta < IVC) {
                if (!intakeOpen[c]) {
                    intakeOpen[c] = true
                    valve = true
                }
                intake += sinFast((theta - IVO) / (IVC - IVO) * 0.5f)
            } else if (intakeOpen[c]) {
                intakeOpen[c] = false
                valve = true
                // Soupapes fermées : on peut décaler la prochaine explosion sans à-coup.
                timing[c] = nextTiming[c]
            }

            val line = header[c]
            val delay = headerDelay[c]
            val slot = headerSlot[c]
            val next = if (slot == delay) 0 else slot + 1
            collector += line[next]
            line[slot] = flow
            headerSlot[c] = next
            jet += flow * flow
        }

        // Turbulences : la vraie source du grain, proportionnelle au débit.
        turbulence.process(random())
        // Un tube rayonne les variations de débit : c'est ce qui claque dans les médiums.
        // Légèrement adouci : une onde de pression n'a pas d'arête vive.
        val softened = lastCollector + (collector - lastCollector) * RADIATION_SOFTEN
        val radiated = (softened - lastCollector) * RADIATION
        lastCollector = softened
        val detonation = popSample(p)
        val noisy = collector + radiated + detonation + turbulence.bp * sqrt(jet) * JET_NOISE * (0.5f + 0.5f * load)

        // Tube : une part de l'onde revient, amortie, et se mêle à la suivante.
        var read = pipeWrite - pipeDelay
        if (read < 0) read += pipe.size
        pipeDamp += 0.3f * (pipe[read] - pipeDamp)
        val inPipe = softDrive(noisy) - p.exhaust.reflection * pipeDamp
        pipe[pipeWrite] = inPipe
        pipeWrite = if (pipeWrite + 1 == pipe.size) 0 else pipeWrite + 1

        var outlet = inPipe
        for (i in chambers.indices) outlet = chambers[i].process(outlet)
        muffler.process(outlet)
        bodyLow.process(outlet)
        var mix = muffler.lp * EXHAUST_LEVEL + bodyLow.lp * BODY_LEVEL * bodyGain

        // Grondement : gaz et carrosserie, sans rythme propre.
        rumble.process(random())
        mix += rumble.lp * RUMBLE_LEVEL * (0.4f + 0.6f * load) * (0.5f + 0.5f * rpmFrac)

        intakeBand.process(random())
        mix += intakeBand.bp * intake * INTAKE_LEVEL * (0.2f + 0.8f * load) * (0.4f + 0.6f * rpmFrac)

        // Mécanique : souffle aigu du moteur, et claquements de soupapes.
        hashBand.process(random())
        mix += hashBand.bp * HASH_LEVEL * hashLevel
        if (valve) valveEnv = 1f
        val tick = random() * valveEnv
        valveBand.process(tick)
        valveBandHigh.process(tick)
        mix += (valveBand.bp + 0.7f * valveBandHigh.bp) * VALVE_LEVEL * (0.35f + 0.65f * rpmFrac)
        valveEnv *= valveDecay

        if (p.diesel) {
            knockBand.process(random())
            mix += knockBand.bp * knockEnv * DIESEL_KNOCK * (1f - 0.5f * load)
            knockEnv *= knockDecay
        }
        if (p.turbo) turboSample(p)

        var wet = mix
        for (i in room.indices) wet = room[i].process(wet)
        return mix * (1f - ROOM_MIX) + wet * ROOM_MIX
    }

    /** Le gaz sature doucement : mêle les raies entre elles, comme un vrai tube. */
    private fun softDrive(x: Float): Float {
        val y = x * DRIVE
        return y / (1f + abs(y) * 0.45f)
    }

    /** Du carburant imbrûlé s'enflamme dans le tube : d'autant plus juste après le lever de pied. */
    private fun maybePop(p: EngineProfile) {
        if (p.pops == PopLevel.OFF || overrun < 0.5f || rpm < POP_MIN_RPM || popCooldown > 0f) return
        val firingsPerSecond = max(rpm / 120f * cylinders, 1f)
        val freshness = exp(-overrunSeconds / POP_WINDOW_S)
        val rate = p.pops.popsPerSecond * p.exhaust.popRate * (0.1f + 0.9f * freshness) * (0.5f + 0.5f * rpmFrac) * (1f + popHeat)
        val roll = random() * 0.5f + 0.5f
        if (roll >= rate / firingsPerSecond) return
        // Une détonation est franche : pas de poussière de petits craquements.
        val draw = random() * 0.5f + 0.5f
        val strength = if (p.pops == PopLevel.STRONG) 0.75f else 0.55f
        popEnv = (0.55f + 0.45f * draw * draw) * strength * p.popForce
        popAttack = 0f
        popDecay = exp(-1f / ((p.exhaust.popDecayS * (0.7f + 0.6f * draw)) * sr))
        popHeat = min(popHeat + 0.5f, 1f)
        popCooldown = POP_MIN_GAP_S
    }

    /**
     * Une onde de pression qui fait résonner le tube, pas un claquement : attaque
     * douce, corps grave. Le bruit du souffle reste filtré selon l'échappement.
     * Renvoie la part qui remonte la ligne ; l'essentiel sort par le bus d'événements.
     */
    private fun popSample(p: EngineProfile): Float {
        if (popEnv < 1e-3f && abs(popBody.bp) < 1e-4f) return 0f
        popAttack = min(popAttack + 1f / (POP_ATTACK_S * sr), 1f)
        val envelope = popEnv * popAttack * (2f - popAttack) // arrondie : aucune arête au début
        popBlast.process(random() * envelope)
        val pressure = popBlast.lp
        popBody.process(pressure + envelope * POP_PUSH)
        popBodyHigh.process(pressure)
        popEnv *= popDecay
        eventBus += (popBody.bp * POP_BODY + popBodyHigh.bp * POP_BODY_HIGH + pressure * POP_BLAST) * p.exhaust.popOpening
        return pressure * POP_IN_PIPE
    }

    /** Pression : monte avec retard sous charge. Turbine : continue sur son élan pied levé. */
    private fun updateTurbo(wantedLoad: Float) {
        val pressure = (sqrt(rpmFrac) * ((load - 0.3f) / 0.7f)).coerceIn(0f, 1f)
        if (wantedLoad < OVERRUN_LOAD && boost > TURBO_RELEASE_MIN_BOOST && releaseEnv < 0.05f) {
            releaseEnv = boost
            releaseRise = 0f
        }
        // Régime stable ne veut pas dire pied levé : la pression ne s'effondre qu'au lever de pied.
        val boostTau = when {
            pressure > boost -> TURBO_SPOOL_S
            wantedLoad < OVERRUN_LOAD -> TURBO_FALL_S
            else -> TURBO_HOLD_S
        }
        boost += (pressure - boost) * smoothing(boostTau)
        val turbineTarget = max(boost, 0.06f * rpmFrac)
        turbine += (turbineTarget - turbine) * smoothing(if (turbineTarget > turbine) TURBINE_UP_S else TURBINE_DOWN_S)
        whistleBand.set(1900f + 6300f * turbine, 16f, sr)
        wastegate += (((boost - 0.8f) / 0.2f).coerceIn(0f, 1f) * load - wastegate) * smoothing(0.12f)
        val releaseHz = if (active.turboRelease == TurboRelease.FLUTTER) 1250f else 1800f + 2000f * releaseEnv
        releaseBand.set(releaseHz, 0.8f, sr)
    }

    private fun turboSample(p: EngineProfile) {
        whistleBand.process(random())
        airBand.process(random())
        var out = whistleBand.bp * turbine * turbine * TURBO_WHISTLE * (0.35f + 0.65f * load)
        out += airBand.bp * boost * boost * TURBO_AIR
        if (wastegate > 1e-3f) {
            flutterPhase += WASTEGATE_RATTLE_HZ / sr
            wastegateBand.process(random())
            out += wastegateBand.bp * wastegate * TURBO_WASTEGATE * (0.72f + 0.28f * sinFast(flutterPhase))
        }
        if (releaseEnv > 1e-3f) {
            releaseRise = min(releaseRise + RELEASE_ATTACK, 1f)
            releaseBand.process(random())
            val shape = if (p.turboRelease == TurboRelease.FLUTTER) {
                // La pression repasse par le compresseur par saccades, de plus en plus lentes.
                flutterPhase += (FLUTTER_LOW_HZ + FLUTTER_SPAN_HZ * releaseEnv) / sr
                val beat = 0.5f + 0.5f * sinFast(flutterPhase)
                beat * beat
            } else {
                1f
            }
            out += releaseBand.bp * releaseEnv * releaseRise * shape * TURBO_RELEASE
            releaseEnv *= if (p.turboRelease == TurboRelease.FLUTTER) flutterDecay else dumpDecay
        }
        eventBus += out
    }

    private fun electricSample(): Float {
        phase += phaseInc * 2f
        if (phase >= 1f) phase -= 1f
        // Le rotor et l'onduleur : quelques raies pures, donc aucun repliement.
        val pole = sinFast(phase * 4f)
        val second = sinFast(phase * 8f)
        val inverter = sinFast(phase * 12f)
        val body = 0.42f * pole + 0.16f * second + (0.05f + 0.12f * load) * inverter
        turbulence.process(random())
        tone.process((body * (0.5f + 0.5f * load) + turbulence.lp * 0.03f * (0.4f + 0.6f * load)) * EV_LEVEL)
        return tone.lp
    }

    /** Limiteur à gain variable : garde la forme d'onde, là où un écrêteur grésille. */
    private fun limit(x: Float): Float {
        limiterPeak = max(abs(x), limiterPeak * limiterRelease)
        val wanted = if (limiterPeak > LIMIT_CEILING) LIMIT_CEILING / limiterPeak else 1f
        limiterGain += (wanted - limiterGain) * (if (wanted < limiterGain) limiterAttack else limiterRecover)
        val y = x * limiterGain
        val a = abs(y)
        // Dernier filet pour la milliseconde d'attaque du limiteur.
        return if (a <= SOFT_KNEE) y else sign(y) * (SOFT_KNEE + KNEE_RANGE * tanh((a - SOFT_KNEE) / KNEE_RANGE))
    }

    /** Sinus d'une phase exprimée en tours, par table interpolée. */
    private fun sinFast(turns: Float): Float {
        val position = (turns - floor(turns)) * SINE_SIZE
        val index = position.toInt()
        val t = position - index
        return SINE[index] + (SINE[index + 1] - SINE[index]) * t
    }

    /** Dans [-1, 1[. */
    private fun random(): Float {
        var x = rng
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rng = x
        return x * 4.656613e-10f
    }

    /** Filtre d'état à deux pôles (trapézoïdal) : stable quand sa fréquence bouge. */
    private class Svf {
        var lp = 0f
        var bp = 0f
        var k = 1f
        private var g = 0f
        private var a1 = 0f
        private var ic1 = 0f
        private var ic2 = 0f

        fun set(hz: Float, q: Float, sampleRate: Float) {
            g = tan(PI * hz / sampleRate).toFloat()
            k = 1f / q
            a1 = 1f / (1f + g * (g + k))
        }

        fun process(x: Float) {
            val v3 = x - ic2
            val v1 = a1 * (ic1 + g * v3)
            val v2 = ic2 + g * v1
            ic1 = 2f * v1 - ic1
            ic2 = 2f * v2 - ic2
            bp = v1
            lp = v2
        }
    }

    /** Disperse l'onde sans la colorer : une chambre de silencieux, ou l'écho de l'habitacle. */
    private class AllPass(sampleRate: Float, seconds: Float, private val g: Float) {
        private val line = FloatArray((sampleRate * seconds).toInt().coerceAtLeast(1))
        private var at = 0

        fun process(x: Float): Float {
            val delayed = line[at]
            val input = x + delayed * g
            line[at] = input
            at = if (at + 1 == line.size) 0 else at + 1
            return delayed - input * g
        }
    }

    private companion object {
        const val CONTROL_SAMPLES = 32
        const val MIN_RPM = 100f
        const val SILENCE_GAIN = 1e-4f
        const val RPM_TAU_S = 0.035f
        const val SLOPE_TAU_S = 0.12f
        const val GAIN_TAU_S = 0.05f
        const val DRIFT_TAU_S = 0.25f
        const val RPM_DRIFT = 0.03f
        const val LOAD_CRUISE = 0.3f
        const val LOAD_ATTACK_S = 0.07f
        const val LOAD_RELEASE_S = 0.25f
        const val FULL_LOAD_RPM_PER_S = 1200f
        const val LIMIT_CEILING = 0.85f
        const val SOFT_KNEE = 0.9f
        const val KNEE_RANGE = 0.08f

        // Calage des soupapes dans le cycle de 720°, à partir de l'explosion.
        const val EVO = 130f / 720f
        const val EVC = 370f / 720f
        const val IVO = 350f / 720f
        const val IVC = 590f / 720f

        const val SOUND_HOT_M_S = 520f
        const val BLOWDOWN_SPEED_M_S = 110f
        const val REFERENCE_CYLINDER = 0.084f * 0.084f * 0.09f
        const val COMBUSTION_SPREAD = 0.24f
        const val TIMING_SPREAD = 0.0025f
        const val MAX_PIPE_S = 0.03f
        val CHAMBER_S = floatArrayOf(0.0041f, 0.0089f, 0.0173f)
        val CHAMBER_G = floatArrayOf(0.5f, 0.55f, 0.5f)

        const val LEVEL_TARGET = 0.2f
        const val LEVEL_SETTLE_S = 1.2f
        const val LEVEL_SETTLE_TAU_S = 0.3f
        const val LEVEL_TAU_S = 2.5f
        const val INITIAL_LEVEL = 0.03f
        const val LEVEL_GAIN_MIN = 0.2f
        const val LEVEL_GAIN_MAX = 12f
        const val RUMBLE_LEVEL = 0.1f
        const val RADIATION = 42f
        const val RADIATION_SOFTEN = 0.5f
        const val DRIVE = 1.4f
        const val EXHAUST_LEVEL = 0.6f
        const val BODY_LEVEL = 0.26f
        const val JET_NOISE = 0.4f
        const val INTAKE_LEVEL = 0.08f
        const val HASH_LEVEL = 0.005f
        const val VALVE_LEVEL = 0.018f
        const val DIESEL_KNOCK = 0.07f
        const val ROOM_MIX = 0.22f
        const val EV_LEVEL = 1f
        const val OVERRUN_LOAD = 0.14f
        const val OVERRUN_MIN_IDLE_RATIO = 1.35f
        const val OVERRUN_DELAY_S = 0.08f
        const val OVERRUN_TAU_S = 0.05f
        const val OVERRUN_PRESSURE_DROP = 0.72f
        const val POP_MIN_RPM = 1800f
        const val POP_WINDOW_S = 1.1f
        const val POP_HEAT_TAU_S = 0.35f
        const val POP_MIN_GAP_S = 0.09f
        const val POP_ATTACK_S = 0.0022f
        const val POP_PUSH = 0.5f
        const val POP_BODY_Q = 13f
        const val POP_BODY_HIGH_Q = 5f
        const val POP_BODY = 2.2f
        const val POP_BODY_HIGH = 0.5f
        const val POP_BLAST = 0.65f
        const val POP_IN_PIPE = 0.12f
        const val TURBO_SPOOL_S = 0.75f
        const val TURBO_FALL_S = 0.22f
        const val TURBO_HOLD_S = 2.5f
        const val TURBINE_UP_S = 0.9f
        const val TURBINE_DOWN_S = 1.5f
        const val TURBO_RELEASE_MIN_BOOST = 0.3f
        const val TURBO_WHISTLE = 0.12f
        const val TURBO_AIR = 0.05f
        const val TURBO_WASTEGATE = 0.09f
        const val TURBO_RELEASE = 0.42f
        const val WASTEGATE_RATTLE_HZ = 31f
        const val RELEASE_ATTACK = 0.004f
        const val FLUTTER_LOW_HZ = 10f
        const val FLUTTER_SPAN_HZ = 16f
        const val SINE_SIZE = 2048
        val SINE = FloatArray(SINE_SIZE + 1) { sin(2.0 * PI * it / SINE_SIZE).toFloat() }
    }
}
