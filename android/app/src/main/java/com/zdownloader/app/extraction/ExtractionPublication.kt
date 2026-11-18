package com.zdownloader.app.extraction

internal enum class ExtractionOutputOwnership { OWNED_PENDING, VISIBLE, EXPORTED, MISSING, UNCERTAIN }
internal enum class ExtractionRecoveryAction { ADOPT, REMOVE_PENDING, CLEAR_MISSING, PRESERVE }

/** Provider uncertainty is not evidence that a document still belongs to cleanup. */
internal fun extractionRecoveryAction(ownership:ExtractionOutputOwnership,matches:Boolean):ExtractionRecoveryAction =
    when(ownership) {
        ExtractionOutputOwnership.OWNED_PENDING->ExtractionRecoveryAction.REMOVE_PENDING
        ExtractionOutputOwnership.MISSING->ExtractionRecoveryAction.CLEAR_MISSING
        ExtractionOutputOwnership.VISIBLE,ExtractionOutputOwnership.EXPORTED->
            if(matches) ExtractionRecoveryAction.ADOPT else ExtractionRecoveryAction.PRESERVE
        ExtractionOutputOwnership.UNCERTAIN->ExtractionRecoveryAction.PRESERVE
    }
