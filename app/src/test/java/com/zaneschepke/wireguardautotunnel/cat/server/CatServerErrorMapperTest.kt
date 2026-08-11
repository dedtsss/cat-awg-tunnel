package com.zaneschepke.wireguardautotunnel.cat.server

import com.dedtsss.catawg.core.protocol.CatServerOperationException
import com.dedtsss.catawg.core.protocol.CatServerOperationStage
import com.zaneschepke.wireguardautotunnel.data.cat.CatCredentialPersistenceFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CatServerErrorMapperTest {
    @Test
    fun `pairing persistence failure has a safe distinct code and message`() {
        val token = "must-not-appear"
        val error =
            CatServerOperationException(
                CatServerOperationStage.CREDENTIAL_PERSISTENCE,
                IllegalStateException(token),
            )

        assertEquals("CREDENTIAL_PERSISTENCE_FAILED", CatServerErrorMapper.code(error))
        assertFalse(CatServerErrorMapper.userMessage(error).contains(token))
    }

    @Test
    fun `capabilities failure remains distinguishable after pairing`() {
        val error =
            CatServerOperationException(
                CatServerOperationStage.CAPABILITIES,
                IllegalStateException("transport"),
            )

        assertEquals("CAPABILITIES_FAILED", CatServerErrorMapper.code(error))
    }

    @Test
    fun `credential failure exposes only its safe persistence stage`() {
        val error =
            CatServerOperationException(
                CatServerOperationStage.CREDENTIAL_PERSISTENCE,
                CatCredentialPersistenceFailure(
                    "ENCRYPT_FAILED",
                    IllegalStateException("cipher provider detail"),
                ),
            )

        assertEquals("ENCRYPT_FAILED", CatServerErrorMapper.code(error))
        assertFalse(CatServerErrorMapper.userMessage(error).contains("cipher provider detail"))
    }
}
