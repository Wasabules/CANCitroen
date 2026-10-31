package com.geoffrey.cancitroen.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantAccessTest {
    private fun valid(text: String) = (HaHeaders.parse(text) as HaHeaders.Valid).headers
    private fun reason(text: String) = (HaHeaders.parse(text) as HaHeaders.Invalid).reason

    @Test fun jetonDeServiceCloudflare() = assertEquals(
        listOf("CF-Access-Client-Id" to "abc.access", "CF-Access-Client-Secret" to "s3cr:et"),
        valid("  CF-Access-Client-Id: abc.access\n\nCF-Access-Client-Secret :s3cr:et  \n"),
    )

    @Test fun rienSaisi() = assertEquals(emptyList<Pair<String, String>>(), valid("  \n "))

    @Test fun formatsRefuses() {
        assertTrue("Ligne 1" in reason("sans deux-points"))
        assertTrue("Ligne 2" in reason("A: 1\n: valeur"))
        assertTrue("nom" in reason("nom invalide: 1"))
        assertTrue("valeur" in reason("X-Vide:"))
        assertTrue("11 lignes" , "10" in reason((1..11).joinToString("\n") { "X-$it: 1" }))
    }

    @Test fun enTetesDeLAppProteges() {
        for (name in listOf("Authorization", "host", "Content-Type", "Content-Length", "User-Agent")) {
            assertTrue(name, "gér" in reason("$name: x"))
        }
    }

    @Test fun lInjectionDEnTeteEstImpossible() {
        assertTrue("caractère" in reason("X-A: un\u0001deux"))
        assertEquals("une ligne par en-tête", 2, valid("X-A: un\r\nX-B: deux").size)
    }

    @Test fun erreurNeCiteJamaisLaValeur() {
        assertFalse("SECRET" in reason("X-A: SECRET\u0001"))
    }

    @Test fun lesSecretsNeVontQuALAdresseSaisie() {
        val mine = "https://ha.example"
        assertTrue(HaHeaders.sameServer("https://ha.example/api/webhook/abc", mine))
        assertTrue(HaHeaders.sameServer("https://HA.example/api/webhook/abc", mine))
        assertFalse("Nabu Casa", HaHeaders.sameServer("https://hooks.nabu.casa/abc", mine))
        assertFalse("accès distant", HaHeaders.sameServer("https://xyz.ui.nabu.casa/api/webhook/abc", mine))
        assertFalse("sous-domaine", HaHeaders.sameServer("https://ha.example.evil.example/x", mine))
        assertFalse("en clair", HaHeaders.sameServer("http://ha.example/x", mine))
        assertFalse("autre port", HaHeaders.sameServer("https://ha.example:8443/x", mine))
        assertFalse("adresse vide", HaHeaders.sameServer("https://ha.example/x", ""))
    }

    private fun reply(code: Int, text: String = "", location: String? = null, cloudflare: Boolean = true) =
        HaDiagnostic.problem(HaDiagnostic.Reply(code, text, location, cloudflare))

    @Test fun cloudflareAccessRedirige() {
        val text = reply(302, location = "https://equipe.cloudflareaccess.com/cdn-cgi/access/login/ha.example?x=1")!!
        assertTrue("CF-Access-Client-Id" in text)
    }

    @Test fun autreRedirectionCiteSeulementLeServeur() {
        val text = reply(301, location = "https://autre.example/page?secret=1", cloudflare = false)!!
        assertTrue("autre.example" in text)
        assertFalse("secret" in text)
    }

    @Test fun refusDeHomeAssistantMemeDerriereCloudflare() =
        assertEquals("Home Assistant refuse le jeton.", reply(401, "401: Unauthorized"))

    @Test fun refusDeCloudflareDistinct() {
        assertTrue("Cloudflare" in reply(403, "<html>Forbidden</html>")!!)
        assertTrue("Cloudflare" in reply(401, "<html>Forbidden</html>")!!)
    }

    @Test fun proxyNonDeclareDansHomeAssistant() =
        assertTrue("trusted_proxies" in reply(400, "400: Bad Request")!!)

    @Test fun tunnelArrete() = assertTrue("tunnel" in reply(530)!!)

    @Test fun reponseNormale() = assertNull(reply(200, "{}"))
}
