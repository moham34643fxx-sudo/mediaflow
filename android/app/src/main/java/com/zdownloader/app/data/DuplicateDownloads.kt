package com.zdownloader.app.data

import com.zdownloader.app.domain.*

data class DuplicateReview(val completed:List<DownloadMatch>,val active:List<DownloadMatch>) {
    val found get()=completed.isNotEmpty()||active.isNotEmpty()
    val open get()=completed.firstOrNull()
}
class DuplicateDownloadException(val review:DuplicateReview):Exception()

internal suspend fun DownloadRepository.reviewDuplicates(media:Media,files:MediaFiles):DuplicateReview {
    val key=MediaIdentity.sourceKey(media)
    val candidates=dao.sourceDuplicates(key,MediaIdentity.urlKey(media.url))
    val completed=mutableListOf<DownloadMatch>()
    for(row in candidates.filter {it.status=="COMPLETED"}) if(files.accessible(row.uri)) completed+=row
    return DuplicateReview(completed,candidates.filter {it.status!="COMPLETED"})
}
