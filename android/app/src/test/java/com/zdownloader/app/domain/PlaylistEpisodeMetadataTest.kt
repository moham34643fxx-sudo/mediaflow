package com.zdownloader.app.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistEpisodeMetadataTest {
    @Test fun onlyTopCinemaSeriesRoutesArePlaylistCandidates() {
        assertTrue(PlaylistUrls.isTopCinemaCollection("https://topcinemaa.one/series/show"))
        assertTrue(PlaylistUrls.candidate("https://www.topcinema.cam/series/season-3/"))
        assertFalse(PlaylistUrls.isTopCinemaCollection("http://topcinemaa.one/series/show"))
        assertFalse(PlaylistUrls.isTopCinemaCollection("https://topcinemaa.one.example/series/show"))
        assertFalse(PlaylistUrls.isTopCinemaCollection("https://user@topcinemaa.one/series/show"))
        assertFalse(PlaylistUrls.isTopCinemaCollection("https://topcinemaa.one:444/series/show"))
        assertFalse(PlaylistUrls.isTopCinemaCollection("https://topcinemaa.one/show/watch"))
        assertFalse(PlaylistUrls.candidate("https://topcinemaa.one/episode-title"))
    }

    @Test fun playlistParserKeepsEpisodeGroupingAndUnverifiedSeasonNotices() {
        val item=JSONObject()
            .put("key","episode-key")
            .put("url","https://topcinemaa.one/episode-title")
            .put("position",1)
            .put("title","Series Season 3 Episode 1")
            .put("season_number",3)
            .put("episode_number",1)
        val payload=JSONObject()
            .put("title","Series")
            .put("items",JSONArray().put(item))
            .put("next_offset",1)
            .put("has_more",false)
            .put("collection_type","topcinema_series")
            .put("unverified_seasons",JSONArray().put(JSONObject()
                .put("season_number",4)
                .put("title","Series Season 4")))

        val page=PlaylistPage.parse(payload,"https://topcinemaa.one/series/show")
        assertEquals("topcinema_series",page.collectionType)
        assertEquals(3,page.items.single().seasonNumber)
        assertEquals(1,page.items.single().episodeNumber)
        assertEquals(4,page.unverifiedSeasons.single().seasonNumber)
        assertEquals("Series Season 4",page.unverifiedSeasons.single().title)
    }
}
