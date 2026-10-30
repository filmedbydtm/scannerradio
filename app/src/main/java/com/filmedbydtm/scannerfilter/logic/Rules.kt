package com.filmedbydtm.scannerfilter.logic

/**
 * What happens to a call that matches a category.
 *
 *  NEVER    - never played, wins over everything else.
 *  PLAY_HARD- played unedited; also wins over MEDICAL (e.g. a GSW call that mentions EMS).
 *  MEDICAL  - never played, unless a PLAY_HARD category also matched.
 *  PLAY     - played unedited (location-style matches such as gas stations).
 *  REDACT   - played only after numbers / birth dates are bleeped; low priority, expires.
 */
enum class Bucket(val token: String) {
    NEVER("NEVER"),
    PLAY_HARD("PLAY+"),
    MEDICAL("MEDICAL"),
    PLAY("PLAY"),
    REDACT("REDACT");

    companion object {
        fun fromToken(t: String): Bucket? = entries.firstOrNull { it.token.equals(t.trim(), ignoreCase = true) }
    }
}

data class Category(val bucket: Bucket, val name: String, val keywords: List<String>)

object Rules {

    /**
     * Rules are stored as editable text, one category per line:
     *   BUCKET | Category name | keyword, keyword phrase, ...
     * Keywords are matched as whole words/phrases against the lower-cased transcript.
     */
    val DEFAULT_TEXT = """
# BUCKET | Category | keywords (comma separated, whole words/phrases)
# Buckets: NEVER, PLAY+ (beats MEDICAL), MEDICAL, PLAY, REDACT
# Order of checks: under-2s, phone numbers, NEVER, PLAY+, MEDICAL, PLAY, REDACT, otherwise ignored.

NEVER | Child abuse / abduction | child abuse, child neglect, abuse, abduction, abducted, abduct, kidnap, kidnapped, kidnapping, amber alert, missing child, missing juvenile, juvenile, child, children, infant, molest, molestation, csc
NEVER | Mental illness | mental, mentally, mental illness, suicidal, suicide, emotionally disturbed, edp, psych, psychiatric, crisis, bipolar, schizophrenic, schizophrenia, off their meds, petition, danger to self
NEVER | Shift change | shift change, change of shift, shift, roll call, day shift, night shift, afternoon shift, midnight shift, relieved
NEVER | Login issues | log in, login, logged in, logging in, log on, logon, log off, logged off, sign on, signed on, password, mdt, computer down, terminal, can't log, cannot log
NEVER | Phone numbers | phone number, phone numbers, callback, call back, callback number, contact number, cell number, cell phone number, area code
NEVER | Hang-up calls | hang up, hang ups, hangup, hung up, nine one one hang, open line, abandoned call, disconnected call
NEVER | Special attention locations | special attention
NEVER | Equipment checks | equipment check, radio check, mic check, signal check, how do you copy, testing, test, check my radio, car check
NEVER | Supervisor requests | request a supervisor, requesting a supervisor, need a supervisor, send a supervisor, supervisor to, supervisor request, supervisor requesting, have a supervisor, get a supervisor
NEVER | Sergeant requests | request a sergeant, requesting a sergeant, need a sergeant, send a sergeant, sergeant to, sergeant request, sergeant requesting, have a sergeant, get a sergeant, sarge

PLAY+ | Car accident | accident, accidents, pi accident, personal injury accident, crash, car crash, vehicle accident, auto accident, rollover, hit and run, mva, vehicle into
PLAY+ | ShotSpotter | shot spotter, shotspotter, shot spotters, spotter
PLAY+ | Shots fired | shots fired, shots, shooting, shot fired, gun fire, gunfire, shooter
PLAY+ | Structure fire | structure fire, house fire, building fire, dwelling fire, commercial fire, vacant fire, garage fire, working fire, fully involved, smoke showing, fire showing, flames showing, box alarm, fire in the building, fire in the house
PLAY+ | DDC | ddc, d d c, detention center, detroit detention
PLAY+ | DUI | dui, d u i, owi, o w i, drunk driver, drunk driving, intoxicated driver, impaired driver
PLAY+ | Animal control / loose dogs | animal control, loose dog, loose dogs, dog loose, dogs loose, stray dog, stray dogs, vicious dog, dog bite, dog bit, pit bull, pitbull
PLAY+ | GSW | gsw, g s w, gunshot, gunshot wound, gun shot wound, gun shot, person shot, been shot, got shot, shot in the
PLAY+ | Pursuit / chase | pursuit, chase, chasing, high speed, fleeing, fleeing and eluding, fled, eluding, foot pursuit, vehicle pursuit, bailed out
PLAY+ | Narcotics use | narcotics, narcotic, drugs, drug, drug activity, using drugs, smoking crack, crack, heroin, fentanyl, cocaine, meth, dope, needle, needles, pills, weed, marijuana
PLAY+ | Use of force | use of force, force used, taser, tased, tasered, pepper spray, o c spray, takedown, resisting, resisted

MEDICAL | Medical | medical, ems, medic, ambulance, unconscious, unresponsive, not breathing, difficulty breathing, trouble breathing, breathing, chest pain, cardiac, heart attack, seizure, seizures, overdose, overdosed, o d, diabetic, stroke, sick, fall, fell, injured, bleeding, pregnant, labor, allergic, cpr, doa, hospital

PLAY | Greenlight locations | green light, greenlight, project green light, green lights
PLAY | Gas stations | gas station, gas stations, gas, marathon, mobil, mobile station, sunoco, speedway, citgo, valero, bp, shell station, fuel
PLAY | Party / liquor stores | party store, party stores, liquor store, liquor stores, liquor, beer and wine

REDACT | Intoxicated persons | intoxicated, intoxicated person, drunk, intox, drunk person, public intoxication
REDACT | Breaking and entering | b and e, b e, breaking and entering, break in, breaking in, broke in, burglary, burglar
REDACT | Assault and battery | a and b, a b, assault and battery, assault, battery
REDACT | Felonious assault | felonious assault, felonious, f a
REDACT | Noise complaints | noise complaint, noise, loud music, loud party, loud
REDACT | Unknown problems | unknown problem, unknown trouble, unknown
REDACT | Person descriptions | description, described as, wearing, black male, white male, black female, white female, hispanic male, hispanic female, male, female, hoodie, jacket, pants, shirt, dreads, braids, complexion, build
REDACT | Missing persons | missing person, missing persons, missing, runaway, walked away
""".trimIndent()

    fun parse(text: String): List<Category> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@mapNotNull null
            val bucket = Bucket.fromToken(parts[0]) ?: return@mapNotNull null
            val keywords = parts.drop(2).joinToString("|").split(",")
                .map { Text.normalize(it) }
                .filter { it.isNotEmpty() }
            Category(bucket, parts[1].trim(), keywords)
        }
        .toList()
}
