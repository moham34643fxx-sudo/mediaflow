package com.zdownloader.app.updates

import android.content.pm.PackageInstaller
import com.zdownloader.app.R
import com.zdownloader.app.background.errorResource
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.ui.updateErrorResource
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateInstallErrorsTest {
    @Test fun installerFailureStatusPreservesTheActionableCause() {
        val failures=mapOf(
            PackageInstaller.STATUS_FAILURE_ABORTED to UpdateError.INSTALL_CANCELLED,
            PackageInstaller.STATUS_FAILURE_CONFLICT to UpdateError.INSTALL_CONFLICT,
            PackageInstaller.STATUS_FAILURE_BLOCKED to UpdateError.INSTALL_BLOCKED,
            PackageInstaller.STATUS_FAILURE_INVALID to UpdateError.INSTALL_INVALID,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE to UpdateError.INSTALL_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_STORAGE to UpdateError.STORAGE,
            PackageInstaller.STATUS_FAILURE to UpdateError.INSTALL_FAILED,
            999 to UpdateError.INSTALL_FAILED
        )
        failures.forEach {(status,expected)->assertEquals("status=$status",expected,UpdateInstallErrors.fromStatus(status))}
    }

    @Test fun conflictAndInvalidApkFailuresExposeDifferentRecoveryGuidance() {
        val messages=mapOf(
            UpdateError.INSTALL_CONFLICT to R.string.update_error_conflict,
            UpdateError.SIGNATURE to R.string.update_error_signature,
            UpdateError.INSTALL_INVALID to R.string.update_error_install_invalid,
            UpdateError.INSTALL_BLOCKED to R.string.update_error_blocked,
            UpdateError.INSTALL_INCOMPATIBLE to R.string.update_error_compatibility
        )
        messages.forEach {(error,resource)->assertEquals(error.name,resource,updateErrorResource(error))}
    }

    @Test fun linkFailureMessagesDoNotCollapseIntoUnsupported() {
        val messages=mapOf(
            ErrorCode.INVALID_URL to R.string.error_invalid_url,
            ErrorCode.UNSUPPORTED_PLATFORM to R.string.error_unsupported,
            ErrorCode.VIDEO_NOT_FOUND to R.string.error_not_found,
            ErrorCode.PRIVATE_CONTENT to R.string.error_private,
            ErrorCode.AUTHENTICATION_REQUIRED to R.string.error_auth,
            ErrorCode.DNS_FAILED to R.string.error_dns,
            ErrorCode.SERVER_UNAVAILABLE to R.string.error_server_unavailable,
            ErrorCode.EXTRACTION_FAILED to R.string.error_extraction
        )
        messages.forEach {(error,resource)->assertEquals(error.name,resource,errorResource(error))}
    }
}
