package jp.linkserver.beastlocator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun candidateVersionMustNotBeOlderThanInstalledVersion() {
        assertTrue(isUpdateVersionCodeAcceptable(100L, 100L))
        assertTrue(isUpdateVersionCodeAcceptable(100L, 101L))
        assertFalse(isUpdateVersionCodeAcceptable(101L, 100L))
    }

    @Test
    fun sameSingleSignerIsAccepted() {
        assertTrue(
            isSigningLineageCompatible(
                installed = identity(current = setOf("signer-a")),
                candidate = identity(current = setOf("signer-a"))
            )
        )
    }

    @Test
    fun forwardCertificateRotationIsAccepted() {
        assertTrue(
            isSigningLineageCompatible(
                installed = identity(current = setOf("signer-a")),
                candidate = identity(
                    current = setOf("signer-b"),
                    history = setOf("signer-a", "signer-b")
                )
            )
        )
    }

    @Test
    fun candidateUsingOnlyOlderCertificateIsRejected() {
        assertFalse(
            isSigningLineageCompatible(
                installed = identity(
                    current = setOf("signer-b"),
                    history = setOf("signer-a", "signer-b")
                ),
                candidate = identity(current = setOf("signer-a"))
            )
        )
    }

    @Test
    fun unrelatedCertificateIsRejected() {
        assertFalse(
            isSigningLineageCompatible(
                installed = identity(current = setOf("signer-a")),
                candidate = identity(current = setOf("signer-x"))
            )
        )
    }

    @Test
    fun multipleSignerPackagesRequireExactSignerSet() {
        assertTrue(
            isSigningLineageCompatible(
                installed = identity(setOf("a", "b"), multiple = true),
                candidate = identity(setOf("a", "b"), multiple = true)
            )
        )
        assertFalse(
            isSigningLineageCompatible(
                installed = identity(setOf("a", "b"), multiple = true),
                candidate = identity(setOf("a", "c"), multiple = true)
            )
        )
        assertFalse(
            isSigningLineageCompatible(
                installed = identity(setOf("a", "b"), multiple = true),
                candidate = identity(setOf("a"), multiple = false)
            )
        )
    }

    private fun identity(
        current: Set<String>,
        history: Set<String> = current,
        multiple: Boolean = false
    ) = ApkSigningIdentity(
        currentSigners = current,
        signingHistory = history,
        hasMultipleSigners = multiple
    )
}
