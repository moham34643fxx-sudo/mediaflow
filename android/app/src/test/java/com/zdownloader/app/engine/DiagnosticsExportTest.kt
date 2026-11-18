package com.zdownloader.app.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsExportTest {
    @Test fun exportsTimingsWithoutPrivateFieldsOrFreeText() {
        val secret="https://private.invalid/media?token=SECRET"
        val raw=JSONObject().put("stage","video_validation_timing").put("operation",secret).put("time",12345)
            .put("cookie",secret).put("details",JSONObject().put("validation_ms",502).put("url",secret)
                .put("decoder",secret).put("validation_check",secret).put("video_codec",secret))
        val safe=DiagnosticsExport().sanitize(raw.toString())!!
        assertEquals(502,safe.getJSONObject("details").getInt("validation_ms"))
        assertFalse(safe.toString().contains("SECRET"))
        assertFalse(safe.has("time"))
        assertEquals("op1",safe.getString("operation"))
    }

    @Test fun retainsDecoderEvidenceButRejectsSecretShapedAsDecoderName() {
        val exporter=DiagnosticsExport()
        fun row(decoder:String)=JSONObject().put("stage","av1_decoder_setup").put("details",JSONObject()
            .put("decoder",decoder).put("hardware",true).put("initialized",true)).toString()
        val valid=exporter.sanitize(row("c2.qti.av1.decoder"))!!.getJSONObject("details")
        assertEquals("c2.qti.av1.decoder",valid.getString("decoder"))
        assertTrue(valid.getBoolean("initialized"))
        assertTrue(valid.getBoolean("hardware"))
        assertFalse(exporter.sanitize(row("c2.qti.SECRET.decoder"))!!.getJSONObject("details").has("decoder"))
    }

    @Test fun correlatesOperationsAndUsesSeparateRelativeProcessClocks() {
        val exporter=DiagnosticsExport()
        fun row(session:String,time:Long)=exporter.sanitize(JSONObject().put("stage","transfer_completed")
            .put("capture_session",session).put("monotonic_ms",time).put("operation","private-id").toString())!!
        val first=row("session1",1000);val second=row("session1",1500);val restarted=row("session2",10)
        assertEquals(0,first.getLong("relative_ms"))
        assertEquals(500,second.getLong("relative_ms"))
        assertEquals(0,restarted.getLong("relative_ms"))
        assertEquals(first.getString("operation"),restarted.getString("operation"))
        assertNotEquals(first.getString("session"),restarted.getString("session"))
    }

    @Test fun rejectsMalformedOversizedAndUnknownStages() {
        val exporter=DiagnosticsExport()
        assertNull(exporter.sanitize("not json"))
        assertNull(exporter.sanitize("x".repeat(65537)))
        assertNull(exporter.sanitize("{\"stage\":\"secret_token\"}"))
        assertNull(exporter.sanitize("{\"stage\":\"https://private.invalid\"}"))
    }

    @Test fun preservesContainerDecisionsAndReturnCodesWithoutUnknownNestedValues() {
        val raw=JSONObject().put("stage","container_option").put("details",JSONObject()
            .put("container","webm").put("reason","codec_audio_incompatible").put("return_code",-1)
            .put("video_codec","av1").put("authorization",JSONObject().put("token","SECRET")))
        val details=DiagnosticsExport().sanitize(raw.toString())!!.getJSONObject("details")
        assertEquals("webm",details.getString("container"))
        assertEquals("codec_audio_incompatible",details.getString("reason"))
        assertEquals(-1,details.getInt("return_code"))
        assertFalse(details.has("authorization"))
    }
}
