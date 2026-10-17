package com.whitedns.vpn

import com.cat.client.CloudflareAccountLinks
import com.cat.client.CloudflareWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the invariant behind the «Invalid redirect_uri» fix.
 *
 * Cloudflare's login page validates the URL it must return to as an OAuth
 * redirect target. The token template link carries query parameters
 * (`permissionGroupKeys`, `accountId=*`, `zoneId=all`, `name`), so opening it
 * while signed out is exactly what produced «Invalid redirect_uri» for a user
 * who tapped the in-app «get a token» button *before* creating an account.
 *
 * The fix leads with parameter-free links (sign-up / login / plain tokens
 * page) and only offers the template link afterwards. These tests fail if
 * somebody ever gives those safe links a query string — which would quietly
 * recreate the dead end — or if the template URL stops containing the
 * pre-filled permission template (its whole reason to exist).
 */
class CloudflareAccountLinksTest {

    @Test
    fun accountLinksAreParameterFreeSoCloudflareCannotRejectThem() {
        for (url in CloudflareAccountLinks.REDIRECT_SAFE) {
            assertTrue("must be https: $url", url.startsWith("https://"))
            assertTrue("must stay on dash.cloudflare.com: $url", url.startsWith("https://dash.cloudflare.com/"))
            assertFalse("must stay parameter-free: $url", CloudflareAccountLinks.carriesQueryParameters(url))
            assertFalse("no fragment allowed: $url", url.contains('#'))
        }
        assertEquals(
            listOf(
                "https://dash.cloudflare.com/sign-up",
                "https://dash.cloudflare.com/login",
                "https://dash.cloudflare.com/profile/api-tokens",
            ),
            CloudflareAccountLinks.REDIRECT_SAFE,
        )
    }

    @Test
    fun tokenTemplateStaysParameterRichAndIsThereforeNotSafeWhileSignedOut() {
        val template = CloudflareWorker.CF_TOKEN_TEMPLATE_URL
        assertTrue(CloudflareAccountLinks.carriesQueryParameters(template))
        assertTrue(template.startsWith("https://dash.cloudflare.com/profile/api-tokens?"))
        // The pre-filled permission set is the reason this link exists at all.
        assertTrue(template.contains("permissionGroupKeys=%5B"))
        assertTrue(template.contains("workers_scripts"))
        assertTrue(template.contains("workers_kv_storage"))
        assertTrue(template.contains("name=Cat%20Panel"))
        // And the detector itself: a bare URL is safe, one with a query is not.
        assertFalse(CloudflareAccountLinks.carriesQueryParameters("https://dash.cloudflare.com/login"))
        assertTrue(CloudflareAccountLinks.carriesQueryParameters("https://dash.cloudflare.com/login?redirect_uri=x"))
    }

    @Test
    fun deployButtonLinkIsAUrlShapeCloudflareAccepts() {
        val deploy = CloudflareWorker.DEPLOY_BUTTON_URL
        assertTrue(deploy.startsWith("https://deploy.workers.cloudflare.com/?url="))
        assertTrue(deploy.endsWith("github.com/mazodimobinhost-creator/cat-client"))
        assertFalse("no nested query inside the repo url", deploy.endsWith("/") )
    }
}
