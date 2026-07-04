package com.pochamps.supporter

import com.pochamps.supporter.data.DbManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** manifest 파싱 + 버전 비교 로직(순수 JVM, P13). */
class DbManifestTest {

    private val validJson = """
        {
          "manifestSchema": 1,
          "dataVersion": "20260705",
          "generatedAt": "2026-07-05T00:00:00Z",
          "files": [
            {"name":"pokedex_db.json","url":"pokedex_db.json.gz","sha256":"aa","size":10,"gzipSize":5},
            {"name":"usage_db.json","url":"usage_db.json.gz","sha256":"bb","size":20},
            {"name":"candidate_index.json","url":"candidate_index.json.gz","sha256":"cc","size":30}
          ]
        }
    """.trimIndent()

    @Test fun 파싱_정상() {
        val m = DbManifest.parse(validJson)!!
        assertEquals("20260705", m.dataVersion)
        assertEquals(1, m.manifestSchema)
        assertEquals(3, m.files.size)
        assertTrue(m.isSchemaSupported)
        assertTrue(m.hasAllRequiredFiles)
        assertEquals("pokedex_db.json.gz", m.fileByName("pokedex_db.json")!!.url)
    }

    @Test fun 파싱_손상은_null() {
        assertNull(DbManifest.parse("not json"))
        assertNull(DbManifest.parse("{"))
        assertNull(DbManifest.parse(""))
    }

    @Test fun 알수없는키_무시() {
        val m = DbManifest.parse("""{"dataVersion":"9","extra":123,"files":[]}""")!!
        assertEquals("9", m.dataVersion)
    }

    @Test fun 필수파일_누락_감지() {
        val m = DbManifest.parse(
            """{"dataVersion":"1","files":[{"name":"pokedex_db.json","url":"x","sha256":"a","size":1}]}"""
        )!!
        assertFalse(m.hasAllRequiredFiles)
    }

    @Test fun 스키마_미지원_감지() {
        val m = DbManifest.parse("""{"manifestSchema":99,"dataVersion":"1","files":[]}""")!!
        assertFalse(m.isSchemaSupported)
    }

    @Test fun 버전비교_정수() {
        assertTrue(DbManifest.isNewer("42", "9"))    // 정수 비교(사전순이면 틀림)
        assertFalse(DbManifest.isNewer("9", "42"))
        assertFalse(DbManifest.isNewer("42", "42"))
    }

    @Test fun 버전비교_날짜스탬프() {
        assertTrue(DbManifest.isNewer("20260706", "20260705"))
        assertFalse(DbManifest.isNewer("20260705", "20260706"))
    }

    @Test fun 버전비교_로컬없으면_항상갱신() {
        assertTrue(DbManifest.isNewer("1", null))
        assertTrue(DbManifest.isNewer("20260705", null))
    }
}
